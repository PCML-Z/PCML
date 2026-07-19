"""PvP AI 数据集加载器。

数据格式（由 Minecraft mod 写入 JSONL）：
  每行：{"tick": 12345, "rel_tick": 0, "state": [...30 floats...],
         "action": [moveDir, jump, sneak, attack, use, hotbar, yawBin, pitchBin]}

加载流程：
  1. 读取所有 session_*.jsonl 文件
  2. 按 session 分组，每 session 内按 rel_tick 排序成时间序列
  3. 滑动窗口切片：窗口长度 20，步长 1
  4. 每个样本 = (state_seq[20, 30], action_seq[20, 8])
     训练只预测最后一个 tick 的动作（教师强制），所以 action 标签 = seq[-1]

输出：
  - state:   [B, 20, 30] float32
  - labels:  dict, 每个动作头的索引 [B] int64
"""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Iterable

import numpy as np

try:
    import torch
    from torch.utils.data import Dataset as _TorchDataset
    _HAS_TORCH = True
except ImportError:
    _HAS_TORCH = False
    _TorchDataset = object  # 降级基类，让 PvpDataset 仍可被实例化（无 torch 时仅 numpy 部分可用）

STATE_DIM = 30
WINDOW_SIZE = 20

# 动作头维度
MOVE_DIM = 9
JUMP_DIM = 2
SNEAK_DIM = 2
ATTACK_DIM = 2
USE_DIM = 2
HOTBAR_DIM = 9
YAW_BIN = 11
PITCH_BIN = 11

# 各动作在 JSONL action 数组中的索引
A_MOVE, A_JUMP, A_SNEAK, A_ATTACK, A_USE, A_HOTBAR, A_YAW, A_PITCH = range(8)


def load_sessions(data_dir: str | Path) -> list[list[dict]]:
    """加载所有 session 文件，返回 session 列表，每 session 是 tick 样本列表。"""
    data_dir = Path(data_dir)
    if not data_dir.exists():
        return []
    sessions: list[list[dict]] = []
    for f in sorted(data_dir.glob("session_*.jsonl")):
        session: list[dict] = []
        with f.open(encoding="utf-8") as fp:
            for line in fp:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                    session.append(obj)
                except json.JSONDecodeError:
                    continue
        if len(session) >= WINDOW_SIZE:
            sessions.append(session)
    return sessions


def make_window_samples(sessions: Iterable[list[dict]]) -> tuple[np.ndarray, dict[str, np.ndarray]]:
    """把 session 切成滑动窗口样本。

    返回：
      states: [N, 20, 30] float32
      labels: {
        'move':   [N] int64, 'jump': [N] int64, 'sneak': [N] int64,
        'attack': [N] int64, 'use': [N] int64, 'hotbar': [N] int64,
        'yaw':    [N] int64, 'pitch': [N] int64
      }
    """
    states_list: list[np.ndarray] = []
    labels = {k: [] for k in ("move", "jump", "sneak", "attack", "use", "hotbar", "yaw", "pitch")}

    for session in sessions:
        # 提取 state 和 action 数组
        states = np.array([s["state"] for s in session], dtype=np.float32)
        actions = np.array([s["action"] for s in session], dtype=np.int64)
        if len(states) < WINDOW_SIZE:
            continue

        # 滑动窗口：每窗口从 i 到 i+WINDOW_SIZE-1，标签取窗口最后一个 tick 的动作
        for i in range(len(states) - WINDOW_SIZE + 1):
            window = states[i:i + WINDOW_SIZE]  # [20, 30]
            last_action = actions[i + WINDOW_SIZE - 1]
            states_list.append(window)
            labels["move"].append(last_action[A_MOVE])
            labels["jump"].append(last_action[A_JUMP])
            labels["sneak"].append(last_action[A_SNEAK])
            labels["attack"].append(last_action[A_ATTACK])
            labels["use"].append(last_action[A_USE])
            labels["hotbar"].append(last_action[A_HOTBAR])
            labels["yaw"].append(last_action[A_YAW])
            labels["pitch"].append(last_action[A_PITCH])

    if not states_list:
        raise RuntimeError(
            f"未生成任何样本。请检查数据集（每个 session 至少 {WINDOW_SIZE} 个 tick）"
        )

    states_arr = np.stack(states_list).astype(np.float32)
    labels_arr = {k: np.array(v, dtype=np.int64) for k, v in labels.items()}
    return states_arr, labels_arr


class PvpDataset(_TorchDataset):
    """torch Dataset：包装 (states, labels) 供 DataLoader 使用。

    需要 torch 才能真正用于训练；但 numpy 数组本身可独立使用。
    """

    def __init__(self, states: np.ndarray, labels: dict[str, np.ndarray]):
        assert states.ndim == 3 and states.shape[1:] == (WINDOW_SIZE, STATE_DIM)
        self.states = states
        self.labels = labels

    def __len__(self) -> int:
        return len(self.states)

    def __getitem__(self, idx: int):
        if not _HAS_TORCH:
            return self.states[idx], {k: v[idx] for k, v in self.labels.items()}
        state = torch.from_numpy(self.states[idx])
        label = {k: torch.tensor(v[idx], dtype=torch.long) for k, v in self.labels.items()}
        return state, label


def split_train_val(states, labels, val_ratio=0.1, seed=42):
    """按 session-aware 的方式划分训练/验证集。

    简化实现：随机打乱样本索引后切分。若需要严格 session 隔离，
    应在 make_window_samples 前按 session 划分。
    """
    rng = np.random.default_rng(seed)
    n = len(states)
    idx = rng.permutation(n)
    n_val = max(1, int(n * val_ratio))
    val_idx = idx[:n_val]
    train_idx = idx[n_val:]
    train_states = states[train_idx]
    val_states = states[val_idx]
    train_labels = {k: v[train_idx] for k, v in labels.items()}
    val_labels = {k: v[val_idx] for k, v in labels.items()}
    return (train_states, train_labels), (val_states, val_labels)
