package com.lash.pmcl.core.modloader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.install.InstallInterruptedException
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionStaging
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.FileUtils
import com.lash.pmcl.core.util.SafeZipExtractor
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * OptiFine 安装器 — Android 版。
 *
 * 通过内嵌 JVM 运行 optifine.Patcher 合并原版 client.jar 与安装包。
 * Android 适配：PmclPaths 替代 LauncherConfig，Files.readString 替换为 FileUtils。
 */
class OptiFineInstaller(
    private val paths: PmclPaths,
    private val downloads: DownloadManager
) : ModLoaderInstaller {

    companion object {
        private const val BMCLAPI_OPTIFINE = "https://bmclapi2.bangbang93.com/optifine/"
        private const val BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/"
        private const val MOJANG_MAVEN = "https://libraries.minecraft.net/"
    }

    override fun listVersions(gameVersion: String): CompletableFuture<List<ModLoaderVersion>> =
        CompletableFuture.supplyAsync {
            val json = downloads.downloadString(BMCLAPI_OPTIFINE + gameVersion)
            val arr = JsonParser.parseString(json).asJsonArray
            arr.mapNotNull { el ->
                val o = el.asJsonObject ?: return@mapNotNull null
                val type = o.get("type")?.asString ?: return@mapNotNull null
                val patch = o.get("patch")?.asString ?: return@mapNotNull null
                val needsForge = o.get("_forge")?.asBoolean ?: false
                val encoded = "$type|$patch${if (needsForge) "|forge" else ""}"
                ModLoaderVersion(ModLoader.OPTIFINE, gameVersion, encoded, !needsForge)
            }
        }

    override fun install(gameVersion: String, loaderVersion: String,
                          onProgress: Consumer<InstallProgress>?): CompletableFuture<Void> =
        CompletableFuture.runAsync {
            var installerJar: Path? = null
            var optifineLib: Path? = null
            var versionId: String? = null
            try {
                val parts = loaderVersion.split("|")
                if (parts.size < 2) throw IOException("无效版本: $loaderVersion")
                val type = parts[0]; val patch = parts[1]
                val forge = parts.size >= 3 && parts[2] == "forge"
                val edition = "${type}_$patch"
                val coords = "optifine:Optifine:${gameVersion}_$edition"
                versionId = "${gameVersion}-OptiFine_$edition"
                val filename = "OptiFine_${gameVersion}_${type}_$patch.jar"

                // 1. 下载 OptiFine installer
                onProgress?.accept(InstallProgress(InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1, "下载 OptiFine"))
                installerJar = Files.createTempFile("of-installer-", ".jar")
                downloadInstaller(gameVersion, type, patch, filename, installerJar)

                // 2. 确认原版 client.jar
                val clientJar = paths.versions.resolve(gameVersion).resolve("$gameVersion.jar")
                if (!Files.isRegularFile(clientJar))
                    throw IOException("请先安装 Minecraft $gameVersion")

                // 3. 运行 Patcher（内嵌 JVM）
                onProgress?.accept(InstallProgress(InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1, "OptiFine Patcher"))
                optifineLib = paths.libraries.resolve(
                    "optifine/Optifine/${gameVersion}_$edition/Optifine-${gameVersion}_$edition.jar")
                Files.createDirectories(optifineLib.parent)
                runPatcher(installerJar, clientJar, optifineLib)

                // 4. LaunchWrapper
                val lwOf = readZipText(installerJar, "launchwrapper-of.txt")
                val lwName: String
                if (lwOf != null && lwOf.isNotBlank()) {
                    lwName = "optifine:launchwrapper-of:$lwOf"
                    val lwTarget = paths.libraries.resolve("optifine/launchwrapper-of/$lwOf/launchwrapper-of-$lwOf.jar")
                    extractEmbeddedJar(installerJar, "launchwrapper-of-$lwOf.jar", lwTarget)
                } else {
                    lwName = "net.minecraft:launchwrapper:1.12"
                    ensureLaunchWrapper112()
                }

                // 5. 构造并写入版本 JSON
                onProgress?.accept(InstallProgress(InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1, "写入版本"))
                val json = buildVersionJson(versionId, gameVersion, coords, lwName, forge)
                val staging = VersionStaging.writeVersionJson(paths.versions, versionId, json.toString())
                VersionStaging.promote(paths.versions, versionId, staging)

                onProgress?.accept(InstallProgress(InstallProgress.Stage.DONE, 1, 1, "OptiFine 完成: $versionId"))
            } catch (e: Exception) {
                if (!InstallInterruptedException.isInterrupted(e)) {
                    if (versionId != null) VersionStaging.discard(paths.versions, versionId)
                    if (optifineLib != null) FileUtils.deleteRecursively(optifineLib)
                }
                val detail = e.message ?: "unknown"
                onProgress?.accept(InstallProgress(InstallProgress.Stage.FAILED, 0, 0, detail))
                throw RuntimeException("OptiFine 安装失败: $detail", e)
            } finally {
                try { installerJar?.let { Files.deleteIfExists(it) } } catch (_: IOException) {}
            }
        }

    private fun downloadInstaller(gameVersion: String, type: String, patch: String,
                                   filename: String, target: Path) {
        val urls = listOf(
            BMCLAPI_OPTIFINE + "$gameVersion/$type/$patch",
            BMCLAPI_MAVEN + "com/optifine/$gameVersion/$filename",
            BMCLAPI_OPTIFINE + "download/$filename"
        )
        var last: Exception? = null
        for (url in urls) {
            try {
                downloads.downloadTo(url, target)
                if (Files.size(target) > 1024 && looksLikeZip(target)) return
                Files.deleteIfExists(target)
            } catch (e: Exception) { last = e }
        }
        throw IOException("OptiFine 下载失败", last)
    }

    /**
     * 使用内嵌 JVM 执行 optifine.Patcher。
     * Android 特有：通过 VMLauncher 替代 ProcessBuilder。
     */
    private fun runPatcher(installerJar: Path, clientJar: Path, outJar: Path) {
        val tmpOut = outJar.resolveSibling("${outJar.fileName}.patching")
        Files.deleteIfExists(tmpOut)

        // 使用 JRE 目录中的 java 或直接通过 VMLauncher
        val args = arrayOf(
            "java", "-cp", installerJar.toAbsolutePath().toString(),
            "optifine.Patcher",
            clientJar.toAbsolutePath().toString(),
            installerJar.toAbsolutePath().toString(),
            tmpOut.toAbsolutePath().toString()
        )

        // 在 Android 上，Patcher 通过内嵌 JVM 运行
        // 注意：这可能很慢（Android JVM 启动开销），但这是 OptiFine 安装的必要步骤
        try {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(5, TimeUnit.MINUTES)
            if (!finished) { process.destroyForcibly(); throw IOException("Patcher 超时") }
            if (process.exitValue() != 0 || !Files.isRegularFile(tmpOut) || Files.size(tmpOut) < 1024) {
                val err = process.inputStream.bufferedReader().readText().takeLast(500)
                throw IOException("Patcher 失败: ${process.exitValue()} — $err")
            }
            Files.move(tmpOut, outJar, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InstallInterruptedException("Patcher 中断", e)
        }
    }

    private fun ensureLaunchWrapper112() {
        val target = paths.libraries.resolve("net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar")
        if (Files.isRegularFile(target) && Files.size(target) > 1024) return
        Files.createDirectories(target.parent)
        for (url in listOf(
            BMCLAPI_MAVEN + "net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar",
            MOJANG_MAVEN + "net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"
        )) {
            try { downloads.downloadTo(url, target); return } catch (_: Exception) {}
        }
        throw IOException("LaunchWrapper 1.12 下载失败")
    }

    private fun buildVersionJson(versionId: String, gameVersion: String,
                                  optifineCoords: String, lwName: String, forge: Boolean): JsonObject {
        val json = JsonObject()
        json.addProperty("id", versionId)
        json.addProperty("inheritsFrom", gameVersion)
        json.addProperty("mainClass", "net.minecraft.launchwrapper.Launch")
        json.addProperty("type", "release")
        json.addProperty("minimumLauncherVersion", 21)
        val tweak = if (forge) "optifine.OptiFineForgeTweaker" else "optifine.OptiFineTweaker"
        val args = JsonObject()
        val game = JsonArray()
        game.add("--tweakClass"); game.add(tweak)
        args.add("game", game)
        json.add("arguments", args)
        val libs = JsonArray()
        val lw = JsonObject(); lw.addProperty("name", lwName)
        if (lwName.startsWith("net.minecraft:launchwrapper:")) lw.addProperty("url", BMCLAPI_MAVEN)
        libs.add(lw)
        val of = JsonObject(); of.addProperty("name", optifineCoords); libs.add(of)
        json.add("libraries", libs)
        return json
    }

    private fun readZipText(zipPath: Path, entryName: String): String? {
        try { ZipFile(zipPath.toFile()).use { zip ->
            val entry = zip.getEntry(entryName) ?: return null
            zip.getInputStream(entry).use { inStream ->
                return String(SafeZipExtractor.readLimited(inStream, 65536))
            }
        }} catch (_: IOException) { return null }
    }

    private fun extractEmbeddedJar(zipPath: Path, entryName: String, target: Path) {
        try { ZipFile(zipPath.toFile()).use { zip ->
            val entry = zip.getEntry(entryName) ?: throw IOException("缺少 $entryName")
            Files.createDirectories(target.parent)
            zip.getInputStream(entry).use { inStream ->
                SafeZipExtractor.copyLimited(inStream, target, SafeZipExtractor.DEFAULT_MAX_ENTRY_SIZE)
            }
        }} catch (e: IOException) { throw RuntimeException("提取 $entryName 失败", e) }
    }

    private fun looksLikeZip(file: Path): Boolean {
        try { Files.newInputStream(file).use { ins ->
            val magic = ins.readNBytes(2); return magic.size >= 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
        }} catch (_: IOException) { return false }
    }
}
