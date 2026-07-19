"""生成合成 FPV PvP 测试视频。

用途：在无真实 PvP 视频时，验证 video_to_dataset.py 管线能否端到端跑通。

模拟一个简化的 MC 第一人称场景：
- 黑色背景
- 中心一个白色准星
- 底部 HUD 区域（红色血量条、棕色饥饿条、白色快捷栏）
- 一个移动的方块（模拟敌人）
- 画面整体周期性平移（模拟视角转动）

输出：MP4 文件，30fps，1920×1080
"""
from __future__ import annotations

import argparse
import math
from pathlib import Path

import cv2
import numpy as np


def draw_hud(frame: np.ndarray, health: float, hunger: float, hotbar: int, cooldown: float):
    h, w = frame.shape[:2]
    # 血量（左下红色 10 颗心）
    for i in range(10):
        x = int(w * (530 + i * 22) / 1920)
        y = int(h * 950 / 1080)
        if i < int(health * 10):
            cv2.rectangle(frame, (x, y), (x + 18, y + 18), (40, 40, 220), -1)
        else:
            cv2.rectangle(frame, (x, y), (x + 18, y + 18), (60, 60, 60), -1)
    # 饥饿（右下棕色 10 个鸡腿）
    for i in range(10):
        x = int(w * (1170 + i * 22) / 1920)
        y = int(h * 950 / 1080)
        if i < int(hunger * 10):
            cv2.rectangle(frame, (x, y), (x + 18, y + 18), (40, 100, 140), -1)
        else:
            cv2.rectangle(frame, (x, y), (x + 18, y + 18), (60, 60, 60), -1)
    # 快捷栏（9 格）
    cell_w = int(w * 44 / 1920)
    x_start = int(w * 760 / 1920)
    y = int(h * 970 / 1080)
    for i in range(9):
        x = x_start + i * cell_w
        color = (255, 255, 255) if i == hotbar else (120, 120, 120)
        thickness = 2 if i == hotbar else 1
        cv2.rectangle(frame, (x, y), (x + cell_w, y + cell_w // 2), color, thickness)
    # 冷却条
    cd_w = int(w * 280 * cooldown / 1920)
    cv2.rectangle(frame, (int(w * 820 / 1920), int(h * 1030 / 1080)),
                  (int(w * 820 / 1920) + cd_w, int(h * 1040 / 1080)),
                  (255, 255, 255), -1)
    # 准星
    cx, cy = w // 2, h // 2
    cv2.line(frame, (cx - 10, cy), (cx + 10, cy), (255, 255, 255), 1)
    cv2.line(frame, (cx, cy - 10), (cx, cy + 10), (255, 255, 255), 1)


def draw_enemy(frame: np.ndarray, ex: float, ey: float, ew: int, eh: int):
    """在归一化坐标 (ex, ey) 处画一个敌人方块。"""
    h, w = frame.shape[:2]
    cx = int(ex * w)
    cy = int(ey * h)
    cv2.rectangle(frame, (cx - ew // 2, cy - eh // 2),
                  (cx + ew // 2, cy + eh // 2), (40, 160, 40), -1)
    # 简单细节：眼睛
    cv2.rectangle(frame, (cx - 5, cy - 5), (cx, cy), (255, 255, 255), -1)


def draw_hand(frame: np.ndarray, attack_phase: float):
    """画右下角手部（持剑），attack_phase=0..1 表示挥剑进度。

    挥剑时手部位置大幅变化（产生明显光流）。
    """
    h, w = frame.shape[:2]
    # 剑：从右下角伸向中心
    base_x = int(w * 0.85)
    base_y = int(h * 0.85)
    # 挥剑时手部大幅上扬 + 旋转（产生 >3 像素光流）
    angle = attack_phase * math.pi * 2
    swing_offset = 80 * math.sin(angle * 2)  # 挥剑时大幅摆动
    end_x = base_x - 200 - int(swing_offset)
    end_y = base_y - 150 - int(abs(swing_offset) * 0.5)
    # 剑身（粗线 + 高对比）
    cv2.line(frame, (base_x, base_y), (end_x, end_y), (180, 180, 200), 12)
    cv2.line(frame, (base_x, base_y), (end_x, end_y), (80, 80, 120), 6)
    # 手部方块（让光流更明显）
    cv2.rectangle(frame, (base_x - 30, base_y - 30),
                  (base_x + 30, base_y + 30), (200, 180, 140), -1)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", default="/tmp/pvp_test.mp4")
    p.add_argument("--seconds", type=int, default=10)
    p.add_argument("--fps", type=int, default=30)
    args = p.parse_args()

    w, h = 1920, 1080
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(args.out, fourcc, args.fps, (w, h))

    total = args.seconds * args.fps
    print(f"[gen] 生成测试视频 {args.out}: {args.seconds}s × {args.fps}fps = {total} 帧")

    for i in range(total):
        t = i / args.fps
        # 创建黑色背景
        frame = np.zeros((h, w, 3), dtype=np.uint8)

        # 模拟视角转动：整体画面偏移
        offset_x = int(20 * math.sin(t * 0.5))
        offset_y = int(5 * math.sin(t * 0.3))

        # 模拟"前进"：背景径向纹理向外扩展
        for r in range(100, 800, 80):
            cv2.circle(frame, (w // 2 + offset_x, h // 2 + offset_y),
                       r + int(20 * math.sin(t * 2 + r * 0.01)),
                       (30, 30, 30), 1)

        # 敌人位置（围绕中心做圆周运动）
        enemy_dist = 5.0 + 2.0 * math.sin(t * 0.7)
        enemy_x = 0.5 + 0.2 * math.cos(t * 1.1) / enemy_dist
        enemy_y = 0.45 + 0.05 * math.sin(t * 1.1)
        enemy_w = int(80 / enemy_dist)
        enemy_h = int(160 / enemy_dist)
        draw_enemy(frame, enemy_x, enemy_y, enemy_w, enemy_h)

        # HUD
        health = 0.5 + 0.3 * math.sin(t * 0.3)
        health = max(0.1, min(1.0, health))
        hunger = 0.8 - 0.05 * t
        hunger = max(0.2, min(1.0, hunger))
        hotbar = 0  # 始终选第 0 格（剑）
        cooldown = (t * 1.5) % 1.0
        draw_hud(frame, health, hunger, hotbar, cooldown)

        # 手部（攻击时挥剑，每个攻击持续 0.5s = 15 帧）
        attack_phase = 0.0
        # 在 2s, 5s, 8s 触发攻击
        for atk_t in [2.0, 5.0, 8.0]:
            if atk_t <= t < atk_t + 0.5:
                attack_phase = (t - atk_t) / 0.5
        draw_hand(frame, attack_phase)

        writer.write(frame)

    writer.release()
    print(f"[done] 视频已生成: {args.out}")
    print(f"       用法: python video_to_dataset.py --video {args.out}")


if __name__ == "__main__":
    main()
