package com.pmcl.core.install;

import com.pmcl.core.util.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 版本目录暂存 / 原子提升工具。
 * <p>
 * 安装过程中写入 {@code versions/{id}.staging/}，成功后再提升为 {@code versions/{id}/}，
 * 避免半成品被 {@link com.pmcl.core.version.VersionManager} 扫描为可启动版本。
 */
public final class VersionStaging {

    public static final String STAGING_SUFFIX = ".staging";
    public static final String BAK_SUFFIX = ".bak";

    private VersionStaging() {}

    /** 是否为暂存或回滚备份目录名。 */
    public static boolean isTransientDirName(String name) {
        return name != null && (name.endsWith(STAGING_SUFFIX) || name.endsWith(BAK_SUFFIX));
    }

    /**
     * 拒绝路径穿越式版本 id（远程 profile.id / 用户输入）。
     */
    public static void assertSafeVersionId(String versionId) throws IOException {
        if (versionId == null || versionId.isBlank()) {
            throw new IOException("版本 id 为空，拒绝写入");
        }
        if (versionId.indexOf('\0') >= 0
                || versionId.contains("/")
                || versionId.contains("\\")
                || versionId.contains("..")
                || versionId.equals(".")
                || versionId.equals("..")) {
            throw new IOException("非法版本 id（含路径穿越字符）: " + versionId);
        }
    }

    public static Path stagingDir(Path versionsDir, String versionId) throws IOException {
        assertSafeVersionId(versionId);
        Path base = versionsDir.toAbsolutePath().normalize();
        Path staging = base.resolve(versionId + STAGING_SUFFIX).normalize();
        if (!staging.startsWith(base)) {
            throw new IOException("版本 staging 路径越界: " + versionId);
        }
        return staging;
    }

    /**
     * 将版本 JSON 写入 staging 目录（文件名仍为 {@code {id}.json}）。
     *
     * @return staging 目录路径
     */
    public static Path writeVersionJson(Path versionsDir, String versionId, String jsonContent)
            throws IOException {
        Path staging = stagingDir(versionsDir, versionId);
        Files.createDirectories(staging);
        Path jsonFile = staging.resolve(versionId + ".json").normalize();
        if (!jsonFile.startsWith(staging.toAbsolutePath().normalize())) {
            throw new IOException("版本 JSON 路径越界: " + versionId);
        }
        Files.writeString(jsonFile, jsonContent, StandardCharsets.UTF_8);
        return staging;
    }

    /**
     * 将 staging 目录提升为正式版本目录。
     * 若正式目录已存在，先移到 {@code .bak}，成功后再删除备份。
     */
    public static void promote(Path versionsDir, String versionId, Path stagingDir)
            throws IOException {
        assertSafeVersionId(versionId);
        Path base = versionsDir.toAbsolutePath().normalize();
        Path finalDir = base.resolve(versionId).normalize();
        Path bakDir = base.resolve(versionId + BAK_SUFFIX).normalize();
        if (!finalDir.startsWith(base) || !bakDir.startsWith(base)) {
            throw new IOException("版本目录路径越界: " + versionId);
        }
        FileUtils.deleteRecursively(bakDir);

        if (Files.exists(finalDir)) {
            try {
                Files.move(finalDir, bakDir,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(finalDir, bakDir, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        try {
            try {
                Files.move(stagingDir, finalDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(stagingDir, finalDir);
            }
        } catch (IOException e) {
            if (Files.exists(bakDir) && !Files.exists(finalDir)) {
                try {
                    Files.move(bakDir, finalDir);
                } catch (IOException restoreErr) {
                    e.addSuppressed(restoreErr);
                }
            }
            throw new IOException("无法将安装暂存目录提升为正式版本: " + versionId, e);
        }
        FileUtils.deleteRecursively(bakDir);
    }

    /** 删除指定版本的 staging 目录（失败清理用）。 */
    public static void discard(Path versionsDir, String versionId) {
        if (versionId == null || versionId.isBlank()) return;
        try {
            FileUtils.deleteRecursively(stagingDir(versionsDir, versionId));
        } catch (IOException e) {
            // 非法 id：忽略，避免取消路径因坏 id 再抛错
        }
    }
}
