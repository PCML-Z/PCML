"""FPV PvP 视频提取配置常量。

HUD 元素坐标基于 MC 1.20.x 默认 GUI scale 2（最常见）。
若视频分辨率或 GUI scale 不同，需在 VideoExtractor 构造时覆盖。
"""
from __future__ import annotations

# === MC 默认 HUD 坐标（基于 1920×1080，GUI scale 2）===
# 所有坐标格式：(x_left, y_top, x_right, y_bottom)
HUD_REGIONS = {
    # 血量：屏幕底部偏左的 10 颗心
    "health": (530, 950, 750, 990),
    # 饥饿：屏幕底部偏右的 10 个鸡腿
    "hunger": (1170, 950, 1390, 990),
    # 快捷栏：屏幕底部 9 格
    "hotbar": (760, 970, 1160, 1030),
    # 攻击冷却条：快捷栏下方白色进度条
    "cooldown": (820, 1030, 1100, 1040),
    # 准星：屏幕中心
    "crosshair": (940, 520, 980, 560),
    # 手部区域：屏幕右下角（第一人称右手）
    "hand_right": (1300, 600, 1920, 1080),
    # 手部区域：左下角（左手模式）
    "hand_left": (0, 600, 620, 1080),
}

# 默认视频帧率（MC 1.9+ 内部 tick 是 20 TPS）
TARGET_TPS = 20

# 光流参数（Farneback）
OPTICAL_FLOW_PARAMS = {
    "pyr_scale": 0.5,
    "levels": 3,
    "winsize": 15,
    "iterations": 3,
    "poly_n": 5,
    "poly_sigma": 1.2,
    "flags": 0,
}

# 视角变化映射：光流平均位移 → Δyaw/Δpitch（弧度）
# 经验值：1 像素位移 ≈ 0.005 弧度（约 0.3°）
# 不同 FOV / 分辨率需要校准
PIXEL_TO_RADIAN_YAW = 0.005
PIXEL_TO_RADIAN_PITCH = 0.005

# 攻击检测：手部区域光流幅度阈值
# 默认 0.5 适配 480p 光流降采样分辨率（手部 ROI 较小）
# 真实高分辨率视频可调高到 2.0-3.0
ATTACK_FLOW_THRESHOLD = 0.5  # 像素/帧

# 跳跃检测：垂直光流脉冲阈值
JUMP_FLOW_THRESHOLD = 2.0

# 移动检测：整体光流幅值阈值
MOVE_MAG_THRESHOLD = 0.3

# 径向度阈值：超过此值判定为前进/后退
RADIAL_THRESHOLD = 0.3
