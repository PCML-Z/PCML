"""PvP AI 训练脚本。

用法：
  python train.py --data ~/.pmcl/pvp-ai/datasets --epochs 50 --batch 64 --lr 1e-3

输出：
  ~/.pmcl/pvp-ai/checkpoints/model_best.pt    （最佳验证 loss 的检查点）
  ~/.pmcl/pvp-ai/model.onnx                    （导出供 mod 加载）

流程：
  1. 加载所有 session_*.jsonl，按 20-tick 窗口切片
  2. 90/10 训练/验证划分
  3. Adam 优化器 + ReduceLROnPlateau
  4. 每 epoch 评估验证集，保存最佳模型
  5. 训练完成导出 ONNX
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import DataLoader
from tqdm import tqdm

# 让脚本能在 pvp-ai/training/ 目录下直接运行
sys.path.insert(0, str(Path(__file__).resolve().parent))

from dataset import (
    load_sessions, make_window_samples, PvpDataset, split_train_val,
    WINDOW_SIZE, STATE_DIM,
)
from model import PvpModel, compute_loss, count_params
from export_onnx import export_model_to_onnx


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--data", default=str(Path.home() / ".pmcl/pvp-ai/datasets"),
                   help="数据集目录")
    p.add_argument("--out", default=str(Path.home() / ".pmcl/pvp-ai"),
                   help="输出目录（检查点 + onnx）")
    p.add_argument("--epochs", type=int, default=50)
    p.add_argument("--batch", type=int, default=64)
    p.add_argument("--lr", type=float, default=1e-3)
    p.add_argument("--hidden", type=int, default=256)
    p.add_argument("--layers", type=int, default=2)
    p.add_argument("--dropout", type=float, default=0.2)
    p.add_argument("--device", default="auto", help="auto/cpu/cuda/mps")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--no-onnx", action="store_true", help="跳过 ONNX 导出")
    return p.parse_args()


def pick_device(spec: str) -> torch.device:
    if spec == "cpu":
        return torch.device("cpu")
    if spec == "cuda" and torch.cuda.is_available():
        return torch.device("cuda")
    if spec == "mps" and torch.backends.mps.is_available():
        return torch.device("mps")
    if spec == "auto":
        if torch.cuda.is_available():
            return torch.device("cuda")
        if torch.backends.mps.is_available():
            return torch.device("mps")
        return torch.device("cpu")
    print(f"[warn] 设备 {spec} 不可用，回退 CPU")
    return torch.device("cpu")


def evaluate(model, loader, device, criterion=compute_loss):
    model.eval()
    total_loss = 0.0
    n = 0
    head_correct: dict[str, int] = {}
    head_total: dict[str, int] = {}
    with torch.no_grad():
        for states, labels in loader:
            states = states.to(device)
            labels = {k: v.to(device) for k, v in labels.items()}
            logits = model(states)
            loss, _ = criterion(logits, labels)
            total_loss += loss.item() * len(states)
            n += len(states)
            for name, l in logits.items():
                pred = l.argmax(dim=-1)
                head_correct[name] = head_correct.get(name, 0) + (pred == labels[name]).sum().item()
                head_total[name] = head_total.get(name, 0) + len(states)
    avg_loss = total_loss / max(n, 1)
    accs = {k: head_correct[k] / max(head_total[k], 1) for k in head_total}
    return avg_loss, accs


def main():
    args = parse_args()
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)

    device = pick_device(args.device)
    print(f"[info] 使用设备: {device}")

    # === 1. 加载数据 ===
    print(f"[info] 加载数据集: {args.data}")
    sessions = load_sessions(args.data)
    if not sessions:
        print(f"[error] 未找到数据集。请先用 mod 录制 PvP 数据（F8 开关）")
        print(f"        期望路径：{args.data}/session_*.jsonl")
        sys.exit(1)
    print(f"[info] 加载了 {len(sessions)} 个 session")

    states, labels = make_window_samples(sessions)
    print(f"[info] 切片后样本数: {len(states)} (window={WINDOW_SIZE}, dim={STATE_DIM})")

    (tr_s, tr_l), (va_s, va_l) = split_train_val(states, labels, val_ratio=0.1, seed=args.seed)
    print(f"[info] 训练集 {len(tr_s)} / 验证集 {len(va_s)}")

    train_ds = PvpDataset(tr_s, tr_l)
    val_ds = PvpDataset(va_s, va_l)
    train_loader = DataLoader(train_ds, batch_size=args.batch, shuffle=True, num_workers=0)
    val_loader = DataLoader(val_ds, batch_size=args.batch, shuffle=False, num_workers=0)

    # === 2. 模型 ===
    model = PvpModel(
        hidden_dim=args.hidden, num_layers=args.layers, dropout=args.dropout
    ).to(device)
    print(f"[info] 模型参数量: {count_params(model):,}")

    optim = torch.optim.Adam(model.parameters(), lr=args.lr, weight_decay=1e-5)
    scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(
        optim, mode="min", factor=0.5, patience=3, min_lr=1e-6
    )

    # === 3. 训练循环 ===
    out_dir = Path(args.out)
    ckpt_dir = out_dir / "checkpoints"
    ckpt_dir.mkdir(parents=True, exist_ok=True)
    best_ckpt = ckpt_dir / "model_best.pt"
    best_val_loss = float("inf")

    print(f"[info] 训练 {args.epochs} epochs, batch={args.batch}, lr={args.lr}")
    for epoch in range(1, args.epochs + 1):
        model.train()
        ep_loss = 0.0
        ep_n = 0
        pbar = tqdm(train_loader, desc=f"epoch {epoch:3d}/{args.epochs}", leave=False)
        for states_b, labels_b in pbar:
            states_b = states_b.to(device)
            labels_b = {k: v.to(device) for k, v in labels_b.items()}
            logits = model(states_b)
            loss, _ = compute_loss(logits, labels_b)
            optim.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            optim.step()
            ep_loss += loss.item() * len(states_b)
            ep_n += len(states_b)
            pbar.set_postfix(loss=f"{loss.item():.4f}")

        train_loss = ep_loss / max(ep_n, 1)
        val_loss, val_accs = evaluate(model, val_loader, device)
        scheduler.step(val_loss)

        acc_str = " ".join(f"{k}={v:.3f}" for k, v in val_accs.items())
        print(f"[epoch {epoch:3d}] train_loss={train_loss:.4f} val_loss={val_loss:.4f} | accs: {acc_str} | lr={optim.param_groups[0]['lr']:.2e}")

        if val_loss < best_val_loss:
            best_val_loss = val_loss
            torch.save({
                "epoch": epoch,
                "model_state": model.state_dict(),
                "val_loss": val_loss,
                "args": vars(args),
            }, best_ckpt)
            print(f"  -> 保存最佳模型到 {best_ckpt}")

    print(f"[done] 训练结束，最佳 val_loss={best_val_loss:.4f}")

    # === 4. 导出 ONNX ===
    if not args.no_onnx:
        print("[info] 加载最佳检查点并导出 ONNX...")
        ckpt = torch.load(best_ckpt, map_location=device, weights_only=False)
        model.load_state_dict(ckpt["model_state"])
        model.eval()
        onnx_path = out_dir / "model.onnx"
        export_model_to_onnx(model, onnx_path, device)
        print(f"[done] ONNX 模型: {onnx_path}")
        print(f"       把它放到 mod 启动时读取的位置（默认 {Path.home() / '.pmcl/pvp-ai/model.onnx'}）")


if __name__ == "__main__":
    main()
