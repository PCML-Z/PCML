package com.pmcl.core.download;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * curl 子进程 fallback：当 Java SSL 握手失败时（GFW 对 Java TLS 指纹干扰），
 * 自动 fallback 到系统 curl 命令执行请求。
 * <p>
 * 适用场景：中国大陆网络环境下，Java 的 TLS ClientHello 被 GFW 识别并 RST，
 * 导致 javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake。
 * curl 使用不同的 TLS 实现（LibreSSL/GnuTLS），指纹不被识别，能正常连接。
 * <p>
 * macOS/Linux/Windows 均自带 curl 命令。
 */
public final class CurlFallback {

    /** curl 超时秒数 */
    private static final int TIMEOUT_SEC = 30;
    /** curl 最大缓冲（字符串响应） */
    private static final int MAX_STRING_SIZE = 16 * 1024 * 1024;

    private CurlFallback() {}

    /**
     * 检测系统是否安装 curl。
     */
    public static boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("curl", "--version").start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断异常是否为 SSL 握手失败（应该 fallback 到 curl）。
     */
    public static boolean isSslHandshakeFailure(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) msg = "";
        // SSL 握手失败的常见错误消息
        return msg.contains("handshake") || msg.contains("SSL") || msg.contains("TLS")
                || msg.contains("Remote host terminated")
                || msg.contains("reset")
                || msg.contains("broken pipe")
                || e instanceof javax.net.ssl.SSLHandshakeException
                || e instanceof javax.net.ssl.SSLException;
    }

    /**
     * 用 curl 下载文本内容。
     *
     * @param url 请求 URL
     * @return 响应体字符串
     */
    public static String getString(String url) throws IOException {
        byte[] bytes = getBytes(url, null, null);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 用 curl 下载字节数据。
     *
     * @param url    请求 URL
     * @param method HTTP 方法（"GET" 或 "HEAD"），默认 GET
     * @param headers 额外请求头，可为 null
     * @return 响应体字节数组
     */
    public static byte[] getBytes(String url, String method, List<String> headers) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-sS");                    // 静默 + 显示错误
        cmd.add("--max-time"); cmd.add(String.valueOf(TIMEOUT_SEC));
        cmd.add("--connect-timeout"); cmd.add("10");
        cmd.add("-L");                     // 跟随重定向
        cmd.add("-X"); cmd.add(method == null ? "GET" : method);
        cmd.add("-H"); cmd.add("User-Agent: PMCL/1.0");
        cmd.add("-H"); cmd.add("Accept: */*");
        if (headers != null) {
            for (String h : headers) {
                cmd.add("-H"); cmd.add(h);
            }
        }
        cmd.add(url);

        Process p = new ProcessBuilder(cmd).start();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            // 读取 stderr 用于错误诊断
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            try (InputStream in = p.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) err.write(buf, 0, n);
            }
            boolean done = p.waitFor(TIMEOUT_SEC + 5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IOException("curl 超时: " + url);
            }
            int exit = p.exitValue();
            if (exit != 0) {
                String errMsg = err.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
                throw new IOException("curl 失败 exit=" + exit + ": " + errMsg + " url=" + url);
            }
            return out.toByteArray();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("curl 被中断: " + url, e);
        } finally {
            p.destroyForcibly();
        }
    }

    /**
     * 用 curl 下载文件到指定路径。
     *
     * @param url    请求 URL
     * @param target 目标文件路径
     */
    public static void downloadFile(String url, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".curldl");
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-sS");
        cmd.add("--max-time"); cmd.add(String.valueOf(TIMEOUT_SEC * 3));  // 文件下载给更多时间
        cmd.add("--connect-timeout"); cmd.add("10");
        cmd.add("-L");
        cmd.add("-H"); cmd.add("User-Agent: PMCL/1.0");
        cmd.add("-o"); cmd.add(tmp.toString());
        cmd.add(url);

        Process p = new ProcessBuilder(cmd).start();
        try {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            try (InputStream in = p.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) err.write(buf, 0, n);
            }
            boolean done = p.waitFor(TIMEOUT_SEC * 3 + 10, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IOException("curl 下载超时: " + url);
            }
            int exit = p.exitValue();
            if (exit != 0) {
                String errMsg = err.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
                throw new IOException("curl 下载失败 exit=" + exit + ": " + errMsg + " url=" + url);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("curl 下载被中断: " + url, e);
        }
    }

    /**
     * 用 curl HEAD 请求获取 Content-Length。
     *
     * @return 文件大小，失败返回 -1
     */
    public static long getContentLength(String url) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("curl");
            cmd.add("-sS");
            cmd.add("--max-time"); cmd.add("10");
            cmd.add("--connect-timeout"); cmd.add("5");
            cmd.add("-I");                     // HEAD 请求
            cmd.add("-L");
            cmd.add("-H"); cmd.add("User-Agent: PMCL/1.0");
            cmd.add(url);

            Process p = new ProcessBuilder(cmd).start();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                boolean done = p.waitFor(15, TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    return -1;
                }
                String resp = out.toString(java.nio.charset.StandardCharsets.UTF_8);
                // 解析 Content-Length 头
                for (String line : resp.split("\n")) {
                    line = line.trim();
                    if (line.toLowerCase().startsWith("content-length:")) {
                        String val = line.substring(15).trim();
                        return Long.parseLong(val);
                    }
                }
                return -1;
            } finally {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            return -1;
        }
    }
}
