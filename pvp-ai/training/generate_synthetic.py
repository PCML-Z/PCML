"""合成 PvP 数据生成器。

用途：在还没有真实 PvP 录制数据时，生成假数据验证训练管线能否端到端跑通。
生成的数据格式与 mod 录制的 JSONL 完全一致。

用法：
  python generate_synthetic.py --out ~/.pmcl/pvp-ai/datasets --sessions 5 --ticks 600
"""
from __future__ import annotations

import argparse
import json
import math
import random
from pathlib import Path

STATE_DIM = 30


def gen_tick(tick: int, prev_state: list[float] | None) -> tuple[list[float], list[int]]:
    """生成一个 tick 的 (state, action)。

    用简单的正弦波 + 噪声模拟玩家围绕敌人走位、攻击的节奏。
    """
    t = tick / 20.0  # 秒
    # 玩家围绕原点做圆周走位，半径周期性变化（让距离偶尔拉近到攻击范围）
    radius = 3.5 + 1.5 * math.sin(t * 0.7)  # 2.0 ~ 5.0 之间变化
    px = radius * math.cos(t * 1.1)
    pz = radius * math.sin(t * 1.1)
    py = 0.0
    vx = -radius * 1.1 * math.sin(t * 1.1)
    vz = radius * 1.1 * math.cos(t * 1.1)

    # 敌人在原点附近晃动
    ex = 0.3 * math.cos(t * 0.5)
    ez = 0.3 * math.sin(t * 0.5)

    yaw = math.atan2(-(pz - ez), -(px - ex))  # 面朝敌人
    pitch = 0.0

    # 当距离接近时触发攻击（每周期接近 3 次）
    dist = math.sqrt((px - ex) ** 2 + (pz - ez) ** 2)
    attack = 1 if dist < 3.2 and (tick % 20 < 10) else 0
    move_dir = 3 if abs(vx) + abs(vz) > 0.5 else 0  # 简化：前/静止
    jump = 1 if (tick % 80 < 5) else 0
    sneak = 0
    use = 1 if (tick % 60 < 3) else 0  # 偶尔举盾
    hotbar = 0  # 主手剑
    yaw_bin = int((yaw + math.pi) / (2 * math.pi) * 11) % 11
    pitch_bin = 5

    state = [
        # 自身（14）
        (px - ex) / 10, (py) / 10, (pz - ez) / 10,
        vx / 0.5, 0.0, vz / 0.5,
        yaw / (math.pi / 2), pitch / (math.pi / 2),
        0.8, 0.9,  # health/hunger
        0.5 + 0.5 * math.sin(t * 1.5),  # cooldown
        0.0, 1.0, 0.0,
        # 敌人（10）
        (ex - px) / 10, 0.0, (ez - pz) / 10,
        0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 1.0,
        # 关系（6）
        dist / 10, 0.0, 1.0, 1.0, 1.0, 1.0,
    ]
    assert len(state) == STATE_DIM
    action = [move_dir, jump, sneak, attack, use, hotbar, yaw_bin, pitch_bin]
    return state, action


def write_session(out_dir: Path, idx: int, n_ticks: int):
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"session_synthetic_{idx:03d}.jsonl"
    with path.open("w", encoding="utf-8") as f:
        prev = None
        for t in range(n_ticks):
            state, action = gen_tick(t, prev)
            obj = {
                "tick": t,
                "rel_tick": t,
                "state": state,
                "action": action,
            }
            f.write(json.dumps(obj) + "\n")
            prev = state
    print(f"  生成 {path.name}: {n_ticks} ticks")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", default=str(Path.home() / ".pmcl/pvp-ai/datasets"))
    p.add_argument("--sessions", type=int, default=5)
    p.add_argument("--ticks", type=int, default=600)
    args = p.parse_args()

    out = Path(args.out)
    print(f"[gen] 输出目录: {out}")
    print(f"[gen] 生成 {args.sessions} 个 session × {args.ticks} ticks")
    for i in range(args.sessions):
        # 加随机偏移让每个 session 略不同
        random.seed(42 + i)
        write_session(out, i, args.ticks + random.randint(-50, 50))
    print(f"[done] 共 {args.sessions} 个 session 已生成，可直接跑 train.py")


if __name__ == "__main__":
    main()
