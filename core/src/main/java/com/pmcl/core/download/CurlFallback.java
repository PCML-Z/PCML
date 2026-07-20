package com.pmcl.core.download;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
        Process p = null;
        try {
            p = new ProcessBuilder("curl", "--version").start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (!done) return false;
            // 排空输出流，防止子进程因管道缓冲区满而阻塞
            try (InputStream is = p.getInputStream()) {
                is.transferTo(OutputStream.nullOutputStream());
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroyForcibly();
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
     * 用 curl 发起 POST 请求并返回响应体字符串。
     * <p>
     * 用于 OkHttp SSL 握手失败时的 fallback（GFW 环境下 Java TLS 指纹被识别并 RST）。
     *
     * @param url         请求 URL
     * @param body        请求体
     * @param contentType Content-Type（如 "application/json" 或 "application/x-www-form-urlencoded"）
     * @param headers     额外请求头，可为 null
     * @return 响应体字符串
     */
    public static String postString(String url, String body, String contentType,
                                     List<String> headers) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-sS");
        cmd.add("-f");                     // HTTP 4xx/5xx 返回非零退出码
        cmd.add("--max-time"); cmd.add(String.valueOf(TIMEOUT_SEC));
        cmd.add("--connect-timeout"); cmd.add("10");
        cmd.add("-L");                     // 跟随重定向
        cmd.add("-X"); cmd.add("POST");
        cmd.add("-H"); cmd.add("User-Agent: PMCL/1.0");
        cmd.add("-H"); cmd.add("Accept: */*");
        if (contentType != null && !contentType.isEmpty()) {
            cmd.add("-H"); cmd.add("Content-Type: " + contentType);
        }
        if (headers != null) {
            for (String h : headers) {
                cmd.add("-H"); cmd.add(h);
            }
        }
        cmd.add("--data-binary"); cmd.add(body);
        cmd.add(url);

        Process p = new ProcessBuilder(cmd).start();
        try {
            // 关闭 stdin（curl 用 --data-binary 参数传 body，不从 stdin 读）
            try (OutputStream os = p.getOutputStream()) {
                os.close();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            try (InputStream in = p.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) err.write(buf, 0, n);
            }
            boolean done = p.waitFor(TIMEOUT_SEC + 5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IOException("curl POST 超时: " + url);
            }
            int exit = p.exitValue();
            if (exit != 0) {
                String errMsg = err.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
                throw new IOException("curl POST 失败 exit=" + exit + ": " + errMsg + " url=" + url);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("curl POST 被中断: " + url, e);
        } finally {
            p.destroyForcibly();
        }
    }

    /**
     * 用 curl 下载文本内容，自定义超时（秒）。
     * 用于快速探测被屏蔽的源（如 Google Translate 在 GFW 环境下），
     * 避免使用默认 30 秒超时导致长时间阻塞。
     *
     * @param url 请求 URL
     * @param timeoutSec 超时秒数
     * @return 响应体字符串
     */
    public static String getStringWithTimeout(String url, int timeoutSec) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-sS");
        cmd.add("-f");                     // HTTP 4xx/5xx 返回非零退出码
        cmd.add("--max-time"); cmd.add(String.valueOf(timeoutSec));
        cmd.add("--connect-timeout"); cmd.add(String.valueOf(Math.min(5, timeoutSec)));
        cmd.add("-L");
        cmd.add("-H"); cmd.add("User-Agent: PMCL/1.0");
        cmd.add("-H"); cmd.add("Accept: */*");
        cmd.add(url);

        Process p = new ProcessBuilder(cmd).start();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            try (InputStream in = p.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) err.write(buf, 0, n);
            }
            boolean done = p.waitFor(timeoutSec + 5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IOException("curl 超时: " + url);
            }
            int exit = p.exitValue();
            if (exit != 0) {
                String errMsg = err.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
                throw new IOException("curl 失败 exit=" + exit + ": " + errMsg + " url=" + url);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("curl 被中断: " + url, e);
        } finally {
            p.destroyForcibly();
        }
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
        cmd.add("-f");                     // HTTP 4xx/5xx 返回非零退出码（避免把错误页当成功响应）
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
        cmd.add("-f");                     // HTTP 4xx/5xx 返回非零退出码
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
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("curl 下载被中断: " + url, e);
        } finally {
            p.destroyForcibly();
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
            cmd.add("-f");                     // HTTP 4xx/5xx 返回非零退出码
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
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
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
