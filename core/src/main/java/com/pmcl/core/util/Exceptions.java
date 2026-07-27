package com.pmcl.core.util;

/**
 * 异常信息提取工具，供安装器 / 下载队列 / 启动路径统一使用。
 */
public final class Exceptions {

    private Exceptions() {}

    /**
     * 展开包装异常，取出最内层有意义的错误信息。
     * 跳过无信息量的外层「Xxx 安装失败」包装（若有更具体的 cause）。
     */
    public static String rootMessage(Throwable e) {
        if (e == null) return "未知错误";
        Throwable cur = e;
        String last = e.getMessage();
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && !msg.isBlank()) {
                if (!(isVagueInstallFailure(msg)) || cur.getCause() == null) {
                    last = msg;
                }
            }
            cur = cur.getCause();
        }
        if (last == null || last.isBlank()) {
            return e.toString();
        }
        if (isVagueInstallFailure(last)) {
            Throwable deepest = e;
            while (deepest.getCause() != null) deepest = deepest.getCause();
            String deep = deepest.getMessage();
            if (deep != null && !deep.isBlank() && !deep.equals(last)) {
                return last + ": " + deep;
            }
        }
        return last;
    }

    private static boolean isVagueInstallFailure(String msg) {
        return "Forge 安装失败".equals(msg)
                || "NeoForge 安装失败".equals(msg)
                || msg.endsWith(" 安装失败")
                || msg.startsWith("java.lang.RuntimeException");
    }
}
