package com.pmcl.core.metal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.instance.InstanceManager;
import com.pmcl.core.market.ModrinthClient;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.version.VersionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * MetalRender 自动安装器：从 Modrinth 下载 MetalRender 及其依赖到 mods 目录。
 * <p>
 * MetalRender 是一个 Fabric mod，使用 Apple Metal API 替换 Sodium 的渲染后端，
 * 仅在 Apple Silicon Mac (M1+) 上生效。
 * <p>
 * 依赖链：MetalRender → Sodium → Fabric API；推荐安装 ModMenu 用于配置界面。
 * mods 路径与 {@link com.pmcl.core.launch.LaunchProfileBuilder} 的 gameDir/mods 对齐：
 * <ul>
 *   <li>版本隔离 → {@code instances/<versionId>/mods/}</li>
 *   <li>整合包（版本目录含 mods/）→ {@code versions/<versionId>/mods/}</li>
 *   <li>普通版本 → {@code <mcRoot>/mods/}（外部安装则为 ~/.minecraft/mods）</li>
 * </ul>
 */
public final class MetalRenderInstaller {

    /** Modrinth 项目 slug/id 列表（安装顺序：依赖优先；优先用 slug，避免旧 id 失效） */
    private static final String[] PROJECT_IDS = {
            "fabric-api", // Fabric API（基础依赖）
            "sodium",     // Sodium（MetalRender 的前置）
            "modmenu",    // Mod Menu（配置界面；旧 id m5HZN3Zi 已 404）
            "metalrender" // MetalRender 本体
    };

    /** MetalRender 本体项目 id（用于可用性预检） */
    private static final String METALRENDER_PROJECT = "metalrender";

    /**
     * 离线回退：MetalRender 当前公开支持的 MC 版本（Modrinth 不可达时使用）。
     * 以 Modrinth 项目实际 game_versions 为准，定期核对。
     */
    private static final List<String> FALLBACK_SUPPORTED_VERSIONS =
            List.of("1.21.8", "1.21.9", "1.21.10");

    /**
     * 关闭开关时仅移除 MetalRender 本体，不删 Sodium / Fabric API / ModMenu：
     * 这些常为整合包原有依赖，误删会导致整包无法启动。
     * 文件名允许 {@code +}（如 {@code metalrender-0.1.6+1.21.8.jar}）。
     */
    private static final Pattern[] UNINSTALL_PATTERNS = {
            Pattern.compile("metalrender-[\\w.+\\-]+\\.jar(\\.disabled)?")
    };

    private final LauncherConfig config;
    private final Preferences preferences;
    private final ModrinthClient modrinth;
    private final DownloadManager downloads;

    public MetalRenderInstaller(LauncherConfig config, Preferences preferences,
                                ModrinthClient modrinth, DownloadManager downloads) {
        this.config = config;
        this.preferences = preferences;
        this.modrinth = modrinth;
        this.downloads = downloads;
    }

    /** 进程级缓存：避免设置页每次组合都 fork sysctl（waitFor 最长约 2s） */
    private static volatile Boolean appleSiliconCached;

    /**
     * 检测当前是否为 Apple Silicon Mac。
     * <p>
     * 优先用 sysctl 检测真实硬件架构（Rosetta 2 下 os.arch 不可靠）。
     * 结果进程级缓存；先 waitFor 再读流，避免 readAllBytes 阻塞导致卡死。
     *
     * @return true 表示当前是 Apple Silicon Mac
     */
    public static boolean isAppleSiliconMac() {
        Boolean cached = appleSiliconCached;
        if (cached != null) return cached;

        synchronized (MetalRenderInstaller.class) {
            cached = appleSiliconCached;
            if (cached != null) return cached;

            boolean result = detectAppleSiliconMac();
            appleSiliconCached = result;
            return result;
        }
    }

