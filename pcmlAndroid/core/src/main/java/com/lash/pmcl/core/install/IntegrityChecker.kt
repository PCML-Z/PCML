package com.lash.pmcl.core.install

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 启动前完整性校验：根据版本 JSON 校验 client.jar 与所有 libraries 的 SHA1。
 * <p>
 * 缺失或哈希不匹配的文件会被收集到 [Result] 中，UI 可提示用户重新下载。
 *
 * Android 版本：通过 [PmclPaths] 提供路径，无 LauncherConfig 依赖。
 */
class IntegrityChecker(private val paths: PmclPaths) {

    class Result {
        val missing: MutableList<String> = ArrayList()
        val hashMismatch: MutableList<String> = ArrayList()
        val ok: MutableList<String> = ArrayList()
        /** 存在但无法哈希校验（无 sha1 / maven 仅路径） */
        val unverifiable: MutableList<String> = ArrayList()

        val isOk: Boolean get() = missing.isEmpty() && hashMismatch.isEmpty()
        val issueCount: Int get() = missing.size + hashMismatch.size
    }

    /**
     * 校验指定版本。
     */
    @Throws(IOException::class)
    fun check(versionId: String): Result {
        val result = Result()
        val versionDir = paths.versions.resolve(versionId)
        val versionJson = versionDir.resolve("$versionId.json")
        if (!Files.exists(versionJson)) {
            result.missing.add("versions/$versionId/$versionId.json")
            return result
        }

        val root = JsonParser.parseString(
            FileUtils.readString(versionJson, StandardCharsets.UTF_8)
        ).asJsonObject

        // client.jar
        if (root.has("downloads")) {
            val dl = root.getAsJsonObject("downloads")
            if (dl.has("client")) {
                val client = dl.getAsJsonObject("client")
                val clientJar = versionDir.resolve("$versionId.jar")
                if (client.has("sha1") && !client.get("sha1").isJsonNull) {
                    verifyFile(clientJar, client.get("sha1").asString, result)
                } else if (Files.exists(clientJar)) {
                    result.unverifiable.add("$clientJar (client.jar 无 sha1)")
                } else {
                    result.missing.add(clientJar.toString())
                }
            }
        }

        // libraries
        if (root.has("libraries")) {
            for (e in root.getAsJsonArray("libraries")) {
                val lib = e.asJsonObject
                if (!lib.has("downloads")) {
                    // Fabric/Forge/NeoForge 第三方库格式（只有顶层 name + url，无 downloads/sha1）
                    if (lib.has("name") && !lib.get("name").isJsonNull) {
                        val parsed = Library.parse(lib)
                        val path = parsed.getPath()
                        if (path.isNotEmpty()) {
                            val libFile = paths.libraries.resolve(path)
                            if (!Files.exists(libFile)) {
                                result.missing.add(libFile.toString())
                            } else {
                                result.unverifiable.add("$libFile (无 sha1)")
                            }
                        }
                    }
                    continue
                }
                val downloads = lib.getAsJsonObject("downloads")
                if (downloads.has("artifact")) {
                    val art = downloads.getAsJsonObject("artifact")
                    if (art.has("path") && !art.get("path").isJsonNull) {
                        val libFile = paths.libraries.resolve(art.get("path").asString)
                        if (art.has("sha1") && !art.get("sha1").isJsonNull) {
                            verifyFile(libFile, art.get("sha1").asString, result)
                        } else if (Files.exists(libFile)) {
                            result.unverifiable.add("$libFile (artifact 无 sha1)")
                        } else {
                            result.missing.add(libFile.toString())
                        }
                    }
                }
                // native classifiers
                if (downloads.has("classifiers")) {
                    val cl = downloads.getAsJsonObject("classifiers")
                    for ((_, ceVal) in cl.entrySet()) {
                        val a = ceVal.asJsonObject
                        if (!a.has("path") || a.get("path").isJsonNull) continue
                        val libFile = paths.libraries.resolve(a.get("path").asString)
                        if (a.has("sha1") && !a.get("sha1").isJsonNull) {
                            verifyFile(libFile, a.get("sha1").asString, result)
                        } else if (Files.exists(libFile)) {
                            result.unverifiable.add("$libFile (classifier 无 sha1)")
                        } else {
                            result.missing.add(libFile.toString())
                        }
                    }
                }
            }
        }

        // assets
        checkAssets(root, result)

        return result
    }

    private fun checkAssets(root: JsonObject, result: Result) {
        if (!root.has("assetIndex") || root.get("assetIndex").isJsonNull) return
        val ai = root.getAsJsonObject("assetIndex")
        val id = if (ai.has("id") && !ai.get("id").isJsonNull) ai.get("id").asString else null
        if (id.isNullOrBlank()) return
        val indexPath = paths.assets.resolve("indexes").resolve("$id.json")
        if (ai.has("sha1") && !ai.get("sha1").isJsonNull) {
            verifyFile(indexPath, ai.get("sha1").asString, result)
        } else if (Files.exists(indexPath)) {
            result.unverifiable.add("$indexPath (assetIndex 无 sha1)")
        } else {
            result.missing.add(indexPath.toString())
            return
        }
        if (!Files.exists(indexPath)) return
        try {
            val idx = JsonParser.parseString(
                FileUtils.readString(indexPath, StandardCharsets.UTF_8)
            ).asJsonObject
            if (!idx.has("objects")) return
            val objects = idx.getAsJsonObject("objects")
            val objectsDir = paths.assets.resolve("objects")
            for ((key, eVal) in objects.entrySet()) {
                val obj = eVal.asJsonObject
                if (!obj.has("hash") || obj.get("hash").isJsonNull) {
                    result.unverifiable.add("assets object $key (无 hash)")
                    continue
                }
                val hash = obj.get("hash").asString
                if (!hash.matches(Regex("[0-9a-fA-F]{40}"))) {
                    result.unverifiable.add("assets object $key (非法 hash)")
                    continue
                }
                val file = objectsDir.resolve(hash.substring(0, 2)).resolve(hash).normalize()
                if (!file.startsWith(objectsDir.toAbsolutePath().normalize())) {
                    result.hashMismatch.add("assets object $key (路径越界)")
                    continue
                }
                verifyFile(file, hash, result)
            }
        } catch (ex: Exception) {
            result.hashMismatch.add("$indexPath (解析失败: ${ex.message})")
        }
    }

    private fun verifyFile(file: Path, expectedSha1: String, result: Result) {
        if (!Files.exists(file)) {
            result.missing.add(file.toString())
            return
        }
        try {
            val actual = sha1(file)
            if (!actual.equals(expectedSha1, ignoreCase = true)) {
                result.hashMismatch.add("$file (期望=$expectedSha1 实际=$actual)")
            } else {
                result.ok.add(file.toString())
            }
        } catch (e: IOException) {
            result.hashMismatch.add("$file (计算哈希失败: ${e.message})")
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun sha1(file: Path): String {
            return try {
                val md = MessageDigest.getInstance("SHA-1")
                Files.newInputStream(file).use { inp ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = inp.read(buf)
                        if (n == -1) break
                        md.update(buf, 0, n)
                    }
                }
                val digest = md.digest()
                val sb = StringBuilder(digest.size * 2)
                // H13: b and 0xff 防止 byte 符号扩展为 int 时产生 ffffffff 而非 ff
                for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xff))
                sb.toString()
            } catch (e: Exception) {
                throw IOException("SHA1 计算失败", e)
            }
        }
    }
}
