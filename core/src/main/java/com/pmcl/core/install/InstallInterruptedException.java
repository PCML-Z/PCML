package com.pmcl.core.install;

/**
 * 安装/下载被用户暂停或取消时抛出。
 * <p>
 * 调用方应将其与真正失败区分：不要清理已有完整安装，也不要把状态标为 FAILED。
 */
public final class InstallInterruptedException extends RuntimeException {

    public InstallInterruptedException() {
        super("安装已中断");
    }

    public InstallInterruptedException(String message) {
        super(message == null || message.isBlank() ? "安装已中断" : message);
    }

    public InstallInterruptedException(String message, Throwable cause) {
        super(message == null || message.isBlank() ? "安装已中断" : message, cause);
    }

    /** 判断异常链是否表示暂停/取消中断。 */
    public static boolean isInterrupted(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof InstallInterruptedException) return true;
            if (cur instanceof InterruptedException) return true;
            if (cur instanceof java.io.InterruptedIOException) return true;
            cur = cur.getCause();
        }
        return false;
    }
}
