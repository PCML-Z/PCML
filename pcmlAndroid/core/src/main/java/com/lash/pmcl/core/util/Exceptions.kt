package com.lash.pmcl.core.util

/**
 * 异常信息提取工具，供安装器 / 下载队列统一使用。
 *
 * Android 版本：从 Java 移植，逻辑保持一致。
 */
object Exceptions {

    /**
     * 展开包装异常，取出最内层有意义的错误信息。
     * 跳过无信息量的外层「Xxx 安装失败」包装（若有更具体的 cause）。
     */
    fun rootMessage(e: Throwable?): String {
        if (e == null) return "未知错误"
        var cur: Throwable? = e
        var last: String? = e.message
        while (cur != null) {
            val msg = cur.message
            if (!msg.isNullOrBlank()) {
                if (!isVagueInstallFailure(msg) || cur.cause == null) {
                    last = msg
                }
            }
            cur = cur.cause
        }
        if (last.isNullOrBlank()) {
            return e.toString()
        }
        if (isVagueInstallFailure(last)) {
            var deepest: Throwable = e
            while (deepest.cause != null) deepest = deepest.cause!!
            val deep = deepest.message
            if (!deep.isNullOrBlank() && deep != last) {
                return "$last: $deep"
            }
        }
        return last
    }

    private fun isVagueInstallFailure(msg: String): Boolean {
        return "Forge 安装失败" == msg
            || "NeoForge 安装失败" == msg
            || msg.endsWith(" 安装失败")
            || msg.startsWith("java.lang.RuntimeException")
    }
}
