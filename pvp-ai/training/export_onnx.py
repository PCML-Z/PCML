"""把训练好的 PvpModel 导出为 ONNX 格式供 mod 内 ONNX Runtime 加载。

输出节点名（与 mod InferenceEngine 对齐）：
  输入: state_seq  [1, 20, 30] float32
  输出: move_logits, jump_logits, sneak_logits, attack_logits,
        use_logits, hotbar_logits, yaw_logits, pitch_logits
        每个形状 [1, head_dim]
"""
from __future__ import annotations

from pathlib import Path

import torch

from dataset import WINDOW_SIZE, STATE_DIM
from model import PvpModel, HEAD_DIMS


def export_model_to_onnx(
    model: PvpModel,
    out_path: Path,
    device: torch.device,
):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    model.eval()
    dummy_input = torch.randn(1, WINDOW_SIZE, STATE_DIM, device=device)

    # ONNX 多输出：动态轴允许 batch/seq 变化（虽然 mod 推理固定 1/20）
    output_names = [f"{name}_logits" for name in HEAD_DIMS.keys()]

    torch.onnx.export(
        model,
        dummy_input,
        str(out_path),
        input_names=["state_seq"],
        output_names=output_names,
        dynamic_axes={
            "state_seq": {0: "batch", 1: "seq"},
            **{n: {0: "batch"} for n in output_names}
        },
        opset_version=15,
        do_constant_folding=True,
    )
    print(f"[onnx] 导出完成: {out_path}")

    # 校验
    try:
        import onnxruntime as ort
        import numpy as np
        sess = ort.InferenceSession(str(out_path), providers=["CPUExecutionProvider"])
        input_name = sess.get_inputs()[0].name
        out_names = [o.name for o in sess.get_outputs()]
        print(f"[onnx] 校验: 输入={input_name}, 输出={out_names}")
        test_input = np.random.randn(1, WINDOW_SIZE, STATE_DIM).astype(np.float32)
        outputs = sess.run(out_names, {input_name: test_input})
        for name, arr in zip(out_names, outputs):
            print(f"  {name}: shape={arr.shape}, dtype={arr.dtype}")
    except Exception as e:
        print(f"[onnx] 校验失败（不影响导出）: {e}")
