package com.lash.pmcl.core.install

import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 版本目录暂存 / 原子提升工具。
 * <p>
 * 安装过程中写入 `versions/{id}.staging/`，成功后再提升为 `versions/{id}/`，
 * 避免半成品被 VersionManager 扫描为可启动版本。
 */
object VersionStaging {
    const val STAGING_SUFFIX = ".staging"
    const val BAK_SUFFIX = ".bak"

    /** 是否为暂存或回滚备份目录名。 */
    fun isTransientDirName(name: String?): Boolean {
        return name != null && (name.endsWith(STAGING_SUFFIX) || name.endsWith(BAK_SUFFIX))
    }

    /**
     * 拒绝路径穿越式版本 id（远程 profile.id / 用户输入）。
     */
    @Throws(IOException::class)
    fun assertSafeVersionId(versionId: String?) {
        if (versionId.isNullOrBlank()) {
            throw IOException("版本 id 为空，拒绝写入")
        }
        if (versionId.indexOf('\u0000') >= 0
            || versionId.contains("/")
            || versionId.contains("\\")
            || versionId.contains("..")
            || versionId == "."
            || versionId == ".."
        ) {
            throw IOException("非法版本 id（含路径穿越字符）: $versionId")
        }
    }

    @Throws(IOException::class)
    fun stagingDir(versionsDir: Path, versionId: String): Path {
        assertSafeVersionId(versionId)
        val base = versionsDir.toAbsolutePath().normalize()
        val staging = base.resolve(versionId + STAGING_SUFFIX).normalize()
        if (!staging.startsWith(base)) {
            throw IOException("版本 staging 路径越界: $versionId")
        }
        return staging
    }

    /**
     * 将版本 JSON 写入 staging 目录（文件名仍为 `{id}.json`）。
     *
     * @return staging 目录路径
     */
    @Throws(IOException::class)
    fun writeVersionJson(versionsDir: Path, versionId: String, jsonContent: String): Path {
        val staging = stagingDir(versionsDir, versionId)
        Files.createDirectories(staging)
        val jsonFile = staging.resolve("$versionId.json").normalize()
        if (!jsonFile.startsWith(staging.toAbsolutePath().normalize())) {
            throw IOException("版本 JSON 路径越界: $versionId")
        }
        FileUtils.writeString(jsonFile, jsonContent, StandardCharsets.UTF_8)
        return staging
    }

    /**
     * 将 staging 目录提升为正式版本目录。
     * 若正式目录已存在，先移到 `.bak`，成功后再删除备份。
     */
    @Throws(IOException::class)
    fun promote(versionsDir: Path, versionId: String, stagingDir: Path) {
        assertSafeVersionId(versionId)
        val base = versionsDir.toAbsolutePath().normalize()
        val target = base.resolve(versionId).normalize()
        if (!target.startsWith(base)) {
            throw IOException("版本目录路径越界: $versionId")
        }
        // 已有正式目录 → 先备份
        val bak = base.resolve(versionId + BAK_SUFFIX).normalize()
        if (Files.exists(target)) {
            if (Files.exists(bak)) {
                FileUtils.deleteRecursively(bak)
            }
            Files.move(target, bak, StandardCopyOption.REPLACE_EXISTING)
        }
        try {
            Files.move(stagingDir, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // 提升失败：尝试回滚
            try {
                if (Files.exists(bak)) {
                    Files.move(bak, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (rollbackErr: Exception) {
                e.addSuppressed(rollbackErr)
                System.err.println("[VersionStaging] 回滚也失败: $versionId — ${rollbackErr.message}")
            }
            throw IOException("提升 staging 目录失败: $versionId", e)
        }
        // 成功后删除备份（失败不影响主流程）
        try {
            if (Files.exists(bak)) {
                FileUtils.deleteRecursively(bak)
            }
        } catch (e: IOException) {
            System.err.println("[VersionStaging] 删除备份失败: ${e.message}")
        }
    }

    /** 回滚到备份（若存在） */
    @Throws(IOException::class)
    fun rollback(versionsDir: Path, versionId: String) {
        assertSafeVersionId(versionId)
        val base = versionsDir.toAbsolutePath().normalize()
        val target = base.resolve(versionId).normalize()
        val bak = base.resolve(versionId + BAK_SUFFIX).normalize()
        if (!Files.exists(bak)) return
        if (Files.exists(target)) {
            FileUtils.deleteRecursively(target)
        }
        Files.move(bak, target, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 删除 staging 目录（安装失败时清理） */
    @Throws(IOException::class)
    fun discard(versionsDir: Path, versionId: String) {
        assertSafeVersionId(versionId)
        val base = versionsDir.toAbsolutePath().normalize()
        val staging = base.resolve(versionId + STAGING_SUFFIX).normalize()
        val bak = base.resolve(versionId + BAK_SUFFIX).normalize()
        if (Files.exists(staging)) FileUtils.deleteRecursively(staging)
        if (Files.exists(bak)) FileUtils.deleteRecursively(bak)
    }
}
