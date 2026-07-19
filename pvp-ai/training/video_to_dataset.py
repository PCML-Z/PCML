"""FPV PvP 视频导入工具：从第一人称 PvP 视频提取训练数据。

输出格式与 mod 录制的 JSONL 完全一致，可直接用 train.py 训练。

用法：
  python video_to_dataset.py --video path/to/pvp.mp4 --out ~/.pmcl/pvp-ai/datasets
  python video_to_dataset.py --video pvp.mp4 --yolo  # 启用 YOLOv8 敌人检测（需安装）

流程：
  1. OpenCV 解码视频帧
  2. HudExtractor 提取血量/饥饿/快捷栏/冷却条/举盾
  3. PerspectiveAnalyzer 光流法推断 Δyaw/Δpitch
  4. ActionClassifier 识别移动/跳跃/攻击/使用
  5. EnemyDetector（可选 YOLOv8）检测敌人位置
  6. 组装 30 维状态 + 8 字段动作 → JSONL

注意事项：
  - 默认假设 1920×1080 @ 60fps，自动按比例缩放 HUD 坐标
  - 帧率自动重采样到 20 TPS（MC 内部 tick 率）
  - CV 提取的标签是伪标签，准确度 60-80%，建议混合真实录制数据训练
  - YOLO 模块可选（无 ultralytics 时降级为简化运动检测）
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np

# 让脚本能在 pvp-ai/training/ 目录下直接运行
sys.path.insert(0, str(Path(__file__).resolve().parent))

from dataset import STATE_DIM, WINDOW_SIZE
from video_config import TARGET_TPS
from hud_extractor import HudExtractor
from perspective_analyzer import PerspectiveAnalyzer
from action_classifier import ActionClassifier
from player_action import quantize_yaw, quantize_pitch

# 可选依赖：YOLOv8
try:
    from ultralytics import YOLO
    _HAS_YOLO = True
except ImportError:
    _HAS_YOLO = False


class EnemyDetector:
    """敌人检测器。

    默认（无 YOLO）：用帧间差分 + 形态学找移动的玩家模型。
    可选（有 YOLO）：用预训练的人形检测模型。
    """

    def __init__(self, frame_width: int, frame_height: int, use_yolo: bool = False):
        self.w = frame_width
        self.h = frame_height
        self.use_yolo = use_yolo and _HAS_YOLO
        self.model = None
        self.prev_gray = None
        if self.use_yolo:
            # 加载 yolov8n.pt（首次运行自动下载）
            try:
                self.model = YOLO("yolov8n.pt")
                print("[video] YOLOv8 加载成功")
            except Exception as e:
                print(f"[video] YOLOv8 加载失败，降级到运动检测: {e}")
                self.use_yolo = False

    def detect(self, frame: np.ndarray) -> dict | None:
        """检测画面中最显著的敌人，返回其位置和距离估计。

        返回 dict：
          cx, cy: 敌人中心像素坐标（归一化到 [0, 1]）
          width: 边界框宽度（归一化）
          height: 边界框高度（归一化）
          horizontal_dist: 估计水平距离（用边界框宽度反推）
          vertical_dist: 估计垂直距离
          has_los: 1.0
          in_view: 1.0
          self_facing: 1.0  # 假设敌人被检测到就在视野中
          enemy_facing: 0.5  # 无法判断，给中位数
        """
        if self.use_yolo and self.model is not None:
            return self._detect_yolo(frame)
        return self._detect_motion(frame)

    def _detect_yolo(self, frame: np.ndarray) -> dict | None:
        """YOLOv8 检测人形目标（class 0 = person）。"""
        results = self.model(frame, verbose=False, conf=0.4)
        best = None
        best_area = 0
        for r in results:
            for box in r.boxes:
                if int(box.cls[0]) != 0:  # 0 = person
                    continue
                x1, y1, x2, y2 = box.xyxy[0].tolist()
                area = (x2 - x1) * (y2 - y1)
                if area > best_area:
                    best_area = area
                    best = (x1, y1, x2, y2)
        if best is None:
            return None
        x1, y1, x2, y2 = best
        cx = (x1 + x2) / 2 / self.w
        cy = (y1 + y2) / 2 / self.h
        w = (x2 - x1) / self.w
        h = (y2 - y1) / self.h
        # 用边界框宽度反推距离：MC 玩家高 1.8 块，宽 0.6 块
        # 简化：width=0.1 ≈ 距离 10 块，width=0.5 ≈ 距离 2 块
        horizontal_dist = max(0.5, 1.0 / max(w, 0.05))
        return {
            "cx": cx, "cy": cy, "width": w, "height": h,
            "horizontal_dist": horizontal_dist,
            "vertical_dist": 0.0,
            "has_los": 1.0, "in_view": 1.0,
            "self_facing": 1.0, "enemy_facing": 0.5,
            "is_weapon_visible": 0.0,  # YOLO 无法判断武器
        }

    def _detect_motion(self, frame: np.ndarray) -> dict | None:
        """运动检测：帧间差分找最显著的移动区域。"""
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        gray = cv2.GaussianBlur(gray, (21, 21), 0)
        if self.prev_gray is None:
            self.prev_gray = gray
            return None
        diff = cv2.absdiff(self.prev_gray, gray)
        self.prev_gray = gray
        # 二值化 + 形态学
        _, mask = cv2.threshold(diff, 25, 255, cv2.THRESH_BINARY)
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
        mask = cv2.dilate(mask, np.ones((5, 5), np.uint8), iterations=2)

        # 找最大连通域
        num, labels, stats, centroids = cv2.connectedComponentsWithStats(mask)
        if num < 2:
            return None
        # 跳过背景（label 0），找最大前景
        largest_label = 1 + np.argmax(stats[1:, cv2.CC_STAT_AREA])
        area = stats[largest_label, cv2.CC_STAT_AREA]
        if area < 500:  # 太小，忽略
            return None
        x = stats[largest_label, cv2.CC_STAT_LEFT]
        y = stats[largest_label, cv2.CC_STAT_TOP]
        w = stats[largest_label, cv2.CC_STAT_WIDTH]
        h = stats[largest_label, cv2.CC_STAT_HEIGHT]
        cx = (x + w / 2) / self.w
        cy = (y + h / 2) / self.h
        w_norm = w / self.w
        h_norm = h / self.h
        horizontal_dist = max(0.5, 1.0 / max(w_norm, 0.05))
        return {
            "cx": cx, "cy": cy, "width": w_norm, "height": h_norm,
            "horizontal_dist": horizontal_dist,
            "vertical_dist": 0.0,
            "has_los": 1.0, "in_view": 1.0,
            "self_facing": 1.0, "enemy_facing": 0.5,
            "is_weapon_visible": 0.0,
        }


class VideoExtractor:
    """视频→JSONL 转换主流程。"""

    def __init__(
        self,
        video_path: str,
        use_yolo: bool = False,
        target_fps: int = TARGET_TPS,
    ):
        self.video_path = video_path
        self.target_fps = target_fps
        self.cap = cv2.VideoCapture(video_path)
        if not self.cap.isOpened():
            raise RuntimeError(f"无法打开视频: {video_path}")

        self.fps = self.cap.get(cv2.CAP_PROP_FPS) or 30.0
        self.total_frames = int(self.cap.get(cv2.CAP_PROP_FRAME_COUNT))
        self.frame_width = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        self.frame_height = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        # 重采样间隔：每 N 帧取 1 帧凑近 target_fps
        self.sample_interval = max(1, round(self.fps / target_fps))

        print(f"[video] 视频: {Path(video_path).name}")
        print(f"[video]   分辨率: {self.frame_width}×{self.frame_height}")
        print(f"[video]   帧率: {self.fps:.1f} fps → 重采样到 {target_fps} TPS")
        print(f"[video]   总帧数: {self.total_frames}")
        print(f"[video]   采样间隔: 每 {self.sample_interval} 帧取 1")

        self.hud = HudExtractor(self.frame_width, self.frame_height)
        self.perspective = PerspectiveAnalyzer()
        self.action = ActionClassifier(self.frame_width, self.frame_height)
        self.enemy = EnemyDetector(self.frame_width, self.frame_height, use_yolo)

    def extract(self, out_path: Path) -> int:
        """提取视频并写入 JSONL，返回样本数。"""
        out_path.parent.mkdir(parents=True, exist_ok=True)
        sample_count = 0
        tick = 0
        prev_attack = False
        start_time = time.time()

        with out_path.open("w", encoding="utf-8") as f:
            frame_idx = 0
            while True:
                ret, frame = self.cap.read()
                if not ret:
                    break
                frame_idx += 1
                if frame_idx % self.sample_interval != 0:
                    continue

                # === HUD 提取 ===
                hud = self.hud.extract_all(frame)

                # === 视角变化（光流）===
                dyaw, dpitch, flow = self.perspective.perspective_delta(frame)

                # === 动作识别 ===
                actions = self.action.classify(flow, frame, prev_attack)
                prev_attack = actions["attack"] == 1

                # === 敌人检测 ===
                enemy_info = self.enemy.detect(frame)

                # === 组装 30 维状态 ===
                state = self._build_state(hud, enemy_info, dyaw, dpitch)

                # === 量化视角变化到 bin ===
                yaw_bin = quantize_yaw(dyaw)
                pitch_bin = quantize_pitch(dpitch)

                # === 写入 JSONL ===
                obj = {
                    "tick": tick,
                    "rel_tick": tick,
                    "state": [float(x) for x in state],
                    "action": [
                        actions["move_dir"], 0,  # jump 后面填
                        0,  # sneak
                        actions["attack"],
                        actions["use"],
                        hud["hotbar"],
                        yaw_bin,
                        pitch_bin,
                    ],
                }
                # 修正 jump 字段索引
                obj["action"][1] = actions["jump"]
                f.write(json.dumps(obj) + "\n")
                sample_count += 1
                tick += 1

                # 进度报告
                if sample_count % 100 == 0:
                    elapsed = time.time() - start_time
                    done = frame_idx / self.total_frames if self.total_frames else 0
                    eta = elapsed / max(done, 0.01) - elapsed if done > 0.01 else 0
                    print(f"[video] {sample_count} samples, {done:.1%} done, "
                          f"elapsed {elapsed:.1f}s, ETA {eta:.1f}s")

        self.cap.release()
        elapsed = time.time() - start_time
        print(f"[video] 完成：{sample_count} samples 写入 {out_path}")
        print(f"[video] 耗时 {elapsed:.1f}s ({sample_count / max(elapsed, 0.1):.1f} samples/s)")
        return sample_count

    def _build_state(
        self,
        hud: dict,
        enemy_info: dict | None,
        dyaw: float,
        dpitch: float,
    ) -> list[float]:
        """组装 30 维状态向量。

        视频源缺失的字段（如自身精确位置/速度）用合理默认值填充。
        """
        if enemy_info is None:
            enemy_info = {
                "cx": 0.5, "cy": 0.5, "width": 0.0, "height": 0.0,
                "horizontal_dist": 15.0, "vertical_dist": 0.0,
                "has_los": 0.0, "in_view": 0.0,
                "self_facing": 0.0, "enemy_facing": 0.0,
                "is_weapon_visible": 0.0,
            }

        # 自身状态（视频无法获取的用默认值）
        # 0-2: 相对敌人位置（视频无法精确，用敌人水平距离反推）
        dx_self = -enemy_info["horizontal_dist"] / 10.0  # 自身在敌人左侧
        dy_self = 0.0
        dz_self = 0.0
        # 3-5: 自身速度（视频无法获取，用光流方向近似）
        vx = -dyaw * 10  # 视角变化大 = 在转视角，不是移动；这里粗略映射
        vy = 0.0
        vz = 0.0
        # 6-7: yaw/pitch（视频只能拿到增量，无绝对值；用 0 占位）
        yaw_norm = 0.0
        pitch_norm = 0.0
        # 8-13: HUD 字段
        health = hud["health"]
        hunger = hud["hunger"]
        cooldown = hud["cooldown"]
        blocking = float(hud["blocking"])
        on_ground = 1.0  # 默认在地面
        held_slot = hud["hotbar"] / 8.0

        # 敌人状态（14-23）
        dx_enemy = enemy_info["horizontal_dist"] / 10.0
        dy_enemy = 0.0
        dz_enemy = 0.0
        # 敌人速度用边界框变化近似（这里简化为 0）
        evx = 0.0
        evy = 0.0
        evz = 0.0
        enemy_yaw = 0.0
        # 敌人血量无法从视频获取，用 1.0 占位
        enemy_health = 1.0
        enemy_blocking = 0.0
        enemy_weapon = enemy_info.get("is_weapon_visible", 0.0)

        # 关系（24-29）
        horiz_dist = enemy_info["horizontal_dist"] / 10.0
        vert_dist = enemy_info["vertical_dist"] / 5.0
        has_los = enemy_info["has_los"]
        in_view = enemy_info["in_view"]
        self_facing = enemy_info["self_facing"]
        enemy_facing = enemy_info["enemy_facing"]

        return [
            dx_self, dy_self, dz_self,
            vx, vy, vz,
            yaw_norm, pitch_norm,
            health, hunger, cooldown, blocking, on_ground, held_slot,
            dx_enemy, dy_enemy, dz_enemy,
            evx, evy, evz,
            enemy_yaw, enemy_health, enemy_blocking, enemy_weapon,
            horiz_dist, vert_dist, has_los, in_view, self_facing, enemy_facing,
        ]

    def __del__(self):
        try:
            self.cap.release()
        except Exception:
            pass


def main():
    p = argparse.ArgumentParser(description="FPV PvP 视频导入工具")
    p.add_argument("--video", required=True, help="视频文件路径")
    p.add_argument("--out", default=str(Path.home() / ".pmcl/pvp-ai/datasets"),
                   help="输出目录")
    p.add_argument("--yolo", action="store_true",
                   help="启用 YOLOv8 敌人检测（需先 pip install ultralytics）")
    p.add_argument("--name", default=None,
                   help="输出文件名前缀（默认用视频文件名）")
    args = p.parse_args()

    video_path = Path(args.video)
    if not video_path.exists():
        print(f"[error] 视频不存在: {video_path}")
        sys.exit(1)

    name = args.name or video_path.stem
    out_file = Path(args.out) / f"session_video_{name}.jsonl"

    if args.yolo and not _HAS_YOLO:
        print("[warn] 未安装 ultralytics，降级到运动检测。安装：pip install ultralytics")

    print(f"[video] 开始提取: {video_path}")
    extractor = VideoExtractor(str(video_path), use_yolo=args.yolo)
    n = extractor.extract(out_file)
    print(f"\n[done] 提取完成，{n} 样本写入 {out_file}")
    print(f"       可直接运行: python train.py --data {args.out}")


if __name__ == "__main__":
    main()
