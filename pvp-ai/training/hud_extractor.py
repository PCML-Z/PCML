"""HUD 元素提取：从 FPV 视频帧识别血量/饥饿/快捷栏/冷却条。

MC 1.20.x 默认 HUD 布局（GUI scale 2 @ 1080p）：
  - 血量：屏幕底部偏左的 10 颗心
  - 饥饿：屏幕底部偏右的 10 个鸡腿
  - 快捷栏：底部中央 9 格
  - 攻击冷却条：快捷栏下方白色进度条

识别策略：
  - 血量/饥饿：HSV 红色/棕色 mask + 连通域计数
  - 快捷栏槽位：白色边框检测 + 选中格高亮
  - 冷却条：白色像素水平占比
"""
from __future__ import annotations

import cv2
import numpy as np


class HudExtractor:
    """从视频帧提取 HUD 状态信息。

    所有方法接收 BGR 格式的 numpy 数组，返回归一化后的浮点数或整数索引。
    坐标基于 1920×1080，自动按比例缩放到实际分辨率。
    """

    def __init__(self, frame_width: int = 1920, frame_height: int = 1080):
        self.w = frame_width
        self.h = frame_height
        # 从 1080p 基准坐标缩放到实际分辨率
        self.scale_x = frame_width / 1920.0
        self.scale_y = frame_height / 1080.0

    def _scale_rect(self, rect: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
        x1, y1, x2, y2 = rect
        return (
            int(x1 * self.scale_x), int(y1 * self.scale_y),
            int(x2 * self.scale_x), int(y2 * self.scale_y),
        )

    def extract_health(self, frame: np.ndarray) -> float:
        """提取血量（0.0-1.0）。

        MC 心形是红色（BGR ~ (40, 40, 220)），用 HSV 红色 mask 计数。
        一颗心约占 mask 区域 1/10。
        """
        x1, y1, x2, y2 = self._scale_rect((530, 950, 750, 990))
        roi = frame[y1:y2, x1:x2]
        if roi.size == 0:
            return 1.0
        hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
        # 红色 HSV 范围（两个区间：低 H 和高 H）
        mask1 = cv2.inRange(hsv, np.array([0, 100, 100]), np.array([10, 255, 255]))
        mask2 = cv2.inRange(hsv, np.array([160, 100, 100]), np.array([180, 255, 255]))
        mask = cv2.bitwise_or(mask1, mask2)
        # 噪声去除
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
        # 红色像素占比 → 血量
        ratio = np.count_nonzero(mask) / mask.size
        # 满血时 ratio ≈ 0.1，归一化到 0-1
        return float(min(1.0, ratio / 0.1))

    def extract_hunger(self, frame: np.ndarray) -> float:
        """提取饥饿值（0.0-1.0）。

        鸡腿图标的棕色（BGR ~ (40, 100, 140)）。
        """
        x1, y1, x2, y2 = self._scale_rect((1170, 950, 1390, 990))
        roi = frame[y1:y2, x1:x2]
        if roi.size == 0:
            return 1.0
        hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
        # 棕色 HSV 范围
        mask = cv2.inRange(hsv, np.array([10, 80, 80]), np.array([25, 200, 200]))
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
        ratio = np.count_nonzero(mask) / mask.size
        return float(min(1.0, ratio / 0.08))

    def extract_cooldown(self, frame: np.ndarray) -> float:
        """提取攻击冷却进度（0.0-1.0）。

        冷却条是快捷栏下方一根白色横条，从右向左填满表示冷却完成。
        """
        x1, y1, x2, y2 = self._scale_rect((820, 1030, 1100, 1040))
        roi = frame[y1:y2, x1:x2]
        if roi.size == 0:
            return 1.0
        gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
        # 白色阈值
        mask = cv2.threshold(gray, 200, 255, cv2.THRESH_BINARY)[1]
        # 白色像素在水平方向上的最右位置 → 冷却进度
        cols_with_white = np.where(mask.any(axis=0))[0]
        if len(cols_with_white) == 0:
            return 1.0  # 无冷却条 = 冷却完成
        rightmost = cols_with_white.max()
        leftmost = cols_with_white.min()
        # 冷却条从右向左填满，进度 = (右端 - 最右白色位置) / 宽度
        # 简化：直接用白色像素占比
        ratio = np.count_nonzero(mask) / mask.size
        return float(min(1.0, ratio / 0.3))

    def extract_hotbar_slot(self, frame: np.ndarray) -> int:
        """提取当前选中的快捷栏槽位（0-8）。

        选中槽有白色高亮边框。把快捷栏 9 等分，找白色边框最明显的格子。
        """
        x1, y1, x2, y2 = self._scale_rect((760, 970, 1160, 1030))
        roi = frame[y1:y2, x1:x2]
        if roi.size == 0:
            return 0
        gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
        # 检测白色边框
        mask = cv2.threshold(gray, 180, 255, cv2.THRESH_BINARY)[1]
        # 9 等分
        cell_w = mask.shape[1] // 9
        best_slot = 0
        best_score = 0
        for i in range(9):
            cell = mask[:, i * cell_w:(i + 1) * cell_w]
            # 边框 = 周边一圈白色像素数
            border = np.concatenate([
                cell[0, :].ravel(), cell[-1, :].ravel(),
                cell[:, 0].ravel(), cell[:, -1].ravel()
            ])
            score = np.count_nonzero(border)
            if score > best_score:
                best_score = score
                best_slot = i
        return best_slot

    def extract_blocking(self, frame: np.ndarray) -> int:
        """检测玩家是否举盾（手部区域出现盾牌纹理）。

        简化实现：检测右下角手部区域是否有大量黑色（盾牌边框）。
        """
        x1, y1, x2, y2 = self._scale_rect((1300, 600, 1920, 1080))
        roi = frame[y1:y2, x1:x2]
        if roi.size == 0:
            return 0
        gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
        # 盾牌有大量深色像素
        dark_ratio = np.count_nonzero(gray < 60) / gray.size
        return 1 if dark_ratio > 0.25 else 0

    def extract_all(self, frame: np.ndarray) -> dict:
        """一次性提取所有 HUD 字段。"""
        return {
            "health": self.extract_health(frame),
            "hunger": self.extract_hunger(frame),
            "cooldown": self.extract_cooldown(frame),
            "hotbar": self.extract_hotbar_slot(frame),
            "blocking": self.extract_blocking(frame),
        }
