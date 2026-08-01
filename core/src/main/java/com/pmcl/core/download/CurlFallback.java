package com.pmcl.core.download;

import com.pmcl.core.util.SsrfChecker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
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
    /** 手动跟随重定向最大跳数（替代 curl -L，每跳校验 SSRF） */
    private static final int MAX_REDIRECTS = 5;
    /** curl -w 输出格式：状态码 + 重定向 URL，附加到 stdout 末尾 */
    private static final String WRITE_OUT_FMT =
            "\n__PMCL_STATUS__%{http_code}\n__PMCL_REDIRECT__%{redirect_url}";
    /** -w 输出中状态码标记（ASCII，可在 byte[] 中安全搜索） */
    private static final byte[] STATUS_MARKER =
            "\n__PMCL_STATUS__".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private CurlFallback() {}

    /** 读取流到缓冲区，超过 maxBytes 则中止（防 OOM）。 */
    private static void readLimited(InputStream in, ByteArrayOutputStream out, int maxBytes)
            throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > maxBytes) {
                throw new IOException("curl 响应超过上限 " + maxBytes + " bytes");
            }
            out.write(buf, 0, n);
        }
    }

    /**
     * H34: 并发排空 stdout/stderr，避免管道缓冲区满造成父子进程互相等待死锁。
     */
    private static final class DrainedStreams {
        final byte[] stdout;
        final byte[] stderr;
        DrainedStreams(byte[] stdout, byte[] stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static DrainedStreams drainConcurrently(Process p, int maxStdout, int maxStderr)
            throws InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outT = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                readLimited(in, out, maxStdout);
            } catch (IOException ignored) {}
        }, "curl-stdout-drain");
        Thread errT = new Thread(() -> {
            try (InputStream in = p.getErrorStream()) {
                readLimited(in, err, maxStderr);
            } catch (IOException ignored) {}
        }, "curl-stderr-drain");
        outT.setDaemon(true);
        errT.setDaemon(true);
        outT.start();
        errT.start();
        outT.join();
        errT.join();
        return new DrainedStreams(out.toByteArray(), err.toByteArray());
    }

    // -------------------------------------------------------------------------
    // 手动重定向跟随（替代 curl -L，每跳校验 SSRF）
    // -------------------------------------------------------------------------

    /** 单次 curl 请求的解析结果。 */
    private static final class CurlResponse {
        final int httpCode;
        final String redirectUrl;
        final byte[] body;

        CurlResponse(int httpCode, String redirectUrl, byte[] body) {
            this.httpCode = httpCode;
            this.redirectUrl = redirectUrl;
            this.body = body;
        }

        boolean isRedirect() {
            return httpCode >= 300 && httpCode < 400
                    && redirectUrl != null && !redirectUrl.isEmpty();
        }
    }

    /**
     * 添加 -w 标志以捕获 HTTP 状态码和重定向 URL，并限制协议为 http/https。
     * 不使用 -L（手动跟随重定向，每跳校验 SSRF）。
     */
    private static void addWriteOutFlag(List<String> cmd) {
        cmd.add("-w"); cmd.add(WRITE_OUT_FMT);
        cmd.add("--proto"); cmd.add("=https,http");
    }

    /** 校验 URL 的 SSRF 安全性，失败时抛 IOException。 */
    private static void requireSsrfSafe(String url) throws IOException {
        String err = SsrfChecker.validate(url);
        if (err != null) {
            throw new IOException("SSRF 校验失败: " + err + " url=" + url);
        }
    }

    /** 解析相对重定向 URL 为绝对 URL。 */
    private static String resolveRedirect(String baseUrl, String location) throws IOException {
        if (location == null || location.isBlank()) {
            throw new IOException("重定向 Location 为空");
        }
        try {
            URI uri = URI.create(location);
            if (uri.getScheme() != null && uri.getHost() != null) {
                return location; // 已是绝对 URL
            }
            URI base = URI.create(baseUrl);
            return base.resolve(location).toString();
        } catch (Exception e) {
            throw new IOException("无法解析重定向 URL: " + location, e);
        }
    }

    /** 在 byte 数组中查找 byte 模式的最后出现位置。 */
    private static int lastIndexOf(byte[] data, byte[] pattern) {
        if (data.length < pattern.length) return -1;
        outer:
        for (int i = data.length - pattern.length; i >= 0; i--) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * 解析 curl stdout：分离响应体和 -w 追加的状态码/重定向 URL。
     * stdout 格式：{body}\n__PMCL_STATUS__{code}\n__PMCL_REDIRECT__{url}
     */
    private static CurlResponse parseCurlOutput(byte[] stdout) {
        int markerIdx = lastIndexOf(stdout, STATUS_MARKER);
        if (markerIdx < 0) {
            // -w 输出未找到（curl 版本过旧或输出被截断）
            return new CurlResponse(0, null, stdout);
        }
        byte[] body = new byte[markerIdx];
        System.arraycopy(stdout, 0, body, 0, markerIdx);

        String trailer = new String(stdout, markerIdx, stdout.length - markerIdx,
                java.nio.charset.StandardCharsets.UTF_8);
        int httpCode = 0;
        String redirectUrl = null;

        int statusKey = trailer.indexOf("__PMCL_STATUS__");
        if (statusKey >= 0) {
            int start = statusKey + "__PMCL_STATUS__".length();
            int end = trailer.indexOf('\n', start);
            String codeStr = (end >= 0 ? trailer.substring(start, end) : trailer.substring(start)).trim();
            try {
                httpCode = Integer.parseInt(codeStr);
            } catch (NumberFormatException ignored) {}
        }

        int redirKey = trailer.indexOf("__PMCL_REDIRECT__");
        if (redirKey >= 0) {
            int start = redirKey + "__PMCL_REDIRECT__".length();
            String redir = trailer.substring(start).trim();
            if (!redir.isEmpty()) redirectUrl = redir;
        }

        return new CurlResponse(httpCode, redirectUrl, body);
    }

    /**
     * 执行单次 curl 请求（不跟随重定向），解析 -w 输出。
     *
     * @param cmd       curl 命令（含 -w，不含 -L/-f）
     * @param maxStdout stdout 最大字节数
     * @param timeoutMs 超时（毫秒）
     */
    private static CurlResponse executeCurl(List<String> cmd, int maxStdout, long timeoutMs)
            throws IOException {
        Process p = new ProcessBuilder(cmd).start();
        try {
            try (OutputStream os = p.getOutputStream()) {
                os.close();
            }
            DrainedStreams drained = drainConcurrently(p, maxStdout, 256 * 1024);
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IOException("curl 超时");
            }
            int exit = p.exitValue();
            if (exit != 0) {
                String errMsg = new String(drained.stderr, java.nio.charset.StandardCharsets.UTF_8).trim();
                throw new IOException("curl 失败 exit=" + exit + ": " + errMsg);
            }
            return parseCurlOutput(drained.stdout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("curl 被中断", e);
        } finally {
            p.destroyForcibly();
        }
    }

    /**
     * 手动跟随重定向，每跳校验 SSRF。替代 curl -L，防止重定向到内网地址。
     *
     * @param initialUrl 初始 URL
     * @param cmdBuilder 给定 URL，返回完整 curl 命令（含 -w，不含 -L/-f）
     * @param maxStdout  stdout 最大字节数
     * @param timeoutMs  单次请求超时（毫秒）
     * @return 最终 2xx 响应
     */
    private static CurlResponse followRedirects(String initialUrl,
                                                java.util.function.Function<String, List<String>> cmdBuilder,
                                                int maxStdout, long timeoutMs) throws IOException {
        requireSsrfSafe(initialUrl);
        String currentUrl = initialUrl;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            List<String> cmd = cmdBuilder.apply(currentUrl);
            CurlResponse resp = executeCurl(cmd, maxStdout, timeoutMs);
            if (resp.isRedirect()) {
                String nextUrl = resolveRedirect(currentUrl, resp.redirectUrl);
                requireSsrfSafe(nextUrl);
                currentUrl = nextUrl;
                continue;
            }
            if (resp.httpCode < 200 || resp.httpCode >= 300) {
                throw new IOException("HTTP " + resp.httpCode + " url=" + currentUrl);
            }
            return resp;
        }
        throw new IOException("重定向超过最大跳数 " + MAX_REDIRECTS + " url=" + initialUrl);
    }

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
     * M59 修复：移除 "reset"/"broken pipe" 等过宽匹配——这些通常是连接被对端中断，
     * 而非 SSL 握手失败。仅匹配真正的 SSL/TLS 握手错误。
     */
    public static boolean isSslHandshakeFailure(Throwable e) {
        if (e == null) return false;
        // SSLHandshakeException 是最明确的信号
        if (e instanceof javax.net.ssl.SSLHandshakeException) return true;
        // SSLException 仅在消息包含握手/TLS 关键词时才视为握手失败
        if (e instanceof javax.net.ssl.SSLException) {
            String msg = e.getMessage();
            if (msg == null) return false;
            String lower = msg.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("handshake")
                    || lower.contains("ssl")
                    || lower.contains("tls")
                    || lower.contains("remote host terminated");
        }
        // 非 SSLException 的异常，仅在消息明确提及握手失败时才 fallback
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("ssl handshake")
                || lower.contains("tls handshake")
                || lower.contains("remote host terminated the handshake");
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
     * <p>
     * 注意：不用 -f（4xx 时需返回 body 便于诊断），不用 -L（POST 重定向会丢 body），
     * 不用 -X POST（--data-binary 已隐含 POST 方法）。
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
        cmd.add("--max-time"); cmd.add(String.valueOf(TIMEOUT_SEC));
        cmd.add("--connect-timeout"); cmd.add("10");
        // --data-binary 隐含 POST，无需 -X POST
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
        // body 作为 argv 传递（ProcessBuilder 不经 shell，%, &, = 不会被特殊处理）
        cmd.add("--data-binary"); cmd.add(body);
        // 在 body 末尾追加 HTTP 状态码，用于判断 4xx/5xx
        cmd.add("-w"); cmd.add("\n__HTTP_CODE__%{http_code}");
        cmd.add("--");
        cmd.add(url);

        Process p = new ProcessBuilder(cmd).start();
        try {
            // 关闭 stdin（body 通过 argv 传递，不从 stdin 读）
            try (OutputStream os = p.getOutputStream()) {
                os.close();
            }
            DrainedStreams drained = drainConcurrently(p, MAX_STRING_SIZE, 256 * 1024);
            boolean done = p.waitFor(TIMEOUT_SEC + 5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IOException("curl POST 超时: " + url);
            }
            int exit = p.exitValue();
            String errMsg = new String(drained.stderr, java.nio.charset.StandardCharsets.UTF_8).trim();
            // curl 退出码非 0 说明网络层错误（SSL/连接/超时）
            if (exit != 0) {
                throw new IOException("curl POST 网络失败 exit=" + exit + ": " + errMsg + " url=" + url);
            }
            String output = new String(drained.stdout, java.nio.charset.StandardCharsets.UTF_8);
            // 解析 -w 追加的 HTTP 状态码
            int sepIdx = output.lastIndexOf("\n__HTTP_CODE__");
            if (sepIdx < 0) {
                return output;
            }
            String httpCodeStr = output.substring(sepIdx + "\n__HTTP_CODE__".length()).trim();
            String responseBody = output.substring(0, sepIdx);
            int httpCode;
            try {
                httpCode = Integer.parseInt(httpCodeStr);
            } catch (NumberFormatException nfe) {
                return output;
            }
            if (httpCode < 200 || httpCode >= 300) {
                // httpCode=0 表示未收到 HTTP 响应（网络层失败）；4xx/5xx 不附带完整 body（可能含 token）
                throw new IOException("HTTP " + httpCode + " url=" + url
                        + " bodyLen=" + responseBody.length());
            }
            return responseBody;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("curl POST 被中断: " + url, e);
        } finally {
            p.destroyForcibly();
        }
    }

    /**
     * 用 curl 发起 POST 请求并返回响应体字符串，**不因 4xx/5xx 抛异常**。
     * <p>
     * 用于协议设计为"4xx 状态码 + JSON body 描述具体错误"的端点（典型例子：
     * Microsoft device code flow 的 token 端点对 authorization_pending /
     * slow_down / expired_token / authorization_declined 都返回 HTTP 400，
     * 调用方需从 body 的 error 字段区分具体状态）。
     * <p>
     * 仅在 curl 网络层失败（SSL/连接/超时，exit != 0）或 HTTP 000 时抛 IOException。
     *
     * @param url         请求 URL
     * @param body        请求体
     * @param contentType Content-Type
     * @param headers     额外请求头，可为 null
     * @return 响应体字符串（无论 HTTP 状态码）
     */
    public static String postStringAllowingErrors(String url, String body, String contentType,
                                                   List<String> headers) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-sS");
        cmd.add("--max-time"); cmd.add(String.valueOf(TIMEOUT_SEC));
        cmd.add("--connect-timeout"); cmd.add("10");
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
        cmd.add("-w"); cmd.add("\n__HTTP_CODE__%{http_code}");
        cmd.add("--");
        cmd.add(url);

        Process p = new ProcessBuilder(cmd).start();
        try {
            try (OutputStream os = p.getOutputStream()) {
                os.close();
            }
            DrainedStreams drained = drainConcurrently(p, MAX_STRING_SIZE, 256 * 1024);
            boolean done = p.waitFor(TIMEOUT_SEC + 5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IOException("curl POST 超时: " + url);
            }
            int exit = p.exitValue();
            String errMsg = new String(drained.stderr, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (exit != 0) {
                throw new IOException("curl POST 网络失败 exit=" + exit + ": " + errMsg + " url=" + url);
            }
            String output = new String(drained.stdout, java.nio.charset.StandardCharsets.UTF_8);
            int sepIdx = output.lastIndexOf("\n__HTTP_CODE__");
            if (sepIdx < 0) {
                return output;
            }
            String httpCodeStr = output.substring(sepIdx + "\n__HTTP_CODE__".length()).trim();
            String responseBody = output.substring(0, sepIdx);
            int httpCode;
            try {
                httpCode = Integer.parseInt(httpCodeStr);
            } catch (NumberFormatException nfe) {
                return output;
            }
            if (httpCode == 0) {
                throw new IOException("curl POST 未收到响应: " + errMsg + " url=" + url);
            }
            // 关键：不因 4xx/5xx 抛异常，直接返回 body
            return responseBody;
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
     * <p>
     * 手动跟随重定向（每跳 SSRF 校验），不使用 curl -L。
     *
     * @param url 请求 URL
     * @param timeoutSec 超时秒数
     * @return 响应体字符串
     */
    public static String getStringWithTimeout(String url, int timeoutSec) throws IOException {
        final int timeout = timeoutSec;
        CurlResponse resp = followRedirects(url, u -> {
            List<String> cmd = new ArrayList<>();
            cmd.add("curl");
            cmd.add("-sS");
            cmd.add("--max-time"); cmd.add(String.valueOf(timeout));
            cmd.add("--connect-timeout"); cmd.add(String.valueOf(Math.min(5, timeout)));
            addWriteOutFlag(cmd);
            cmd.add("-H"); cmd.add("User-Agent: PMCL/1.0");
            cmd.add("-H"); cmd.add("Accept: */*");
            cmd.add("--");
            cmd.add(u);
            return cmd;
        }, MAX_STRING_SIZE, (timeoutSec + 5L) * 1000);
        return new String(resp.body, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 用 curl 下载字节数据。
     * <p>
     * 手动跟随重定向（每跳 SSRF 校验），不使用 curl -L。
     *
     * @param url    请求 URL
     * @param method HTTP 方法（"GET" 或 "HEAD"），默认 GET
     * @param headers 额外请求头，可为 null
     * @return 响应体字节数组
     */
    public static byte[] getBytes(String url, String method, List<String> headers) throws IOException {
        final String m = method == null ? "GET" : method;
        final List<String> hdrs = headers;
        CurlResponse resp = followRedirects(url, u -> {
            List<String> cmd = new ArrayList<>();
            cmd.add("curl");
            cmd.add("-sS");
            cmd.add("--max-time"); cmd.add(String.valueOf(TIMEOUT_SEC));
            cmd.add("--connect-timeout"); cmd.add("10");
            addWriteOutFlag(cmd);
            cmd.add("-X"); cmd.add(m);
            cmd.add("-H"); cmd.add("User-Agent: PMCL/1.0");
            cmd.add("-H"); cmd.add("Accept: */*");
            if (hdrs != null) {
                for (String h : hdrs) {
                    cmd.add("-H"); cmd.add(h);
                }
            }
            cmd.add("--");
            cmd.add(u);
            return cmd;
        }, MAX_STRING_SIZE, (TIMEOUT_SEC + 5L) * 1000);
        return resp.body;
    }

    /**
     * 用 curl 下载文件到指定路径。
     * <p>
     * 手动跟随重定向（每跳 SSRF 校验），不使用 curl -L。
     * 临时文件 {@code .curldl} 在重定向过程中被多次覆写，仅最终 2xx 响应体保留。
     *
     * @param url    请求 URL
     * @param target 目标文件路径
     */
    public static void downloadFile(String url, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        final Path tmp = target.resolveSibling(target.getFileName() + ".curldl");
        try {
            followRedirects(url, u -> {
                List<String> cmd = new ArrayList<>();
                cmd.add("curl");
                cmd.add("-sS");
                cmd.add("--max-time"); cmd.add(String.valueOf(TIMEOUT_SEC * 3));
                cmd.add("--connect-timeout"); cmd.add("10");
                addWriteOutFlag(cmd);
                cmd.add("-H"); cmd.add("User-Agent: PMCL/1.0");
                cmd.add("-o"); cmd.add(tmp.toString());
                cmd.add("--");
                cmd.add(u);
                return cmd;
            }, 64 * 1024, (TIMEOUT_SEC * 3L + 10) * 1000);
            // 仅 2xx 到达此处：移动临时文件到目标
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * 用 curl HEAD 请求获取 Content-Length。
     * <p>
     * 手动跟随重定向（每跳 SSRF 校验），不使用 curl -L。
     *
     * @return 文件大小，失败返回 -1
     */
    public static long getContentLength(String url) {
        try {
            CurlResponse resp = followRedirects(url, u -> {
                List<String> cmd = new ArrayList<>();
                cmd.add("curl");
                cmd.add("-sS");
                cmd.add("--max-time"); cmd.add("10");
                cmd.add("--connect-timeout"); cmd.add("5");
                cmd.add("-I");                     // HEAD 请求
                addWriteOutFlag(cmd);
                cmd.add("-H"); cmd.add("User-Agent: PMCL/1.0");
                cmd.add("--");
                cmd.add(u);
                return cmd;
            }, 256 * 1024, 15_000);
            // HEAD 响应体为响应头；解析 Content-Length
            String headers = new String(resp.body, java.nio.charset.StandardCharsets.UTF_8);
            for (String line : headers.split("\n")) {
                line = line.trim();
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    String val = line.substring(15).trim();
                    return Long.parseLong(val);
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
