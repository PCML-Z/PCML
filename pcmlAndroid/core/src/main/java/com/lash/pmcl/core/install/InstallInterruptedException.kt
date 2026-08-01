package com.lash.pmcl.core.install

/**
 * 安装/下载被用户暂停或取消时抛出。
 *
 * 调用方应将其与真正失败区分：不要清理已有完整安装，也不要把状态标为 FAILED。
 *
 * Android 版本：从 Java 移植，逻辑保持一致。
 */
class InstallInterruptedException : RuntimeException {

    constructor() : super("安装已中断")

    constructor(message: String?) : super(if (message.isNullOrBlank()) "安装已中断" else message)

    constructor(message: String?, cause: Throwable?) :
        super(if (message.isNullOrBlank()) "安装已中断" else message, cause)

    companion object {
        /** 判断异常链是否表示暂停/取消中断。 */
        fun isInterrupted(e: Throwable?): Boolean {
            var cur: Throwable? = e
            while (cur != null) {
                if (cur is InstallInterruptedException) return true
                if (cur is InterruptedException) return true
                if (cur is java.io.InterruptedIOException) return true
                cur = cur.cause
            }
            return false
        }
    }
}
