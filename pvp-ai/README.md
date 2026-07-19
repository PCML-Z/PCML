# PMCL PvP AI

基于模仿学习（Imitation Learning）的 Minecraft 1.9+ 冷却 PvP 助手。

**工作流程**：录制人类 PvP 实战 → 训练 LSTM 多头分类器 → Mod 内 ONNX Runtime 推理接管操作。

## 项目结构

```
pvp-ai/
├── mod/           # Fabric mod（Kotlin），运行在 MC 客户端内
│   └── src/main/kotlin/com/pmcl/pvpai/
│       ├── PvpAiMod.kt          # 入口：热键注册 + Tick 钩子 + HUD
│       ├── data/
│       │   ├── GameState.kt      # 30 维状态向量定义
│       │   └── PlayerAction.kt   # 8 字段动作定义 + 量化/反量化
│       ├── collector/
│       │   ├── StateCollector.kt # 从 MC 客户端读 30 维特征
│       │   ├── ActionRecorder.kt # 录制玩家输入
│       │   └── DatasetWriter.kt  # 写 JSONL 数据集
│       └── inference/
│           └── InferenceEngine.kt # ONNX 加载 + 异步推理 + 动作应用
├── training/      # Python 训练管线
│   ├── dataset.py               # JSONL → 滑窗样本
│   ├── model.py                 # LSTM(256)×2 多头分类器
│   ├── train.py                 # 训练主脚本
│   ├── export_onnx.py           # ONNX 导出
│   ├── generate_synthetic.py    # 合成数据生成（验证管线）
│   └── requirements.txt
└── README.md
```

## 1. 构建 Mod

需要 JDK 17。

```bash
cd pvp-ai/mod
./gradlew build
# 产物：build/libs/pvp-ai-0.1.0.jar
```

把 jar 放到 MC 实例的 `mods/` 目录（PMCL 启动器 → 拖放安装 mod 功能也支持）。

## 2. 录制 PvP 数据

启动游戏后进入 PvP 场景，按 **F8** 开始录制，HUD 左上角会显示红色 `[PvpAI] 录制中` 字样。
战斗结束再按 F8 停止。数据写入：

```
~/.pmcl/pvp-ai/datasets/session_<timestamp>_<seq>.jsonl
```

每行格式：
```json
{"tick": 12345, "rel_tick": 0, "state": [...30 floats...],
 "action": [moveDir, jump, sneak, attack, use, hotbar, yawBin, pitchBin]}
```

**录制建议**：
- 单个 session 至少 200 tick（10 秒）才有滑窗价值
- 涵盖不同距离、不同敌人、不同武器
- 至少 5-10 个 session、总量 5000+ 样本能训出可用的 baseline

## 3. 训练模型

需要 Python 3.10+ 和 PyTorch 2.2+：

```bash
cd pvp-ai/training
python -m venv .venv
source .venv/bin/activate  # Windows: .venv\Scripts\activate
pip install -r requirements.txt

# 训练
python train.py --epochs 50 --batch 64 --lr 1e-3

# 验证管线（无真实数据时用合成数据）
python generate_synthetic.py --sessions 5 --ticks 600
python train.py --data ~/.pmcl/pvp-ai/datasets --epochs 5
```

产物：
- `~/.pmcl/pvp-ai/checkpoints/model_best.pt` — PyTorch 检查点
- `~/.pmcl/pvp-ai/model.onnx` — 导出的 ONNX 模型（Mod 加载这个）

**设备**：默认 auto，CUDA / Apple MPS / CPU 自动选。Apple Silicon Mac 走 MPS。

## 3.5. 从 FPV 视频导入训练数据（可选）

若已有 PvP 视频（第一人称视角），可跳过游戏内录制直接生成训练数据：

```bash
cd pvp-ai/training

# 基础用法（运动检测，无需 YOLO）
python video_to_dataset.py --video path/to/pvp.mp4

# 启用 YOLOv8 敌人检测（更准确，需先 pip install ultralytics）
python video_to_dataset.py --video path/to/pvp.mp4 --yolo

# 指定输出目录和文件名前缀
python video_to_dataset.py --video pvp.mp4 --out ~/.pmcl/pvp-ai/datasets --name my_session
```

输出 `~/.pmcl/pvp-ai/datasets/session_video_<name>.jsonl`，格式与 mod 录制完全一致，可直接喂给 `train.py`。

