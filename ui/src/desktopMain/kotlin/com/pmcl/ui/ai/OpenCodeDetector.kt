package com.pmcl.ui.ai

import java.io.File
import java.nio.file.Paths

/**
 * OpenCode CLI 检测与安装引导。
 *
 * OpenCode 是开源 AI 编码代理（TUI 程序），安装方式：
 * - macOS/Linux: curl -fsSL https://opencode.ai/install | bash
 * - macOS: brew install anomalyco/tap/opencode
 * - 跨平台（需 Node.js）: npm install -g opencode-ai
 * - Windows: scoop install opencode / choco install opencode
 */
object OpenCodeDetector {

    /** 在 PATH 中查找 opencode 可执行文件 */
    fun findOpenCode(): File? {
        val pathEnv = System.getenv("PATH") ?: return null
        val name = if (System.getProperty("os.name").startsWith("Windows")) "opencode.cmd" else "opencode"
        for (dir in pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val candidate = Paths.get(dir).resolve(name).toFile()
            if (candidate.isFile && candidate.canExecute()) return candidate
        }
        return null
    }

    /** 是否已安装 */
    fun isInstalled(): Boolean = findOpenCode() != null

    /** 按平台返回推荐安装命令列表（标题 → 命令） */
    fun installCommands(): List<Pair<String, String>> {
        val osName = System.getProperty("os.name").lowercase()
        val isWindows = osName.contains("win")
        val isMac = osName.contains("mac")
        return when {
            isMac -> listOf(
                "Homebrew (推荐)" to "brew install anomalyco/tap/opencode",
                "安装脚本" to "curl -fsSL https://opencode.ai/install | bash",
                "npm" to "npm install -g opencode-ai"
            )
            isWindows -> listOf(
                "Scoop" to "scoop install opencode",
                "Chocolatey" to "choco install opencode",
                "npm" to "npm install -g opencode-ai"
            )
            else -> listOf(
                "安装脚本 (推荐)" to "curl -fsSL https://opencode.ai/install | bash",
                "npm" to "npm install -g opencode-ai"
            )
        }
    }
}
