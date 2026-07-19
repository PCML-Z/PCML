package com.pmcl.pvpai

import com.pmcl.pvpai.collector.ActionRecorder
import com.pmcl.pvpai.collector.DatasetWriter
import com.pmcl.pvpai.collector.StateCollector
import com.pmcl.pvpai.inference.InferenceEngine
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

/**
 * PMCL PvP AI 主入口。
 *
 * 三个核心功能（均通过热键切换）：
 * 1. F8: 开始/停止录制 — 把玩家操作序列化为 JSONL 数据集
 * 2. F9: 启用/禁用 AI — 加载 ONNX 模型并接管玩家输入
 * 3. F10: 重新加载模型（修改模型文件后热加载）
 *
 * 录制和 AI 不会同时启用：开启 AI 自动停止录制，反之亦然。
 */
class PvpAiMod : ClientModInitializer {

    override fun onInitializeClient() {
        logger.info("[PvpAi] 初始化 PMCL PvP AI")

        // === 热键注册 ===
        val recordKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.pvp-ai.record", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.pvp-ai")
        )
        val aiKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.pvp-ai.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "category.pvp-ai")
        )
        val reloadKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.pvp-ai.reload", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F10, "category.pvp-ai")
        )

        // === Tick 钩子：每 tick 检查热键 + 采集数据 + 推理 ===
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            tickCounter++
            handleHotkeys(client, recordKey, aiKey, reloadKey)

            if (recording && client.player != null && client.world != null) {
                val state = StateCollector.capture(client, tickCounter)
                val action = ActionRecorder.capture(client, tickCounter)
                if (state != null && action != null) {
                    datasetWriter.write(state, action)
                }
            }

            if (aiEnabled && client.player != null) {
                inferenceEngine.tick(client, tickCounter)
            }
        })

        // === HUD 提示：录制中/AI 接管中显示状态文字 ===
        HudRenderCallback.EVENT.register(HudRenderCallback { matrixStack, _ ->
            val client = MinecraftClient.getInstance()
            val y = 12
            var line = 0
            if (recording) {
                client.textRenderer.draw(
                    matrixStack, Text.literal("[PvpAI] 录制中 (F8 停止)"),
                    12f, (y + line * 12).toFloat(), 0xFF5555.toInt()
                )
                line++
            }
            if (aiEnabled) {
                client.textRenderer.draw(
                    matrixStack, Text.literal("[PvpAI] AI 接管 (F9 关闭)"),
                    12f, (y + line * 12).toFloat(), 0x55FF55.toInt()
                )
            }
        })

        logger.info("[PvpAi] 热键: F8=录制 F9=AI开关 F10=重载模型")
    }

    private fun handleHotkeys(
        client: MinecraftClient,
        recordKey: KeyBinding,
        aiKey: KeyBinding,
        reloadKey: KeyBinding
    ) {
        // wasPressed 消费一次按键事件
        while (recordKey.wasPressed()) {
            toggleRecording(client)
        }
        while (aiKey.wasPressed()) {
            toggleAi(client)
        }
        while (reloadKey.wasPressed()) {
            inferenceEngine.reload()
            client.player?.sendMessage(Text.literal("[PvpAI] 模型已重新加载"), false)
        }
    }

    private fun toggleRecording(client: MinecraftClient) {
        if (recording) {
            val n = datasetWriter.endSession()
            recording = false
            client.player?.sendMessage(
                Text.literal("[PvpAI] 录制结束，保存 $n 个样本到 ${datasetWriter.currentPath()}"),
                false
            )
        } else {
            // 开启录制时关闭 AI（互斥）
            if (aiEnabled) {
                inferenceEngine.disable()
                aiEnabled = false
            }
            datasetWriter.startSession(tickCounter)
            ActionRecorder.reset()
            recording = true
            client.player?.sendMessage(Text.literal("[PvpAI] 开始录制 PvP 数据"), false)
        }
    }

    private fun toggleAi(client: MinecraftClient) {
        if (aiEnabled) {
            inferenceEngine.disable()
            aiEnabled = false
            client.player?.sendMessage(Text.literal("[PvpAI] AI 已关闭"), false)
        } else {
            // 开启 AI 时关闭录制（互斥）
            if (recording) {
                val n = datasetWriter.endSession()
                recording = false
                client.player?.sendMessage(
                    Text.literal("[PvpAI] 录制被中断（保存 $n 个样本）"), false
                )
            }
            val ok = inferenceEngine.enable()
            if (ok) {
                aiEnabled = true
                client.player?.sendMessage(Text.literal("[PvpAI] AI 已开启"), false)
            } else {
                client.player?.sendMessage(
                    Text.literal("[PvpAI] AI 启用失败：未找到模型文件 ~/.pmcl/pvp-ai/model.onnx"),
                    false
                )
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("PvpAi")
        private var tickCounter: Long = 0
        private var recording: Boolean = false
        private var aiEnabled: Boolean = false
        private val datasetWriter = DatasetWriter()
        private val inferenceEngine = InferenceEngine()
    }
}
