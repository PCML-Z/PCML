package com.lash.pmcl.core.launch

import com.google.gson.JsonParser
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.preferences.Preferences
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 与桌面 [com.pmcl.core.launch.GameDirResolver] 对齐：
 * 开版本隔离时把共享目录里的 mods/config 灌进 instances/，
 * 已经带 mods/ 的整合包版本目录不搬。
 */
class GameDirResolver(
    private val paths: PmclPaths,
    private val preferences: Preferences?
) {
    fun resolveGameDir(versionId: String): Path {
        requireSafeVersionId(versionId)
        val jsonPath = findVersionJson(versionId)
        val versionDir = jsonPath?.parent
        val mcRoot = versionDir?.parent?.parent ?: paths.minecraftWorkDir

        if (versionDir != null && isSelfContained(versionDir)) {
            return versionDir
        }
        if (preferences?.isVersionIsolation() == true) {
            val instanceDir = isolatedDir(versionId)
            ensureSubdirs(instanceDir)
            seedIsolatedDir(instanceDir, versionDir, mcRoot, versionId)
            return instanceDir
        }
        return mcRoot
    }

    fun resolveModsDir(versionId: String): Path = resolveGameDir(versionId).resolve("mods")

    companion object {
        private val GAME_SUBDIRS = arrayOf(
            "mods", "saves", "config", "resourcepacks", "shaderpacks", "screenshots", "logs"
        )
        private val SEED_SUBDIRS = arrayOf("mods", "config", "resourcepacks", "shaderpacks")
        private const val SEEDED_MARKER = ".pmcl-isolation-seeded"

        fun requireSafeVersionId(versionId: String) {
            if (versionId.isBlank() || versionId.contains("..") || versionId.contains("/")
                || versionId.contains("\\") || versionId.indexOf('\u0000') >= 0
            ) {
                throw IllegalArgumentException("非法版本 ID: $versionId")
            }
        }

        fun isSelfContained(versionDir: Path): Boolean =
            Files.isDirectory(versionDir.resolve("mods"))
                    || Files.exists(versionDir.resolve("instance.json"))
                    || Files.exists(versionDir.resolve("modpack.json"))
    }

    private fun isolatedDir(versionId: String): Path {
        val root = paths.instances.toAbsolutePath().normalize()
        val dir = root.resolve(versionId).normalize()
        if (!dir.startsWith(root)) {
            throw IllegalArgumentException("versionId path escapes instances dir: $versionId")
        }
        return dir
    }

    private fun ensureSubdirs(gameDir: Path) {
        Files.createDirectories(gameDir)
        for (sub in GAME_SUBDIRS) {
            Files.createDirectories(gameDir.resolve(sub))
        }
    }

    private fun seedIsolatedDir(instanceDir: Path, versionDir: Path?, mcRoot: Path, versionId: String) {
        val marker = instanceDir.resolve(SEEDED_MARKER)
        if (Files.exists(marker)) return
        val inheritsFrom = readInheritsFrom(versionDir, versionId)
        try {
            for (sub in SEED_SUBDIRS) {
                if (sub == "mods") {
                    seedMods(instanceDir.resolve("mods"), versionDir, mcRoot, versionId, inheritsFrom)
                } else {
                    for (srcRoot in seedRoots(versionDir, mcRoot)) {
                        copyTreeIfAbsent(srcRoot.resolve(sub), instanceDir.resolve(sub))
                    }
                }
            }
            Files.writeString(marker, "1", StandardCharsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("[GameDirResolver] 灌入隔离目录失败 $instanceDir: ${e.message}")
        }
    }

    private fun seedRoots(versionDir: Path?, mcRoot: Path): List<Path> {
        val roots = LinkedHashSet<Path>()
        if (versionDir != null) roots.add(versionDir)
        roots.add(mcRoot)
        roots.add(paths.minecraftWorkDir)
        return roots.toList()
    }

    private fun seedMods(
        destMods: Path, versionDir: Path?, mcRoot: Path,
        versionId: String, inheritsFrom: String?
    ) {
        Files.createDirectories(destMods)
        val keys = LinkedHashSet<String>()
        if (versionId.isNotBlank()) keys.add(versionId)
        if (!inheritsFrom.isNullOrBlank()) keys.add(inheritsFrom)
        for (srcRoot in seedRoots(versionDir, mcRoot)) {
            val srcMods = srcRoot.resolve("mods")
            if (!Files.isDirectory(srcMods)) continue
            copyLooseFiles(srcMods, destMods)
            val srcAbs = srcMods.toAbsolutePath().normalize()
            for (key in keys) {
                val versioned = srcMods.resolve(key).normalize()
                if (Files.isDirectory(versioned) && versioned.startsWith(srcAbs)) {
                    copyTreeIfAbsent(versioned, destMods)
                }
            }
        }
    }

    private fun findVersionJson(versionId: String): Path? {
        val jsonPath = paths.versions.resolve(versionId).resolve("$versionId.json")
        return if (Files.exists(jsonPath)) jsonPath else null
    }

    private fun readInheritsFrom(versionDir: Path?, versionId: String): String? {
        if (versionDir == null) return null
        val json = versionDir.resolve("$versionId.json")
        if (!Files.isRegularFile(json)) return null
        return try {
            val root = JsonParser.parseString(Files.readString(json)).asJsonObject
            if (root.has("inheritsFrom") && !root.get("inheritsFrom").isJsonNull) {
                root.get("inheritsFrom").asString?.takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) {
            null
        }
    }
}

private fun copyLooseFiles(srcDir: Path, destDir: Path) {
    if (!Files.isDirectory(srcDir)) return
    Files.createDirectories(destDir)
    val destAbs = destDir.toAbsolutePath().normalize()
    Files.list(srcDir).use { stream ->
        stream.forEach { src ->
            if (!Files.isRegularFile(src) || Files.isSymbolicLink(src)) return@forEach
            val dest = destAbs.resolve(src.fileName.toString()).normalize()
            if (dest.startsWith(destAbs) && !Files.exists(dest)) {
                Files.copy(src, dest, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}

private fun copyTreeIfAbsent(source: Path, target: Path) {
    if (!Files.isDirectory(source)) return
    Files.createDirectories(target)
    val srcAbs = source.toAbsolutePath().normalize()
    val dstAbs = target.toAbsolutePath().normalize()
    Files.walk(source).use { walk ->
        walk.forEach { src ->
            if (src == source) return@forEach
            val rel = srcAbs.relativize(src.toAbsolutePath().normalize())
            val dst = dstAbs.resolve(rel).normalize()
            if (!dst.startsWith(dstAbs)) return@forEach
            if (Files.isDirectory(src)) {
                Files.createDirectories(dst)
            } else if (!Files.exists(dst) && !Files.isSymbolicLink(src)) {
                Files.createDirectories(dst.parent)
                Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}
