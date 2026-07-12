package com.pmcl.core.multiplayer;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * EasyTier 进程管理器：负责下载对应平台的 easytier-core 二进制、启动/停止进程、捕获输出。
 * <p>
 * 二进制存放在 {@code ~/.pmcl/easytier/easytier-core[.exe]}。
 * 首次启动时若文件不存在则自动从 GitHub Releases 下载并解压。
 * <p>
 * 陶瓦联机基于 EasyTier P2P 组网工具，使用官方公共共享节点 {@code tcp://public.easytier.cn:11010}。
 */
public final class EasyTierManager {

    /** 官方公共共享节点（陶瓦联机默认入口） */
    public static final String PUBLIC_PEER = "tcp://public.easytier.cn:11010";

    /**
     * GitHub 下载镜像列表（按顺序尝试）。GitHub Releases 在中国大陆访问不稳定，
     * 通过镜像加速可显著提升下载成功率。
     * <p>
     * 占位符 %s 会被替换为 GitHub Releases 路径（如 EasyTier/EasyTier/releases/latest/download/xxx.zip）。
     */
    private static final List<String> MIRROR_TEMPLATES = Arrays.asList(
            "https://github.com/%s",                                              // 直连
            "https://ghproxy.net/https://github.com/%s",                          // ghproxy
            "https://ghfast.top/https://github.com/%s",                           // ghfast
            "https://mirror.ghproxy.com/https://github.com/%s",                   // mirror.ghproxy
            "https://gh.api.99988866.xyz/https://github.com/%s"                   // 99988866
    );

    private final Path binaryDir;
    private final Path binaryPath;
    /** 复用一个带超时的 OkHttp 客户端 */
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private volatile Process process;
    private volatile Thread outputThread;

    public EasyTierManager() {
        this.binaryDir = Paths.get(System.getProperty("user.home"), ".pmcl", "easytier");
        this.binaryPath = binaryDir.resolve(isWindows() ? "easytier-core.exe" : "easytier-core");
    }

    /** 获取 easytier-core 可执行文件路径 */
    public Path getBinaryPath() { return binaryPath; }

    /** 二进制是否已下载就绪 */
    public boolean isBinaryReady() {
        return Files.exists(binaryPath) && Files.isExecutable(binaryPath);
    }

    /**
     * 若二进制不存在，则从 GitHub Releases 下载并解压。
     * <p>
     * 实现策略：
     * 1. 通过 {@code /releases/latest}（HTTP 302 重定向）拿到最新版本号；
     *    再请求 {@code /releases/expanded_assets/<version>}（HTML 端点，无速率限制）
     *    解析出 asset 列表。
     *    —— 不使用 {@code api.github.com}：该 API 对未认证请求限制为 60/小时，
     *       共享 IP（VPN/CGNAT）极易耗尽导致 403。
     * 2. 按当前平台（OS + 架构）匹配 asset 文件名（动态匹配，兼容命名规则变化）。
     *    实际命名规则：{@code easytier-<platform>-<arch>-v<version>.zip}，
     *    例如 {@code easytier-macos-x86_64-v2.6.4.zip}。
     * 3. 通过多镜像下载选中 asset。
     *
     * @param progress 进度回调（下载阶段为 0..50，解压阶段为 50..100）
     */
    public CompletableFuture<Void> ensureBinary(Consumer<String> progress) {
        if (isBinaryReady()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(binaryDir);
                if (progress != null) progress.accept("查询最新版本…");
                // 1. 通过 HTML 端点拿最新版本号 + asset 列表
                ReleaseInfo info = fetchLatestRelease();
                if (progress != null) progress.accept("最新版本 " + info.version + "，共 " + info.assets.size() + " 个文件");
                // 2. 按当前平台匹配 asset
                String assetName = matchAsset(info.assets);
                if (assetName == null) {
                    throw new RuntimeException("在最新版本中找不到适配当前平台的 asset，候选：" + info.assets);
                }
                String githubPath = "EasyTier/EasyTier/releases/download/" + info.version + "/" + assetName;
                Path zip = binaryDir.resolve(assetName);
                if (progress != null) progress.accept("正在下载 " + assetName);
                downloadFile(githubPath, zip, progress);
                if (progress != null) progress.accept("正在解压…");
                extractEasyTierCore(zip, binaryPath);
                if (!isWindows()) {
                    try {
                        Process p = new ProcessBuilder("chmod", "+x", binaryPath.toString())
                                .redirectErrorStream(true).start();
                        try {
                            p.waitFor(10, TimeUnit.SECONDS);
                        } finally {
                            p.destroyForcibly();
                        }
                    } catch (Exception ignored) {}
                    // macOS：移除 com.apple.quarantine 隔离属性，避免 Gatekeeper 阻止运行
                    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
                        try {
                            Process p = new ProcessBuilder("xattr", "-d", "com.apple.quarantine", binaryPath.toString())
                                    .redirectErrorStream(true).start();
                            try {
                                p.waitFor(10, TimeUnit.SECONDS);
                            } finally {
                                p.destroyForcibly();
                            }
                        } catch (Exception ignored) {}
                    }
                }
                Files.deleteIfExists(zip);
                if (progress != null) progress.accept("EasyTier 就绪");
                return (Void) null;
            } catch (Exception e) {
                throw new RuntimeException("下载 EasyTier 失败：" + e.getMessage(), e);
            }
        });
    }

    /** GitHub Release 元数据 */
    private static final class ReleaseInfo {
        String version;                       // "v2.6.4"
        java.util.List<String> assets = new java.util.ArrayList<>();
    }

    /**
     * 获取最新 release 元数据。
     * <p>
     * 优先用 HTML 端点（{@code /releases/latest} + {@code /releases/expanded_assets/<tag>}），
     * 该路径走 github.com 主站，无 API 速率限制，国内可直连。
     * <p>
     * 仅当 HTML 抓取失败时回退到 {@code api.github.com}（注意 60/小时限流）。
     */
    private ReleaseInfo fetchLatestRelease() throws IOException {
        // 步骤 1：通过 /releases/latest 的 302 重定向拿到 tag 名
        String tag = fetchLatestTag();
        if (tag == null || tag.isEmpty()) {
            // 回退：API
            return fetchLatestReleaseFromApi();
        }
        // 步骤 2：抓 expanded_assets 端点拿 asset 列表
        ReleaseInfo info = new ReleaseInfo();
        info.version = tag;
        try {
            List<String> assets = fetchAssetList(tag);
            info.assets.addAll(assets);
        } catch (IOException ignored) {
            // asset 列表抓取失败时也保留 version，后续走 API 兜底
        }
        if (info.assets.isEmpty()) {
            // 回退：API
            ReleaseInfo api = fetchLatestReleaseFromApi();
            if (api.version != null && !api.version.isEmpty()) info.version = api.version;
            if (!api.assets.isEmpty()) info.assets = api.assets;
        }
        if (info.version == null) info.version = "latest";
        return info;
    }

    /** 请求 /releases/latest，从 Location 头解析 tag 名（如 "v2.6.4"） */
    private String fetchLatestTag() throws IOException {
        // 不自动跟随重定向，直接读 Location 头
        OkHttpClient noRedirect = http.newBuilder().followRedirects(false).build();
        Request req = new Request.Builder()
                .url("https://github.com/EasyTier/EasyTier/releases/latest")
                .header("User-Agent", "PMCL/1.0")
                .get().build();
        try (Response resp = noRedirect.newCall(req).execute()) {
            // 302 / 301
            String location = resp.header("Location");
            if (location == null || location.isEmpty()) return null;
            // location 形如 https://github.com/EasyTier/EasyTier/releases/tag/v2.6.4
            int idx = location.lastIndexOf('/');
            if (idx < 0 || idx == location.length() - 1) return null;
            return location.substring(idx + 1);
        }
    }

    /**
     * 抓取 {@code /releases/expanded_assets/<tag>} 页面，正则提取 asset 文件名。
     * <p>
     * 页面 HTML 含形如 {@code href="/EasyTier/EasyTier/releases/download/v2.6.4/easytier-macos-x86_64-v2.6.4.zip"}
     * 的链接，正则匹配后取最后一段文件名。
     */
    private List<String> fetchAssetList(String tag) throws IOException {
        String url = "https://github.com/EasyTier/EasyTier/releases/expanded_assets/" + tag;
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "PMCL/1.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .get().build();
        String body;
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            body = resp.body() != null ? resp.body().string() : "";
        }
        List<String> assets = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/EasyTier/EasyTier/releases/download/" + java.util.regex.Pattern.quote(tag) + "/([^\"]+)")
                .matcher(body);
        while (m.find()) {
            String name = m.group(1);
            if (!assets.contains(name)) assets.add(name);
        }
        return assets;
    }

    /** API 回退：调用 api.github.com（注意 60/小时限流，共享 IP 可能 403） */
    private ReleaseInfo fetchLatestReleaseFromApi() throws IOException {
        String[] apiUrls = {
            "https://api.github.com/repos/EasyTier/EasyTier/releases/latest",
            "https://api.github.com/repos/EasyTier/EasyTier/releases"
        };
        IOException last = null;
        for (String apiUrl : apiUrls) {
            try {
                Request req = new Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", "PMCL/1.0")
                        .header("Accept", "application/vnd.github+json")
                        .get().build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
                    String body = resp.body() != null ? resp.body().string() : "{}";
                    return parseReleaseJson(body);
                }
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("无法访问 GitHub API：" +
                (last != null ? last.getMessage() : "未知"), last);
    }

    /** 简单 JSON 解析：提取 tag_name 和所有 asset name */
    private ReleaseInfo parseReleaseJson(String json) {
        ReleaseInfo info = new ReleaseInfo();
        java.util.regex.Matcher tagM = java.util.regex.Pattern
                .compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        if (tagM.find()) info.version = tagM.group(1);
        java.util.regex.Matcher nameM = java.util.regex.Pattern
                .compile("\"name\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        while (nameM.find()) {
            String n = nameM.group(1);
            if (n.equals("github-actions[bot]") || n.startsWith("v") && n.matches("v\\d+\\.\\d+\\.\\d+")) {
                continue;
            }
            info.assets.add(n);
        }
        if (info.version == null) info.version = "latest";
        return info;
    }

    /**
     * 按当前平台从 asset 列表中匹配正确的 easytier-core 包。
     * <p>
     * 实际命名规则（v2.6.4 起观察得到）：
     * <ul>
     *   <li>macOS x86_64: {@code easytier-macos-x86_64-v2.6.4.zip}</li>
     *   <li>macOS aarch64: {@code easytier-macos-aarch64-v2.6.4.zip}</li>
     *   <li>Linux x86_64: {@code easytier-linux-x86_64-v2.6.4.zip}</li>
     *   <li>Windows x86_64: {@code easytier-windows-x86_64-v2.6.4.zip}</li>
     * </ul>
     * 故匹配关键字为 {@code easytier-<platform>[-<arch>]},排除 gui / apk / rpm / deb / AppImage / dmg / Magisk 等。
     * 同时兼容老版本命名（{@code easytier-core-*} / 含 darwin / apple）。
     */
    private String matchAsset(java.util.List<String> assets) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        // 平台关键字（多种命名变体）
        List<String> osKeys;
        String archKey;
        if (os.contains("win")) {
            osKeys = Arrays.asList("windows");
            archKey = "x86_64";
        } else if (os.contains("mac")) {
            osKeys = Arrays.asList("macos", "darwin", "apple");
            archKey = (arch.contains("aarch64") || arch.contains("arm")) ? "aarch64" : "x86_64";
        } else {
            osKeys = Arrays.asList("linux");
            archKey = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x86_64";
        }
        // 排除关键字：GUI 客户端、Android apk、Linux 包格式、Magisk、Web Dashboard 等
        java.util.function.Predicate<String> isCliZip = ln ->
                !ln.contains("gui") && !ln.endsWith(".apk") && !ln.endsWith(".rpm")
                && !ln.endsWith(".deb") && !ln.endsWith(".appimage") && !ln.endsWith(".dmg")
                && !ln.endsWith(".exe") && !ln.contains("magisk") && !ln.contains("web-dashboard")
                && !ln.contains("freebsd") && !ln.contains("loongarch") && !ln.contains("mips")
                && !ln.contains("riscv") && !ln.contains("armv7") && !ln.contains("armhf")
                && !ln.contains("-arm-");

        // 第一轮：精确匹配 平台 + 架构
        for (String name : assets) {
            String ln = name.toLowerCase(Locale.ROOT);
            if (!isCliZip.test(ln)) continue;
            boolean osMatch = osKeys.stream().anyMatch(ln::contains);
            if (osMatch && ln.contains(archKey) && ln.endsWith(".zip")) {
                return name;
            }
        }
        // 第二轮：放宽架构（仅匹配平台，取第一个 zip）
        for (String name : assets) {
            String ln = name.toLowerCase(Locale.ROOT);
            if (!isCliZip.test(ln)) continue;
            boolean osMatch = osKeys.stream().anyMatch(ln::contains);
            if (osMatch && ln.endsWith(".zip")) {
                return name;
            }
        }
        // 第三轮：兼容老命名 easytier-core-*
        for (String name : assets) {
            String ln = name.toLowerCase(Locale.ROOT);
            if (ln.contains("easytier-core") && ln.endsWith(".zip")
                && osKeys.stream().anyMatch(ln::contains)) {
                return name;
            }
        }
        return null;
    }

    /**
     * 启动 easytier-core 进程。
     *
     * @param networkName    网络名
     * @param networkSecret  网络密钥
     * @param peer           对等节点 URL（如 {@link #PUBLIC_PEER}），为空则不连接
     * @param onOutput       输出回调（每行一次），可用于状态解析
     * @return 进程已启动的 CompletableFuture
     */
    public CompletableFuture<Void> start(String networkName,
                                          String networkSecret,
                                          String peer,
                                          Consumer<String> onOutput) {
        if (process != null && process.isAlive()) {
            return CompletableFuture.failedFuture(new IllegalStateException("EasyTier 已在运行"));
        }
        return ensureBinary(null).thenRun(() -> {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(binaryPath.toString());
                cmd.add("--network-name"); cmd.add(networkName);
                cmd.add("--network-secret"); cmd.add(networkSecret);
                cmd.add("-d");                   // DHCP 自动分配虚拟 IP
                cmd.add("--multi-thread");
                cmd.add("--instance-name"); cmd.add("pmcl");
                if (peer != null && !peer.isEmpty()) {
                    cmd.add("-p"); cmd.add(peer);
                }
                cmd.add("--console-log-level"); cmd.add("info");

                ProcessBuilder pb = new ProcessBuilder(cmd)
                        .redirectErrorStream(true);
                process = pb.start();

                // 异步读取输出
                final Process procForThread = process;
                outputThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(procForThread.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (onOutput != null) {
                                try { onOutput.accept(line); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (IOException ignored) {}
                }, "easytier-output");
                outputThread.setDaemon(true);
                outputThread.start();
            } catch (IOException e) {
                throw new RuntimeException("启动 EasyTier 失败：" + e.getMessage(), e);
            }
        });
    }

    /** 停止正在运行的 easytier-core 进程 */
    public void stop() {
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            // 给 1.5 秒优雅退出，否则强杀
            try {
                if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        process = null;
        if (outputThread != null) {
            outputThread.interrupt();
            outputThread = null;
        }
    }

    /** 进程是否在运行 */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * 查询本机所有虚拟网卡的 IPv4 地址。
     * <p>
     * EasyTier 会创建 TUN 网卡，网卡名通常含 "tun" / "easytier" / "utun" / "wintun"。
     * 此方法跨平台枚举所有 NetworkInterface，按名称关键字过滤，返回第一个匹配的非回环 IPv4。
     * <p>
     * 相比从日志正则解析，此方法更可靠（不依赖输出格式）。
     *
     * @return 虚拟 IPv4 字符串（如 "10.144.144.10"），未找到返回空串
     */
    public String queryVirtualIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                String name = iface.getName().toLowerCase(Locale.ROOT);
                String disp = iface.getDisplayName() != null
                        ? iface.getDisplayName().toLowerCase(Locale.ROOT) : "";
                // EasyTier / TUN 网卡名称关键字（扩展：含 tap / p2p / 等）
                boolean isVirtual = name.contains("tun") || name.contains("easytier")
                        || disp.contains("tun") || disp.contains("easytier")
                        || name.contains("wintun") || name.contains("utun")
                        || name.contains("tap") || name.contains("p2p")
                        || disp.contains("tap");
                if (!isVirtual) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (java.net.SocketException ignored) {}
        return "";
    }

    /**
     * 诊断方法：列出所有网卡的名称、显示名、是否虚拟、IPv4 地址。
     * 用于 TUN 网卡匹配失败时排查。
     */
    public String dumpAllInterfaces() {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                String name = iface.getName();
                String disp = iface.getDisplayName();
                boolean up = iface.isUp();
                boolean loopback = iface.isLoopback();
                boolean virtual = iface.isVirtual();
                StringBuilder ips = new StringBuilder();
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        ips.append(addr.getHostAddress()).append(" ");
                    }
                }
                sb.append(String.format("[%s] name=%s disp=%s up=%s virt=%s ipv4=%s; ",
                        loopback ? "lo" : "nic", name, disp, up, virtual, ips.toString().trim()));
            }
        } catch (java.net.SocketException e) {
            sb.append("枚举失败: ").append(e.getMessage());
        }
        return sb.length() > 0 ? sb.toString() : "(无网卡)";
    }

    // ============ 平台/下载辅助 ============

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 从 URL 模板中安全提取 hostname，split 失败时返回原始模板 */
    private static String safeHostname(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    // resolveAssetName 已废弃：改用 GitHub API 动态查询 asset 列表 + 平台匹配，见 matchAsset()

    /**
     * 多镜像 + 重试下载。GitHub Releases 在国内访问不稳定，依次尝试镜像列表，
     * 每个镜像最多重试 2 次。任意镜像成功即返回。
     *
     * @param githubPath GitHub 上的路径，如 {@code EasyTier/EasyTier/releases/latest/download/xxx.zip}
     * @param target     目标文件路径
     * @param progress   进度回调（可空）
     */
    private void downloadFile(String githubPath, Path target, Consumer<String> progress) throws IOException {
        Files.createDirectories(target.getParent());
        IOException lastError = null;
        for (int mi = 0; mi < MIRROR_TEMPLATES.size(); mi++) {
            String tmpl = MIRROR_TEMPLATES.get(mi);
            String url = String.format(tmpl, githubPath);
            String mirrorName = tmpl.startsWith("https://github.com")
                    ? "GitHub 直连"
                    : safeHostname(tmpl);
            // 每个镜像重试 2 次
            for (int retry = 0; retry < 2; retry++) {
                try {
                    if (progress != null) {
                        progress.accept("下载中（" + mirrorName + "）" +
                                (retry > 0 ? " 重试 " + retry : "") + " …");
                    }
                    Request req = new Request.Builder()
                            .url(url)
                            .header("User-Agent", "PMCL/1.0")
                            .get()
                            .build();
                    try (Response resp = http.newCall(req).execute()) {
                        if (!resp.isSuccessful()) {
                            throw new IOException("HTTP " + resp.code());
                        }
                        if (resp.body() == null) throw new IOException("响应体为空");
                        try (InputStream in = resp.body().byteStream()) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    // 校验下载内容是有效 zip（魔数 PK\x03\x04），避免镜像返回 200 但内容是 HTML 错误页
                    byte[] magic = new byte[4];
                    try (java.io.InputStream is = Files.newInputStream(target)) {
                        int read = is.read(magic);
                        if (read < 4) {
                            Files.deleteIfExists(target);
                            throw new IOException("下载内容不是有效 zip（可能是镜像错误页）");
                        }
                    }
                    if (magic[0] != 0x50 || magic[1] != 0x4B ||
                        magic[2] != 0x03 || magic[3] != 0x04) {
                        Files.deleteIfExists(target);
                        throw new IOException("下载内容不是有效 zip（可能是镜像错误页）");
                    }
                    if (progress != null) progress.accept("下载完成");
                    return; // 成功即返回
                } catch (IOException e) {
                    lastError = e;
                    if (progress != null) {
                        progress.accept(mirrorName + " 失败：" + e.getMessage());
                    }
                    // 短暂等待后重试
                    try { Thread.sleep(500L * (retry + 1)); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("下载被中断", ie);
                    }
                }
            }
        }
        throw new IOException("所有镜像下载失败，最后错误：" +
                (lastError != null ? lastError.getMessage() : "未知"), lastError);
    }

    /** 从 zip 中提取 easytier-core 二进制到目标路径 */
    private void extractEasyTierCore(Path zip, Path outBinary) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                // 匹配 easytier-core 或 easytier-core.exe
                if (name.endsWith("easytier-core") || name.endsWith("easytier-core.exe")) {
                    Files.copy(zis, outBinary, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
            throw new IOException("压缩包中未找到 easytier-core 二进制");
        }
    }
}
