package com.pmcl.core.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.market.ModrinthClient;
import com.pmcl.core.preferences.Preferences;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 拖放安装器：解析拖入的 mod jar + SHA1 反查 Modrinth + 拷贝到目标 mods 目录。
 * <p>
 * 流程：
 * <ol>
 *   <li>{@link #analyze(List)}：对每个 jar 调用 {@link ModScanner#parseJar(Path)} 拿元数据，
 *       计算 SHA1，批量调用 {@link ModrinthClient#batchCheckBySha1(List)} 反查
 *       game_versions / loaders，返回 {@link ModDropInfo} 列表</li>
 *   <li>{@link #installTo(ModDropInfo, String, String)}：拷贝 jar 到目标 mods 目录
 *       （版本隔离 → instances/&lt;versionId&gt;/mods/，否则 mods/&lt;gameVersion&gt;/）</li>
 * </ol>
 * <p>
 * 设计：所有 IO 在调用方线程执行（同步方法），让 UI 决定是否切到 IO 协程。
 * 网络失败时不抛异常，降级为 modrinthFound=false，UI 仍允许用户手动选择版本。
 */
public final class ModDropInstaller {

    private final LauncherConfig config;
    private final ModrinthClient modrinth;

    public ModDropInstaller(LauncherConfig config, ModrinthClient modrinth) {
        this.config = config;
        this.modrinth = modrinth;
    }

    /**
     * 批量解析拖入的 jar 文件。
     * <p>
     * 对每个 jar：
     * <ol>
     *   <li>ModScanner.parseJar 拿 modId/name/version/loader</li>
     *   <li>计算文件 SHA1</li>
     * </ol>
     * 然后一次性批量调用 Modrinth batchCheckBySha1 反查所有 jar 的兼容版本信息。
     *
     * @param jarPaths 拖入的 jar 文件路径列表
     * @return 解析结果列表（顺序与输入一致），解析失败的 jar 也会返回带 parseError 的项
     */
    public List<ModDropInfo> analyze(List<Path> jarPaths) {
        if (jarPaths == null || jarPaths.isEmpty()) return Collections.emptyList();

        // 第一阶段：解析 jar 元数据 + 计算 SHA1
        List<ModMeta> metas = new ArrayList<>(jarPaths.size());
        List<String> sha1s = new ArrayList<>(jarPaths.size());
        List<String> parseErrors = new ArrayList<>(jarPaths.size());
        for (Path p : jarPaths) {
            ModMeta meta;
            String err = null;
            try {
                meta = ModScanner.parseJar(p);
            } catch (Throwable t) {
                meta = null;
                err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            metas.add(meta);
            parseErrors.add(err);
            String sha1 = null;
            try {
                sha1 = computeSha1(p);
            } catch (IOException ignored) {
                // SHA1 计算失败：仍允许安装，只是无法反查 Modrinth
            }
            sha1s.add(sha1);
        }

        // 第二阶段：批量反查 Modrinth（仅对有 SHA1 的 jar）
        Map<String, JsonObject> modrinthMap = Collections.emptyMap();
        if (modrinth != null) {
            List<String> nonNullSha1s = new ArrayList<>();
            for (String s : sha1s) {
                if (s != null && !s.isEmpty() && !nonNullSha1s.contains(s)) {
                    nonNullSha1s.add(s);
                }
            }
            if (!nonNullSha1s.isEmpty()) {
                try {
                    modrinthMap = modrinth.batchCheckBySha1(nonNullSha1s);
                } catch (Throwable ignored) {
                    // 网络失败：降级为 modrinthFound=false
                    modrinthMap = Collections.emptyMap();
                }
            }
        }

        // 第三阶段：组装 ModDropInfo
        List<ModDropInfo> result = new ArrayList<>(jarPaths.size());
        for (int i = 0; i < jarPaths.size(); i++) {
            Path jarPath = jarPaths.get(i);
            ModMeta meta = metas.get(i);
            String sha1 = sha1s.get(i);
            String err = parseErrors.get(i);

            String modId, name, version, loader, authors, description;
            if (meta != null) {
                modId = meta.getModId();
                name = meta.getName();
                version = meta.getVersion();
                loader = meta.getLoader();
                authors = meta.getAuthors();
                description = meta.getDescription();
            } else {
                modId = "";
                name = jarPath.getFileName().toString();
                version = "";
                loader = "unknown";
                authors = "";
                description = "";
            }

            List<String> gameVersions = Collections.emptyList();
            List<String> loaders = Collections.emptyList();
            boolean found = false;
            if (sha1 != null && modrinthMap.containsKey(sha1)) {
                JsonObject v = modrinthMap.get(sha1);
                if (v != null) {
                    gameVersions = jsonArrToStrings(v, "game_versions");
                    loaders = jsonArrToStrings(v, "loaders");
                    found = !gameVersions.isEmpty() || !loaders.isEmpty();
                }
            }
            result.add(new ModDropInfo(modId, name, version, loader, authors, description,
                    jarPath, sha1, gameVersions, loaders, found, err));
        }
        return result;
    }

    /**
     * 把已解析的 mod jar 拷贝到目标 mods 目录。
     * <p>
     * 路径推导：
     * <ul>
     *   <li>版本隔离开启且 versionId 非空：{@code ~/.pmcl/instances/<versionId>/mods/}</li>
     *   <li>否则：{@code ~/.pmcl/mods/<gameVersion>/}（gameVersion 为空则直接 mods/）</li>
     * </ul>
     * 同名文件存在时覆盖（让用户能拖入新版 jar 更新）。
     *
     * @param info        已解析的 mod 信息
     * @param versionId   目标版本 ID（用于版本隔离），可为 null
     * @param gameVersion 目标 MC 版本号（如 "1.20.1"），可为 null
     * @return 拷贝目标路径
     * @throws IOException 拷贝失败
     */
    public Path installTo(ModDropInfo info, String versionId, String gameVersion) throws IOException {
        Path modsDir;
        if (preferences != null && preferences.isVersionIsolation()
                && versionId != null && !versionId.isEmpty()) {
            modsDir = config.getWorkDir().resolve("instances").resolve(versionId).resolve("mods");
        } else {
            modsDir = config.getWorkDir().resolve("mods");
            if (gameVersion != null && !gameVersion.isEmpty()) {
                modsDir = modsDir.resolve(gameVersion);
            }
        }
        Files.createDirectories(modsDir);
        Path target = modsDir.resolve(info.getJarPath().getFileName());
        Files.copy(info.getJarPath(), target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** 可选注入的 Preferences，用于判断版本隔离开关 */
    private Preferences preferences;

    public void setPreferences(Preferences prefs) {
        this.preferences = prefs;
    }

    // ==================== 辅助 ====================

    /** 计算文件 SHA1（hex 小写） */
    private static String computeSha1(Path path) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (Exception e) {
            return null;
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static List<String> jsonArrToStrings(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return Collections.emptyList();
        JsonArray arr = o.getAsJsonArray(key);
        List<String> list = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive()) list.add(e.getAsString());
        }
        return list;
    }
}
