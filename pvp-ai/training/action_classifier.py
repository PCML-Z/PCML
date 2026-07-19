"""动作识别：从光流场和画面变化识别玩家动作。

识别的动作：
  - move_dir: 0-8（基于整体光流方向推断 WASD 组合）
  - jump: 0/1（垂直方向光流突然正向脉冲）
  - sneak: 0/1（默认 0，sneak 时画面会降低 0.1-0.3 格高度）
  - attack: 0/1（手部区域光流幅值突增）
  - use: 0/1（手部区域持续高光流且非挥剑模式）

注意：
  - FPV 视频中"玩家向前走"会让画面整体向前扩张（光流径向发散）
  - "后退"则径向收缩
  - "左右平移"画面整体平移
  - "跳跃"画面整体短暂下移（视角不变但玩家上升）
"""
from __future__ import annotations

import cv2
import numpy as np

from video_config import (
    ATTACK_FLOW_THRESHOLD, JUMP_FLOW_THRESHOLD, MOVE_MAG_THRESHOLD, RADIAL_THRESHOLD,
)
from perspective_analyzer import flow_magnitude


class ActionClassifier:
    """从光流场和手部 ROI 识别本 tick 的动作。"""

    def __init__(
        self,
        frame_width: int = 1920,
        frame_height: int = 1080,
    ):
        self.w = frame_width
        self.h = frame_height
        # 手部区域（默认右下，第一人称右手持物）
        self.hand_roi = self._scale_rect((1300, 600, 1920, 1080))
        # 跳跃检测：保存最近 N 帧的垂直光流均值
        self.vertical_flow_history: list[float] = []
        self.history_size = 5

    def _scale_rect(self, rect: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
        x1, y1, x2, y2 = rect
        return (
            int(x1 * self.w / 1920.0), int(y1 * self.h / 1080.0),
            int(x2 * self.w / 1920.0), int(y2 * self.h / 1080.0),
        )

    def classify(
        self,
        flow: np.ndarray,
        frame: np.ndarray,
        is_attacking_prev: bool = False,
    ) -> dict:
        """识别本帧的动作。

        参数：
          flow: 稠密光流场 [H, W, 2]
          frame: 当前 BGR 帧
          is_attacking_prev: 上一帧是否在攻击（用于攻击去抖）

        返回 dict:
          move_dir: 0-8
          jump: 0/1
          attack: 0/1
          use: 0/1
        """
        if flow.size == 0:
            return {"move_dir": 0, "jump": 0, "attack": 0, "use": 0}

        # === 移动方向推断 ===
        move_dir = self._infer_move_dir(flow)

        # === 跳跃检测 ===
        jump = self._detect_jump(flow)

        # === 攻击检测（手部光流突增）===
        attack = self._detect_attack(flow, is_attacking_prev)

        # === 使用/举盾检测 ===
        use = self._detect_use(flow, attack)

        return {"move_dir": move_dir, "jump": jump, "attack": attack, "use": use}

    def _infer_move_dir(self, flow: np.ndarray) -> int:
        """从整体光流模式推断移动方向。

        光流模式：
          - 前进：径向发散（中心向外的光流）
          - 后退：径向收缩（向中心的光流）
          - 左移：整体向右光流
          - 右移：整体向左光流
          - 静止：光流幅值极小
        """
        h, w = flow.shape[:2]
        cy, cx = h // 2, w // 2

        # 中心 ROI 平均位移
        r = min(h, w) // 4
        center = flow[cy - r:cy + r, cx - r:cx + r]
        mean_x = float(np.mean(center[:, :, 0]))
        mean_y = float(np.mean(center[:, :, 1]))
        mag = float(np.mean(flow_magnitude(center)))

        # 静止阈值
        if mag < MOVE_MAG_THRESHOLD:
            return 0

        # 径向度：中心到边缘的光流方向是否与径向一致
        # 计算光流向量与"中心→像素"向量的点积
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        dx = xx - cx
        dy = yy - cy
        dist = np.sqrt(dx * dx + dy * dy) + 1e-6
        # 单位径向向量
        rx = dx / dist
        ry = dy / dist
        # 光流与径向向量的点积（正=向外，负=向内）
        radial_dot = flow[:, :, 0] * rx + flow[:, :, 1] * ry
        avg_radial = float(np.mean(radial_dot[cy - r:cy + r, cx - r:cx + r]))

        # 横向移动检测：mean_x 较大且径向度低
        is_strafing = abs(mean_x) > 1.0 and abs(avg_radial) < RADIAL_THRESHOLD

        if avg_radial > RADIAL_THRESHOLD:
            # 径向发散 → 前进
            if is_strafing and mean_x > 0:
                return 1  # FL（画面右移 = 玩家左前）
            elif is_strafing and mean_x < 0:
                return 2  # FR
            return 3  # F
        elif avg_radial < -RADIAL_THRESHOLD:
            # 径向收缩 → 后退
            if is_strafing and mean_x > 0:
                return 6  # BL
            elif is_strafing and mean_x < 0:
                return 7  # BR
            return 8  # B
        elif mean_x > 1.0:
            return 4  # L（画面右移 = 玩家左移）
        elif mean_x < -1.0:
            return 5  # R
        return 0

    def _detect_jump(self, flow: np.ndarray) -> int:
        """跳跃检测：垂直光流突然正向脉冲（玩家上升=画面下移）。"""
        h, w = flow.shape[:2]
        cy, cx = h // 2, w // 2
        r = min(h, w) // 4
        center = flow[cy - r:cy + r, cx - r:cx + r]
        mean_y = float(np.mean(center[:, :, 1]))

        self.vertical_flow_history.append(mean_y)
        if len(self.vertical_flow_history) > self.history_size:
            self.vertical_flow_history.pop(0)

        if len(self.vertical_flow_history) < 3:
            return 0

        # 跳跃特征：垂直光流突然变大（负值 = 画面下移 = 玩家上升）
        recent_max = max(self.vertical_flow_history[-3:])
        if recent_max < -JUMP_FLOW_THRESHOLD:
            return 1
        return 0

    def _detect_attack(self, flow: np.ndarray, prev: bool) -> int:
        """攻击检测：手部区域光流幅值突增。

        攻击动画 5-6 tick，检测到后用 prev 去抖避免连续触发。
        """
        x1, y1, x2, y2 = self.hand_roi
        # 缩放到光流场分辨率
        h, w = flow.shape[:2]
        sx1 = int(x1 * w / self.w)
        sy1 = int(y1 * h / self.h)
        sx2 = int(x2 * w / self.w)
        sy2 = int(y2 * h / self.h)
        hand_flow = flow[sy1:sy2, sx1:sx2]
        if hand_flow.size == 0:
            return 0
        mag = float(np.mean(flow_magnitude(hand_flow)))

        # 攻击特征：手部光流幅值突增
        if mag > ATTACK_FLOW_THRESHOLD and not prev:
            return 1
        return 0

    def _detect_use(self, flow: np.ndarray, attack: int) -> int:
        """使用/举盾检测：手部区域持续中等光流（非攻击性突增）。

        简化实现：举盾通常持续多帧，攻击是单帧脉冲。
        若手部光流中等且未在攻击，且持续 5+ 帧，判定为 use。
        """
        # 简化：与攻击互斥，且当前帧手部光流中等
        x1, y1, x2, y2 = self.hand_roi
        h, w = flow.shape[:2]
        sx1 = int(x1 * w / self.w)
        sy1 = int(y1 * h / self.h)
        sx2 = int(x2 * w / self.w)
        sy2 = int(y2 * h / self.h)
        hand_flow = flow[sy1:sy2, sx1:sx2]
        if hand_flow.size == 0:
            return 0
        mag = float(np.mean(flow_magnitude(hand_flow)))

        # 2-3 像素幅值 + 非攻击 → use
        if attack == 0 and 2.0 < mag < ATTACK_FLOW_THRESHOLD:
            return 1
        return 0

    def reset(self):
        self.vertical_flow_history.clear()
