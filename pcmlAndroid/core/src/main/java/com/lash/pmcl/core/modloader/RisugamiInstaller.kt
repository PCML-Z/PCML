package com.lash.pmcl.core.modloader

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.install.InstallInterruptedException
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionInstaller
import com.lash.pmcl.core.install.VersionStaging
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.Exceptions
import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Risugami's ModLoader 安装器：从 MCArchive 下载 ModLoader zip，合并进 client jar，并去掉 META-INF。
 */
class RisugamiInstaller(
    private val paths: PmclPaths,
    private val downloads: DownloadManager,
    private val versionInstaller: VersionInstaller?
) : ModLoaderInstaller {

    override fun listVersions(gameVersion: String): CompletableFuture<List<ModLoaderVersion>> {
        return CompletableFuture.supplyAsync {
            val out = ArrayList<ModLoaderVersion>()
            if (SHA_BY_GAME.containsKey(gameVersion)) {
                out.add(ModLoaderVersion(ModLoader.RISUGAMI, gameVersion, gameVersion, true))
            }
            out
        }
    }

    override fun install(
        gameVersion: String,
        loaderVersion: String,
        onProgress: Consumer<InstallProgress>?
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            val id = "$gameVersion-ModLoader"
            try {
                val sha = SHA_BY_GAME[gameVersion]
                    ?: throw IllegalArgumentException(
                        "Risugami's ModLoader 无此游戏版本的归档: $gameVersion"
                    )
                VersionStaging.assertSafeVersionId(id)
                ParentVersionSupport.ensureParentInstalled(
                    paths, versionInstaller, gameVersion, onProgress
                )

                val parentDir = paths.versions.resolve(gameVersion)
                val parentJar = parentDir.resolve("$gameVersion.jar")
                val parentJson = parentDir.resolve("$gameVersion.json")
                if (!Files.isRegularFile(parentJar) || !Files.isRegularFile(parentJson)) {
                    throw IOException("原版文件缺失: $gameVersion")
                }

                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1,
                        "下载 ModLoader $gameVersion"
                    )
                )
                val zip = paths.cache.resolve("modloader-$gameVersion.zip")
                val fileName = "ModLoader%20" + gameVersion.replace(" ", "%20") + ".zip"
                // b1.7.3 等文件名带大写 B
                val altName = "ModLoader%20" + capitalizeBeta(gameVersion) + ".zip"
                ParentVersionSupport.downloadFirstOk(
                    downloads, zip,
                    MCARCHIVE + sha + "/" + fileName,
                    MCARCHIVE + sha + "/" + altName,
                    MCARCHIVE + sha + "/ModLoader%20" + gameVersion + ".zip"
                )

                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "合并 ModLoader 到 client jar"
                    )
                )

                val staging = VersionStaging.stagingDir(paths.versions, id)
                Files.createDirectories(staging)
                val outJar = staging.resolve("$id.jar")
                mergeJarmod(parentJar, zip, outJar)

                val jsonText = FileUtils.readString(parentJson, StandardCharsets.UTF_8)
                val root = JsonParser.parseString(jsonText).asJsonObject
                root.addProperty("id", id)
                // 独立 jar，去掉继承以免再用原版 jar
                root.remove("inheritsFrom")
                if (root.has("downloads")) {
                    root.getAsJsonObject("downloads").remove("client")
                }
                FileUtils.writeString(staging.resolve("$id.json"), root.toString(), StandardCharsets.UTF_8)
                VersionStaging.promote(paths.versions, id, staging)

                onProgress?.accept(
                    InstallProgress(InstallProgress.Stage.DONE, 1, 1, "ModLoader 安装完成: $id")
                )
            } catch (e: Exception) {
                if (!InstallInterruptedException.isInterrupted(e)) {
                    VersionStaging.discard(paths.versions, id)
                }
                val detail = Exceptions.rootMessage(e)
                onProgress?.accept(
                    InstallProgress(InstallProgress.Stage.FAILED, 0, 0, detail)
                )
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw if (e is RuntimeException) e
                    else InstallInterruptedException("ModLoader 安装已中断", e)
                }
                throw RuntimeException("ModLoader 安装失败: $detail", e)
            }
        }
    }

    private fun capitalizeBeta(gameVersion: String): String {
        if (gameVersion.startsWith("b") || gameVersion.startsWith("a")) {
            return gameVersion.substring(0, 1).uppercase(Locale.ROOT) + gameVersion.substring(1)
        }
        return gameVersion
    }

    /**
     * 将 ModLoader zip 中的 class 合并进 client jar，并删除 META-INF 签名文件。
     */
    @Throws(IOException::class)
    private fun mergeJarmod(clientJar: Path, modloaderZip: Path, outJar: Path) {
        val tmp = outJar.resolveSibling(outJar.fileName.toString() + ".tmp")
        Files.createDirectories(outJar.parent)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipFile(clientJar.toFile()).use { base ->
            val en = base.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                val name = e.name
                if (e.isDirectory) continue
                if (name.startsWith("META-INF/") || name == "META-INF") continue
                base.getInputStream(e).use { inp ->
                    entries[name] = inp.readBytes()
                }
            }
        }
        Files.newInputStream(modloaderZip).use { fin ->
            ZipInputStream(fin).use { zis ->
                var e: ZipEntry?
                while (zis.nextEntry.also { e = it } != null) {
                    if (e!!.isDirectory) continue
                    val name = e!!.name
                    if (name.startsWith("META-INF/") || name == "META-INF") continue
                    entries[name] = zis.readBytes()
                    zis.closeEntry()
                }
            }
        }
        ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
            for ((key, value) in entries) {
                zos.putNextEntry(ZipEntry(key))
                zos.write(value)
                zos.closeEntry()
            }
        }
        Files.move(tmp, outJar, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val MCARCHIVE = "https://b2.mcarchive.net/file/mcarchive/"

        /** gameVersion → sha256（MCArchive） */
        private val SHA_BY_GAME: Map<String, String> = linkedMapOf(
            "1.6.2" to "0b14f5e261c9862989aa74313b59188cce10bea6724bae31130ce1e8e6a1c060",
            "1.6.1" to "95fc5afdd9cc14d85cb41225fb689d7994f5994287ed9595e192026c06e7b536",
            "1.5.2" to "0c355696c2f3ba405bb1f0f845dc51a6613c121eac25a6c7bc9d8046f2c941df",
            "1.5.1" to "af7d7bca70b8bc08c75e96ec90a25432682dfc825aa4fe35485dcb390b1f7014",
            "1.5" to "597d4d437a250986da84a9c7aee3ea653739608caf1d4a208f2006d8cbdfbc3d",
            "1.4.7" to "685ead73c19531cf24062c7536737663421ed4170cfa582baddbbf6cba1544d2",
            "1.4.6" to "f69b1f99b76c23cc1e076197375996e3b79feb369952ac692630f7b063709d5f",
            "1.4.5" to "885b62bde6231b04d0189a06b082edfa48ea1474f22a5502ab40288563036b42",
            "1.4.4" to "7d39b6d5e41bcd77edabd0aca3b43a10861a65ee9c2f9b358cedf8382d69c14e",
            "1.4.2" to "861324b55c40e4af622e2a987c3c20ed4eb869ea89a004c93222058e394baec4",
            "1.3.2" to "01a28a0a3d05634ce8745d34738b0617ddb285ad1584fb668874892c61e489eb",
            "1.3.1" to "511881d7432cf740b753180a645ca6abb7cd63d09813e0089485c125d52c09a0",
            "1.2.5" to "219370a86a15bfef8ff91f51fdd151e99391b771759183b19f72197452a28b79",
            "1.0.0" to "0abd012bcfd536522d50ac642080d6164cd6cdc22629386a0e8e1fafa2e7cd99",
            "1.1" to "b56d925adc210773e4b2390f8189e16456910da4bcd4276b492bb382ea04f079",
            "b1.7.3" to "78bc1107a2ae78334d1086c7f372601c141b53345f23ce73931ef318df5cf83e",
            "b1.8.1" to "4135de0b0fddf6f9b39761a5261b82dae278b311237ec1cd936911b0b133919e",
            "b1.7.2" to "2b4e0e19b817a464ef32042a12f3ba1d8e4db25a01a1bb19efe8a5d9713a003c",
            "b1.6.6" to "15262e652abcf8b925909e867821575cf25a17bc8217dbc281a20ce166a3f6b9"
        )
    }
}
