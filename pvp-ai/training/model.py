"""PvP AI 模型：多头 LSTM 序列分类器。

输入：[B, 20, 30] 状态序列
输出：8 个分类头（move/jump/sneak/attack/use/hotbar/yaw/pitch）

设计要点：
  - 2 层 LSTM，hidden=256，dropout=0.2 防过拟合
  - 取最后一个时间步的 hidden state 作为 sequence embedding
  - 共享 Linear(256, 128) 投影 + 各头独立 Linear(128, head_dim)
  - 各头 CrossEntropy loss 相加（无权重，可后续调）
"""
from __future__ import annotations

import torch
import torch.nn as nn

from dataset import (
    STATE_DIM, WINDOW_SIZE,
    MOVE_DIM, JUMP_DIM, SNEAK_DIM, ATTACK_DIM, USE_DIM,
    HOTBAR_DIM, YAW_BIN, PITCH_BIN,
)

HEAD_DIMS = {
    "move": MOVE_DIM,
    "jump": JUMP_DIM,
    "sneak": SNEAK_DIM,
    "attack": ATTACK_DIM,
    "use": USE_DIM,
    "hotbar": HOTBAR_DIM,
    "yaw": YAW_BIN,
    "pitch": PITCH_BIN,
}


class PvpModel(nn.Module):
    """多头 LSTM 动作预测模型。"""

    def __init__(
        self,
        input_dim: int = STATE_DIM,
        hidden_dim: int = 256,
        num_layers: int = 2,
        dropout: float = 0.2,
        proj_dim: int = 128,
    ):
        super().__init__()
        self.lstm = nn.LSTM(
            input_size=input_dim,
            hidden_size=hidden_dim,
            num_layers=num_layers,
            batch_first=True,
            dropout=dropout if num_layers > 1 else 0.0,
        )
        self.proj = nn.Linear(hidden_dim, proj_dim)
        self.act = nn.ReLU()
        self.dropout = nn.Dropout(dropout)

        # 多头输出
        self.heads = nn.ModuleDict({
            name: nn.Linear(proj_dim, dim) for name, dim in HEAD_DIMS.items()
        })

    def forward(self, x: torch.Tensor) -> dict[str, torch.Tensor]:
        """x: [B, T, F] → returns logits dict, 每个头 [B, head_dim]"""
        out, _ = self.lstm(x)        # [B, T, H]
        last = out[:, -1, :]          # [B, H] 取最后时间步
        z = self.dropout(self.act(self.proj(last)))  # [B, proj]
        return {name: head(z) for name, head in self.heads.items()}

    @torch.no_grad()
    def predict(self, x: torch.Tensor) -> dict[str, int]:
        """单样本推理：x [1, T, F] → 各头 argmax 索引。"""
        self.eval()
        logits = self.forward(x)
        return {name: int(l.argmax(dim=-1).item()) for name, l in logits.items()}


def compute_loss(logits: dict[str, torch.Tensor], labels: dict[str, torch.Tensor]) -> tuple[torch.Tensor, dict[str, float]]:
    """计算多头加权 cross-entropy loss。

    权重可调，目前给视角/移动大头（更难学），攻击/使用小头（更重要但简单）。
    """
    weights = {
        "move": 1.0, "jump": 0.5, "sneak": 0.5,
        "attack": 1.5, "use": 1.0,
        "hotbar": 0.3,  # 切槽位不太频繁，权重小
        "yaw": 1.5, "pitch": 1.5,  # 视角预测最关键
    }
    ce = nn.CrossEntropyLoss()
    total = 0.0
    parts: dict[str, float] = {}
    for name, w in weights.items():
        l = ce(logits[name], labels[name])
        total = total + w * l
        parts[name] = float(l.item())
    return total, parts


def count_params(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters() if p.requires_grad)
