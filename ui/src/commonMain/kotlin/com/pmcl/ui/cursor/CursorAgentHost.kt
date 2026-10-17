package com.pmcl.ui.cursor

data class CursorAgentUiState(
    val available: Boolean,
    val enabled: Boolean,
    val running: Boolean,
    val port: Int,
    val tokenTail: String,
    val error: String,
)

/** 桌面端在启动时放上实现。其它目标没有本机服务。 */
interface CursorAgentHost {
    fun state(): CursorAgentUiState
    fun setEnabled(enabled: Boolean)
    fun rotateToken()
    fun writeCursorConfig(): String
    fun mcpSnippet(): String
}

object CursorAgentHosts {
    @Volatile
    var host: CursorAgentHost? = null
}