**视频导入管线**：
- OpenCV 解码视频帧，自动按比例重采样到 20 TPS（MC 内部 tick 率）
- `HudExtractor`：HSV 颜色 mask 提取血量/饥饿/快捷栏槽位/冷却条/举盾
- `PerspectiveAnalyzer`：Farneback 稠密光流推断 Δyaw/Δpitch
- `ActionClassifier`：径向度判前/后/平移，手部光流判攻击/举盾
- `EnemyDetector`：默认帧间差分 + 连通域；`--yolo` 时用 YOLOv8 person 检测

**视频导入的伪标签准确度**：
- HUD 提取（血量/饥饿/冷却条）：~90%（受压缩伪影影响）
- 视角变化（Δyaw/Δpitch）：~80%（依赖光流质量，与 FOV/分辨率相关）
- 动作识别（攻击/移动）：~60-70%（合成视频检测率约 80%）
- 敌人位置：~70%（运动检测） / ~90%（YOLOv8）

**校准建议**：
- 真实视频的 `PIXEL_TO_RADIAN` 与 `ATTACK_FLOW_THRESHOLD` 可能需要调整
- 编辑 [video_config.py](pvp-ai/training/video_config.py) 中的常数后重新跑
- 可混合视频数据 + 真实录制数据（在 `~/.pmcl/pvp-ai/datasets/` 目录共存）

**测试视频生成**（验证管线）：
```bash
python generate_test_video.py --out /tmp/pvp_test.mp4 --seconds 10
python video_to_dataset.py --video /tmp/pvp_test.mp4 --out /tmp/pvp-video-test
python train.py --data /tmp/pvp-video-test --epochs 5
```

## 4. 启用 AI

把 `model.onnx` 放到 `~/.pmcl/pvp-ai/model.onnx`，启动游戏后按 **F9** 启用。
HUD 左上角显示绿色 `[PvpAI] AI 接管`。

按 **F10** 重新加载模型（替换 onnx 后无需重启游戏）。

## 状态空间（30 维）

| 区段 | 字段 | 归一化 |
|---|---|---|
| 自身 (0-13) | dx/dy/dz(相对敌人), vx/vy/vz, yaw, pitch, health, hunger, cooldown, blocking, onGround, heldSlot | 距离 /10, 速度 /0.5, 角度 /π/2 |
| 敌人 (14-23) | dx/dy/dz, vx/vy/vz, yaw, health, blocking, holdingWeapon | 同上 |
| 关系 (24-29) | horizDist, vertDist, hasLOS, inViewCone(60°), selfFacingEnemy(30°), enemyFacingSelf | 0/1 或 /10 |

## 动作空间（8 字段 → 35 维 logits）

| 头 | 维度 | 含义 |
|---|---|---|
| move | 9 | WASD 8 方向 + 静止 |
| jump | 2 | 0/1 |
| sneak | 2 | 0/1 |
| attack | 2 | 本 tick 是否攻击 |
| use | 2 | 本 tick 是否使用/举盾 |
| hotbar | 9 | 快捷栏槽位 |
| yaw | 11 | Δyaw 量化到 [-π/4, π/4] |
| pitch | 11 | Δpitch 量化到 [-π/8, π/8] |

## 模型架构

```
LSTM(input=30, hidden=256, layers=2, dropout=0.2)
  → 取最后时间步 hidden
  → Linear(256, 128) + ReLU + Dropout
  → 8 个并行 Linear(128, head_dim) 输出头
```

Loss：8 个 CrossEntropy 加权和（视角/攻击权重 1.5，hotbar 权重 0.3）。

## 当前限制 & 改进方向

- **多敌人**：当前只取最近 1 个敌人，多v多场景需扩展为 set encoding
- **视线检测**：简化为距离判定，应该用 block raycast
- **攻击触发**：现用 `isPressed` 持续态记录，应该改成 `wasPressed` 单次事件以更准确建模点击节奏
- **资源感知**：未编码药水/食物/耐久等上下文
- **热加载**：模型路径固定 `~/.pmcl/pvp-ai/model.onnx`，未在 PMCL 启动器 UI 集成选择器
- **反检测**：未做人类化抖动/延迟，纯 argmax 决策可能被反作弊识别为机械操作

## 伦理声明

本工具用于学习 ML 与游戏 AI 研究。在反作弊服务器使用可能违反服务条款，账号封禁风险由使用者承担。建议在单机/私服/训练场景使用。

## 许可

MIT
