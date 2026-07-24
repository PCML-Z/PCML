package com.pmcl.core.metal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.market.ModrinthClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * MetalRender 自动安装器：从 Modrinth 下载 MetalRender 及其依赖到 mods 目录。
 * <p>
 * MetalRender 是一个 Fabric mod，使用 Apple Metal API 替换 Sodium 的渲染后端，
 * 仅在 Apple Silicon Mac (M1+) 上生效。
 * <p>
 * 依赖链：MetalRender → Sodium → Fabric API；推荐安装 ModMenu 用于配置界面。
 * <p>
 * 项目 ID（Modrinth slug）：
 * <ul>
 *   <li>MetalRender: metalrender</li>
 *   <li>Sodium: AANobbMI</li>
 *   <li>Fabric API: P7dR8mSH</li>
 *   <li>ModMenu: m5HZN3Zi</li>
 * </ul>
 */
public final class MetalRenderInstaller {

    /** Modrinth 项目 slug 列表（安装顺序：依赖优先） */
    private static final String[] PROJECT_IDS = {
            "P7dR8mSH",   // Fabric API（基础依赖）
            "AANobbMI",   // Sodium（MetalRender 的前置）
            "m5HZN3Zi",   // ModMenu（配置界面）
            "metalrender" // MetalRender 本体
    };

    /** mods 目录下已安装 mod 的文件名前缀，用于卸载时识别 */
    private static final String[] FILENAME_KEYWORDS = {
            "fabric-api", "sodium", "modmenu", "metalrender"
    };

    private final LauncherConfig config;
    private final ModrinthClient modrinth;
    private final DownloadManager downloads;

    public MetalRenderInstaller(LauncherConfig config, ModrinthClient modrinth, DownloadManager downloads) {
        this.config = config;
        this.modrinth = modrinth;
        this.downloads = downloads;
    }

    /**
     * 检测当前是否为 Apple Silicon Mac。
     * <p>
     * 优先用 sysctl 检测真实硬件架构（Rosetta 2 下 os.arch 不可靠）。
     *
     * @return true 表示当前是 Apple Silicon Mac
     */
    public static boolean isAppleSiliconMac() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("mac")) return false;

        // 优先用 sysctl 检测（Rosetta 2 下 os.arch 会被骗成 x86_64）
        Process p = null;
        try {
            p = new ProcessBuilder("sysctl", "-n", "hw.optional.arm64").start();
            try (var in = p.getInputStream()) {
                String out = new String(in.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return "1".equals(out);
                }
            }
        } catch (Exception ignored) {
            // sysctl 失败则回退到 os.arch
        } finally {
            if (p != null) p.destroyForcibly();
        }
        return "aarch64".equals(System.getProperty("os.arch", ""));
    }

    /**
     * 安装 MetalRender 及其依赖到 mods 目录。
     * <p>
     * 逐个从 Modrinth 获取最新兼容版本并下载。已存在同名文件则覆盖。
     *
     * @param gameVersion Minecraft 版本（如 "1.21.8"），用于过滤兼容的 mod 版本
     * @param loader      加载器（通常为 "fabric"）
     * @param onProgress  进度回调（当前 mod 名称），可为 null
     * @throws IOException 下载或写入失败
     */
    public void install(String gameVersion, String loader,
                        Consumer<String> onProgress) throws IOException {
        Path modsDir = config.getWorkDir().resolve("mods");
        Files.createDirectories(modsDir);

        for (String projectId : PROJECT_IDS) {
            if (onProgress != null) {
                onProgress.accept(projectId);
            }
            JsonObject version = modrinth.getLatestVersion(projectId, gameVersion, loader);
            if (version == null) {
                throw new IOException("Modrinth 上未找到 " + projectId
                        + " 兼容 " + gameVersion + "/" + loader + " 的版本");
            }
            // 取第一个 file（主 jar）
            String fileUrl = null;
            String fileName = null;
            if (version.has("files")) {
                JsonArray files = version.getAsJsonArray("files");
                for (JsonElement fe : files) {
                    JsonObject fo = fe.getAsJsonObject();
                    fileUrl = fo.has("url") ? fo.get("url").getAsString() : null;
                    fileName = fo.has("filename") ? fo.get("filename").getAsString() : null;
                    if (fileUrl != null && fileName != null) break;
                }
            }
            if (fileUrl == null || fileName == null) {
                throw new IOException("Modrinth 返回的 " + projectId + " 版本无可用文件");
            }
            Path target = modsDir.resolve(fileName);
            // 已存在则跳过（避免重复下载）
            if (Files.exists(target)) {
                if (onProgress != null) {
                    onProgress.accept("skip:" + fileName);
                }
                continue;
            }
            downloads.downloadTo(fileUrl, target);
            if (onProgress != null) {
                onProgress.accept("done:" + fileName);
            }
        }
    }

    /**
     * 卸载 MetalRender 及其依赖：从 mods 目录删除相关 jar 文件。
     * <p>
     * 通过文件名关键词匹配（fabric-api / sodium / modmenu / metalrender）。
     * 仅删除根目录下的匹配文件，不递归子目录。
     *
     * @return 已删除的文件列表
     * @throws IOException 删除失败
     */
    public List<String> uninstall() throws IOException {
        List<String> deleted = new ArrayList<>();
        Path modsDir = config.getWorkDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) return deleted;

        try (var stream = Files.list(modsDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                String name = file.getFileName().toString().toLowerCase();
                if (!name.endsWith(".jar") && !name.endsWith(".jar.disabled")) continue;
                for (String keyword : FILENAME_KEYWORDS) {
                    if (name.contains(keyword)) {
                        Files.deleteIfExists(file);
                        deleted.add(file.getFileName().toString());
                        break;
                    }
                }
            }
        }
        return deleted;
    }

    /**
     * 检查 MetalRender 是否已安装（mods 目录中存在 metalrender jar）。
     */
    public boolean isInstalled() {
        Path modsDir = config.getWorkDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) return false;
        try (var stream = Files.list(modsDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                String name = file.getFileName().toString().toLowerCase();
                if (name.contains("metalrender") && (name.endsWith(".jar"))) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // 读取 mods 目录失败，视为未安装
        }
        return false;
    }
}
