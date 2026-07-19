"""视角变化提取：用光流法从 FPV 视频帧推断 Δyaw/Δpitch。

原理：
  - 第一人称视角下，玩家转动视角时整个画面会朝反方向位移
  - 用稠密光流（Farneback）计算帧间全局位移
  - 水平位移 → Δyaw（向左转 = 画面向右移）
  - 垂直位移 → Δpitch（向上看 = 画面向下移）

校准：
  - PIXEL_TO_RADIAN 常数与 FOV、分辨率、GUI scale 相关
  - 默认值适用于 1080p + 90 FOV + 默认 GUI scale
  - 若视频源不同需通过校准工具调整
"""
from __future__ import annotations

import cv2
import numpy as np

from video_config import OPTICAL_FLOW_PARAMS, PIXEL_TO_RADIAN_YAW, PIXEL_TO_RADIAN_PITCH


class PerspectiveAnalyzer:
    """基于光流的视角变化提取器。

    内部维护上一帧的灰度图，每次调用 perspective_delta 返回与上一帧的视角差。
    """

    def __init__(
        self,
        pixel_to_yaw: float = PIXEL_TO_RADIAN_YAW,
        pixel_to_pitch: float = PIXEL_TO_RADIAN_PITCH,
        # 中心区域 ROI（避免 HUD 边框干扰光流）
        roi: tuple[int, int, int, int] | None = None,
    ):
        self.pixel_to_yaw = pixel_to_yaw
        self.pixel_to_pitch = pixel_to_pitch
        self.prev_gray: np.ndarray | None = None
        self.roi = roi  # (x1, y1, x2, y2)，None = 全帧

    def _to_gray(self, frame: np.ndarray) -> np.ndarray:
        if self.roi is not None:
            x1, y1, x2, y2 = self.roi
            frame = frame[y1:y2, x1:x2]
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        # 降采样到 480p 加速光流计算
        h, w = gray.shape[:2]
        if max(h, w) > 480:
            scale = 480.0 / max(h, w)
            gray = cv2.resize(gray, None, fx=scale, fy=scale,
                              interpolation=cv2.INTER_AREA)
        return gray

    def perspective_delta(self, frame: np.ndarray) -> tuple[float, float, np.ndarray]:
        """计算与上一帧的视角变化。

        返回 (dyaw_radian, dpitch_radian, flow_field)。
        首次调用返回 (0, 0, zeros)。
        flow_field 是稠密光流（用于动作识别）。
        """
        gray = self._to_gray(frame)
        if self.prev_gray is None:
            self.prev_gray = gray
            h, w = gray.shape[:2]
            return 0.0, 0.0, np.zeros((h, w, 2), dtype=np.float32)

        # Farneback 稠密光流
        flow = cv2.calcOpticalFlowFarneback(
            self.prev_gray, gray, None, **OPTICAL_FLOW_PARAMS
        )
        self.prev_gray = gray

        # 中心区域平均位移（避免边缘抖动）
        h, w = flow.shape[:2]
        cy, cx = h // 2, w // 2
        r = min(h, w) // 4
        center_flow = flow[cy - r:cy + r, cx - r:cx + r]
        mean_x = float(np.median(center_flow[:, :, 0]))
        mean_y = float(np.median(center_flow[:, :, 1]))

        # 转换到视角变化
        # 玩家向左转（yaw 减小）= 画面整体向右移（mean_x > 0）→ Δyaw < 0
        dyaw = -mean_x * self.pixel_to_yaw
        # 玩家向上看（pitch 减小）= 画面整体向下移（mean_y > 0）→ Δpitch < 0
        dpitch = -mean_y * self.pixel_to_pitch

        return dyaw, dpitch, flow

    def reset(self):
        self.prev_gray = None


def flow_magnitude(flow: np.ndarray) -> np.ndarray:
    """计算光流场每个像素的位移幅值。"""
    return np.sqrt(flow[:, :, 0] ** 2 + flow[:, :, 1] ** 2)