    private static boolean detectAppleSiliconMac() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("mac")) return false;

        // 优先用 sysctl 检测（Rosetta 2 下 os.arch 会被骗成 x86_64）
        Process p = null;
        try {
            p = new ProcessBuilder("sysctl", "-n", "hw.optional.arm64")
                    .redirectErrorStream(true)
                    .start();
            // 先等待进程结束（带超时），再读取输出，避免 readAllBytes 阻塞
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "aarch64".equals(System.getProperty("os.arch", ""));
            }
            try (var in = p.getInputStream()) {
                String out = new String(in.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                return p.exitValue() == 0 && "1".equals(out);
            }
        } catch (Exception ignored) {
            // sysctl 失败则回退到 os.arch
        } finally {
            if (p != null) p.destroyForcibly();
        }
        return "aarch64".equals(System.getProperty("os.arch", ""));
    }

    /** 支持版本列表缓存（网络成功结果）；失败不缓存以便下次重试 */
    private volatile List<String> supportedVersionsCache;

    /**
     * 查询 MetalRender 当前支持的 Minecraft 版本列表（优先 Modrinth，失败则回退常量）。
     * 成功结果会缓存，避免设置页重复打网络。
     */
    public List<String> supportedGameVersions() {
        List<String> cached = supportedVersionsCache;
        if (cached != null) return cached;

        synchronized (this) {
            cached = supportedVersionsCache;
            if (cached != null) return cached;
            try {
                JsonObject project = modrinth.getProject(METALRENDER_PROJECT);
                if (project != null && project.has("game_versions")) {
                    List<String> versions = new ArrayList<>();
                    for (JsonElement e : project.getAsJsonArray("game_versions")) {
                        if (e != null && e.isJsonPrimitive()) {
                            versions.add(e.getAsString());
                        }
                    }
                    if (!versions.isEmpty()) {
                        List<String> frozen = Collections.unmodifiableList(versions);
                        supportedVersionsCache = frozen;
                        return frozen;
                    }
                }
            } catch (Exception ignored) {
                // 网络失败时用离线列表（不缓存失败，下次仍可重试）
            }
            return FALLBACK_SUPPORTED_VERSIONS;
        }
    }

    /**
     * 安装 MetalRender 及其依赖到目标版本的 mods 目录。
     * <p>
     * 先预检 MetalRender 本体是否有兼容版本，再下载依赖；任一步失败会回滚本次新下载的文件。
     *
     * @param versionId   本地版本/实例 ID（版本隔离时决定目录），可为 null
     * @param gameVersion Minecraft 版本（如 "1.21.8"），用于 Modrinth 过滤
     * @param loader      加载器（通常为 "fabric"）
     * @param onProgress  进度回调（当前 mod 名称），可为 null
     * @throws IOException 下载或写入失败
     */
    public void install(String versionId, String gameVersion, String loader,
                        Consumer<String> onProgress) throws IOException {
        if (gameVersion == null || gameVersion.isBlank()) {
            throw new IOException("未指定 Minecraft 版本");
        }
        if (loader == null || loader.isBlank()) {
            loader = "fabric";
        }
        if (!"fabric".equalsIgnoreCase(loader)) {
            throw new IOException("MetalRender 仅支持 Fabric，当前加载器: " + loader);
        }

        // 预检并选择与当前 MC 版本可共存的 MetalRender（避免 0.1.6 强依赖 ModMenu 16，
        // 而 ModMenu 16 又不支持 1.21.8 的死锁）
        JsonObject metalVersion = selectMetalRenderVersion(gameVersion, "fabric");
        if (metalVersion == null) {
            String supported = String.join(", ", supportedGameVersions());
            throw new IOException("MetalRender 暂不支持 Minecraft " + gameVersion
                    + "（当前支持: " + supported + "）。请选择兼容版本后重试。");
        }

        Path modsDir = resolveModsDir(versionId, gameVersion);
        Files.createDirectories(modsDir);

        List<Path> downloaded = new ArrayList<>();
        try {
            for (String projectId : PROJECT_IDS) {
                if (onProgress != null) {
                    onProgress.accept(projectId);
                }
                JsonObject version = METALRENDER_PROJECT.equals(projectId)
                        ? metalVersion
                        : resolveDependencyVersion(projectId, gameVersion, loader, metalVersion);
                if (version == null) {
                    // ModMenu 对旧版 MetalRender 非硬依赖：找不到可跳过
                    if ("modmenu".equalsIgnoreCase(projectId) && !requiresModMenu16(metalVersion)) {
                        if (onProgress != null) {
                            onProgress.accept("skip:modmenu");
                        }
                        continue;
                    }
                    throw new IOException("Modrinth 上未找到 " + projectId
                            + " 兼容 " + gameVersion + "/" + loader + " 的版本");
                }
                Path target = downloadVersionFile(projectId, version, modsDir);
                downloaded.add(target);
                if (onProgress != null) {
                    onProgress.accept("done:" + target.getFileName());
                }
            }
        } catch (IOException e) {
            rollback(downloaded);
            throw e;
        } catch (RuntimeException e) {
            rollback(downloaded);
            throw new IOException(e.getMessage() != null ? e.getMessage() : "MetalRender 安装失败", e);
        }
    }

    /**
     * 选择适合当前 MC 版本的 MetalRender：
     * <ul>
     *   <li>若该 MC 版本有 ModMenu 16+：可用最新 MetalRender（含 0.1.6）</li>
     *   <li>否则：跳过需要 ModMenu 16 的版本，回退到 0.1.5 / 0.1.4 等</li>
     * </ul>
     */
    private JsonObject selectMetalRenderVersion(String gameVersion, String loader) {
        boolean modMenu16ForGame = isModMenu16Plus(
                modrinth.getLatestVersion("modmenu", gameVersion, loader));
        List<JsonObject> candidates = modrinth.listVersions(METALRENDER_PROJECT, gameVersion, loader);
        if (candidates.isEmpty()) return null;

        JsonObject fallback = null;
        for (JsonObject v : candidates) {
            String vt = v.has("version_type") ? v.get("version_type").getAsString() : "release";
            boolean isRelease = "release".equals(vt);
            if (requiresModMenu16(v) && !modMenu16ForGame) {
                continue; // 例如 1.21.8 + MetalRender 0.1.6
            }
            if (isRelease) return v;
            if (fallback == null) fallback = v;
        }
        return fallback;
    }

    /**
     * 解析依赖版本。ModMenu 必须与当前 MC 版本匹配，禁止跨版本硬塞 16.x。
     */
    private JsonObject resolveDependencyVersion(String projectId, String gameVersion,
                                                String loader, JsonObject metalVersion) {
        if ("modmenu".equalsIgnoreCase(projectId)) {
            JsonObject forGame = modrinth.getLatestVersion(projectId, gameVersion, loader);
            if (requiresModMenu16(metalVersion)) {
                if (isModMenu16Plus(forGame)) return forGame;
                throw new RuntimeException("MetalRender 需要 Mod Menu 16+，但 Minecraft "
                        + gameVersion + " 上没有兼容的 Mod Menu 16。"
                        + "请改用 1.21.9+，或由安装器自动回退到旧版 MetalRender。");
            }
            // 旧版 MetalRender：装当前 MC 可用的 ModMenu（通常 15.x）即可
            return forGame;
        }
        return modrinth.getLatestVersion(projectId, gameVersion, loader);
    }

    /** MetalRender ≥0.1.6 在 fabric.mod.json 中声明 depends.modmenu ≥16.0.0-rc.1 */
    private static boolean requiresModMenu16(JsonObject metalVersion) {
        if (metalVersion == null) return true;
        String vn = metalVersion.has("version_number")
                ? metalVersion.get("version_number").getAsString() : "";
        // v0.1.6 / 0.1.6 及更新需要 ModMenu 16；更旧版本不强制
        String n = vn.startsWith("v") || vn.startsWith("V") ? vn.substring(1) : vn;
        try {
            String[] parts = n.split("[.+\\-]");
            int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            if (major > 0) return true;
            if (minor > 1) return true;
            return minor == 1 && patch >= 6;
        } catch (NumberFormatException e) {
            return true; // 解析失败时按新版要求处理
        }
    }

    private static boolean isModMenu16Plus(JsonObject version) {
        if (version == null) return false;
        String vn = version.has("version_number")
                ? version.get("version_number").getAsString() : "";
        if (vn.isBlank()) return false;
        String n = vn.startsWith("v") || vn.startsWith("V") ? vn.substring(1) : vn;
        try {
            int major = Integer.parseInt(n.split("[.+\\-]")[0]);
            return major >= 16;
        } catch (NumberFormatException e) {
            return n.startsWith("16.") || n.startsWith("17.");
        }
    }

    /**
     * 兼容旧调用：无 versionId 时按非隔离路径 {@code mods/<gameVersion>/} 安装。
     */
    public void install(String gameVersion, String loader,
                        Consumer<String> onProgress) throws IOException {
        install(null, gameVersion, loader, onProgress);
    }

    /**
     * 卸载 MetalRender 及其依赖：从目标 mods 目录删除相关 jar。
     * <p>
     * 同时清理历史错误路径 {@code ~/.pmcl/mods/} 根目录中的残留（旧版安装器写入处）。
     *
     * @param versionId   本地版本/实例 ID，可为 null
     * @param gameVersion Minecraft 版本号，可为 null
     * @return 已删除的文件列表
     * @throws IOException 删除失败
     */
    public List<String> uninstall(String versionId, String gameVersion) throws IOException {
        Set<String> deleted = new LinkedHashSet<>();
        for (Path modsDir : candidateModsDirs(versionId, gameVersion)) {
            deleted.addAll(uninstallFrom(modsDir));
        }
        return new ArrayList<>(deleted);
    }

    /** 兼容旧调用：仅清理 workDir/mods 根目录。 */
    public List<String> uninstall() throws IOException {
        return uninstall(null, null);
    }

    /**
     * 检查 MetalRender 是否已安装到目标 mods 目录（含历史根目录残留）。
     */
    public boolean isInstalled(String versionId, String gameVersion) {
        for (Path modsDir : candidateModsDirs(versionId, gameVersion)) {
            if (hasMetalRenderJar(modsDir)) return true;
        }
        return false;
    }

    /** 兼容旧调用：扫描 workDir/mods 根目录。 */
    public boolean isInstalled() {
        return isInstalled(null, null);
    }

    // ─── 内部实现 ───────────────────────────────────────────────

    private Path downloadVersionFile(String projectId, JsonObject version, Path modsDir)
            throws IOException {
        String fileUrl = null;
        String fileName = null;
        String sha1 = "";
        String sha512 = "";
        if (version.has("files")) {
            JsonArray files = version.getAsJsonArray("files");
            JsonObject chosen = null;
            for (JsonElement fe : files) {
                JsonObject fo = fe.getAsJsonObject();
                if (fo.has("primary") && fo.get("primary").getAsBoolean()) {
                    chosen = fo;
                    break;
                }
            }
            if (chosen == null) {
                for (JsonElement fe : files) {
                    JsonObject fo = fe.getAsJsonObject();
                    String fn = fo.has("filename") ? fo.get("filename").getAsString() : "";
                    if (fn.endsWith(".jar") && !fn.contains("-sources") && !fn.contains("-dev")) {
                        chosen = fo;
                        break;
                    }
                }
            }
            if (chosen != null) {
                fileUrl = chosen.has("url") ? chosen.get("url").getAsString() : null;
                fileName = chosen.has("filename") ? chosen.get("filename").getAsString() : null;
                if (chosen.has("hashes") && chosen.get("hashes").isJsonObject()) {
                    JsonObject h = chosen.getAsJsonObject("hashes");
                    sha1 = h.has("sha1") ? h.get("sha1").getAsString() : "";
                    sha512 = h.has("sha512") ? h.get("sha512").getAsString() : "";
                }
            }
        }
        if (fileUrl == null || fileName == null) {
            throw new IOException("Modrinth 返回的 " + projectId + " 版本无可用文件");
        }
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")
                || fileName.indexOf('\0') >= 0 || fileName.isBlank()) {
            throw new IOException("非法 MetalRender 文件名: " + fileName);
        }
        if ((sha1 == null || sha1.isBlank()) && (sha512 == null || sha512.isBlank())) {
            throw new IOException("Modrinth 未提供 " + fileName + " 的哈希，拒绝安装未校验的 MetalRender 组件");
        }
        Path modsAbs = modsDir.toAbsolutePath().normalize();
        Path target = modsAbs.resolve(fileName).normalize();
        if (!target.startsWith(modsAbs)) {
            throw new IOException("MetalRender 路径越界: " + fileName);
        }
        // 先清掉同模组旧 jar（不同文件名会并存，Fabric 会报重复/冲突）
        removeConflictingJars(modsAbs, projectId, fileName);
        downloads.downloadToVerified(fileUrl, target, sha1, sha512);
        return target;
    }

    /**
     * 删除 mods 目录中与 {@code projectId} 同模组、但文件名不同于 {@code keepFileName} 的 jar。
     * 例如已有 modmenu-15.0.0.jar 再装 15.0.2 时，避免两个 Mod Menu 并存。
     */
    private static void removeConflictingJars(Path modsDir, String projectId, String keepFileName)
            throws IOException {
        if (!Files.isDirectory(modsDir)) return;
        String keep = keepFileName != null ? keepFileName.toLowerCase(Locale.ROOT) : "";
        try (var stream = Files.list(modsDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString();
                if (name.equalsIgnoreCase(keep)) continue;
                if (isSameModJar(name, projectId)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** 按 Modrinth slug/id 判断文件是否属于同一模组（避免误伤 sodium-extra 等）。 */
    private static boolean isSameModJar(String fileName, String projectId) {
        if (fileName == null || projectId == null) return false;
        String n = fileName.toLowerCase(Locale.ROOT);
        if (!n.endsWith(".jar") && !n.endsWith(".jar.disabled")) return false;
        String id = projectId.toLowerCase(Locale.ROOT);
        return switch (id) {
            case "modmenu", "mogut4gm" -> n.startsWith("modmenu-");
            case "fabric-api", "p7dr8msh" -> n.startsWith("fabric-api-");
            case "metalrender" -> n.startsWith("metalrender-") || n.contains("metalrender");
            case "sodium", "aanobbmi" ->
                    n.startsWith("sodium-") && !n.startsWith("sodium-extra");
            default -> false;
        };
    }

    private static void rollback(List<Path> downloaded) {
        for (Path p : downloaded) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // 尽力回滚
            }
        }
    }

    private List<String> uninstallFrom(Path modsDir) throws IOException {
        List<String> deleted = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) return deleted;
        try (var stream = Files.list(modsDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                for (Pattern pattern : UNINSTALL_PATTERNS) {
                    if (pattern.matcher(name).matches()) {
                        Files.deleteIfExists(file);
                        deleted.add(file.getFileName().toString());
                        break;
                    }
                }
            }
        }
        return deleted;
    }

    private static boolean hasMetalRenderJar(Path modsDir) {
        if (!Files.isDirectory(modsDir)) return false;
        try (var stream = Files.list(modsDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.contains("metalrender")
                        && (name.endsWith(".jar") || name.endsWith(".jar.disabled"))) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // 读取失败视为未安装
        }
        return false;
    }

    /**
     * mods 目录解析：与 {@link com.pmcl.core.launch.LaunchProfileBuilder} 的 gameDir/mods 对齐，
     * 确保游戏能加载（尤其是外部 .minecraft 整合包：versions/&lt;id&gt;/mods/）。
     * <ul>
     *   <li>版本隔离：{@code instances/<versionId>/mods/}</li>
     *   <li>整合包（版本目录含 mods/）：该目录下的 mods/</li>
     *   <li>普通版本：版本所属 mcRoot/mods/（PMCL 或 ~/.minecraft）</li>
     * </ul>
     */
    Path resolveModsDir(String versionId, String gameVersion) {
        if (preferences != null && preferences.isVersionIsolation()
                && versionId != null && !versionId.isEmpty()) {
            InstanceManager.requireSafeInstanceId(versionId);
            Path instancesRoot = config.getWorkDir().resolve("instances").toAbsolutePath().normalize();
            Path instanceDir = instancesRoot.resolve(versionId).normalize();
            if (!instanceDir.startsWith(instancesRoot)) {
                throw new IllegalArgumentException("versionId path escapes instances dir: " + versionId);
            }
            return instanceDir.resolve("mods");
        }

        if (versionId != null && !versionId.isEmpty()) {
            Path jsonPath = findVersionJson(versionId);
            if (jsonPath != null) {
                Path versionDir = jsonPath.getParent();
                // 整合包：版本目录内已有 mods/，启动时 gameDir=versionDir
                if (Files.isDirectory(versionDir.resolve("mods"))) {
                    return versionDir.resolve("mods");
                }
                // 普通版本：gameDir=mcRoot → mods 在 mcRoot/mods
                Path versionsDir = versionDir.getParent();
                if (versionsDir != null) {
                    Path mcRoot = versionsDir.getParent();
                    if (mcRoot != null) {
                        return mcRoot.resolve("mods");
                    }
                }
            }
        }

        // 回退：PMCL 工作目录 mods/
        return config.getWorkDir().resolve("mods");
    }

    /**
     * 在 PMCL versions 与系统 Minecraft versions 目录中查找版本 JSON。
     */
    private Path findVersionJson(String versionId) {
        if (versionId == null || versionId.isEmpty()
                || versionId.contains("..") || versionId.contains("/")
                || versionId.contains("\\") || versionId.indexOf('\0') >= 0) {
            return null;
        }
        List<Path> dirs = new ArrayList<>();
        Path pmclVersions = config.getVersionsDir();
        if (pmclVersions != null) {
            dirs.add(pmclVersions);
        }
        for (Path d : VersionManager.detectAllMinecraftVersionsDirs()) {
            if (!dirs.contains(d)) {
                dirs.add(d);
            }
        }
        for (Path dir : dirs) {
            Path jsonPath = dir.resolve(versionId).resolve(versionId + ".json");
            if (Files.exists(jsonPath)) {
                return jsonPath;
            }
        }
        return null;
    }

    /**
     * 目标目录 + 历史残留路径（旧安装器写过 workDir/mods；曾短暂写入 mods/&lt;gv&gt;/）。
     */
    private List<Path> candidateModsDirs(String versionId, String gameVersion) {
        List<Path> dirs = new ArrayList<>();
        try {
            Path primary = resolveModsDir(versionId, gameVersion);
            dirs.add(primary);
        } catch (IllegalArgumentException ignored) {
            // 非法 id 时仍尝试清理其它候选
        }
        Path legacyRoot = config.getWorkDir().resolve("mods");
        if (!dirs.contains(legacyRoot)) {
            dirs.add(legacyRoot);
        }
        // 外部 mcRoot/mods（非隔离、非整合包时的正确路径；隔离/整合包时作为残留清理）
        if (versionId != null && !versionId.isEmpty()) {
            Path jsonPath = findVersionJson(versionId);
            if (jsonPath != null) {
                Path versionDir = jsonPath.getParent();
                Path versionMods = versionDir.resolve("mods");
                if (!dirs.contains(versionMods)) {
                    dirs.add(versionMods);
                }
                Path versionsDir = versionDir.getParent();
                if (versionsDir != null) {
                    Path mcRoot = versionsDir.getParent();
                    if (mcRoot != null) {
                        Path mcMods = mcRoot.resolve("mods");
                        if (!dirs.contains(mcMods)) {
                            dirs.add(mcMods);
                        }
                    }
                }
            }
        }
        if (gameVersion != null && !gameVersion.isEmpty()) {
            try {
                InstanceManager.requireSafeInstanceId(gameVersion);
                Path versioned = legacyRoot.resolve(gameVersion).normalize();
                if (versioned.startsWith(legacyRoot.toAbsolutePath().normalize())
                        && !dirs.contains(versioned)) {
                    dirs.add(versioned);
                }
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
        return dirs;
    }
}
