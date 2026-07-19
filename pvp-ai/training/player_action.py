"""视角量化工具函数（与 mod 侧 PlayerAction 保持一致）。

从视频提取的 Δyaw/Δpitch 是连续浮点数，需要量化到 11 个 bin
才能与 mod 录制的 JSONL 格式一致。
"""
from __future__ import annotations

import math

YAW_BIN = 11
PITCH_BIN = 11

YAW_RANGE = math.pi / 4   # ±π/4
PITCH_RANGE = math.pi / 8  # ±π/8


def quantize_yaw(delta: float) -> int:
    """Δyaw（弧度）量化到 [0, 10]。"""
    norm = (delta / YAW_RANGE + 1) / 2  # [0, 1]
    clamped = max(0.0, min(1.0, norm))
    return int(clamped * (YAW_BIN - 1))


def quantize_pitch(delta: float) -> int:
    norm = (delta / PITCH_RANGE + 1) / 2
    clamped = max(0.0, min(1.0, norm))
    return int(clamped * (PITCH_BIN - 1))


def dequantize_yaw(bin_idx: int) -> float:
    norm = bin_idx / (YAW_BIN - 1)
    return (norm * 2 - 1) * YAW_RANGE


def dequantize_pitch(bin_idx: int) -> float:
    norm = bin_idx / (PITCH_BIN - 1)
    return (norm * 2 - 1) * PITCH_RANGE
