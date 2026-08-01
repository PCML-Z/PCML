package com.lash.pmcl.core.market

import java.util.ArrayList
import java.util.Collections

/**
 * Mod 文件信息（版本/下载件）。
 *
 * Android 版本：从 Java 移植，纯数据类，无平台依赖。
 */
data class ModFile(
    val source: String,
    val projectId: String,
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val downloadUrl: String,
    private val gameVersions: List<String>,
    private val loaders: List<String>,
    val releaseType: String,
    private val dependencies: List<String> = emptyList()
) {
    private var sha1: String? = null
    private var sha512: String? = null

    fun hashes(sha1: String?, sha512: String?): ModFile {
        this.sha1 = sha1
        this.sha512 = sha512
        return this
    }

    fun getSha1(): String? = sha1
    fun getSha512(): String? = sha512

    fun getGameVersions(): List<String> = ArrayList(gameVersions)
    fun getLoaders(): List<String> = ArrayList(loaders)
    fun getDependencies(): List<String> = ArrayList(dependencies)

    companion object {
        fun createWithDeps(
            source: String, projectId: String, fileId: String,
            fileName: String, fileSize: Long, downloadUrl: String,
            gameVersions: List<String>, loaders: List<String>,
            releaseType: String, dependencies: List<String>
        ): ModFile = ModFile(
            source, projectId, fileId, fileName, fileSize, downloadUrl,
            Collections.unmodifiableList(ArrayList(gameVersions)),
            Collections.unmodifiableList(ArrayList(loaders)),
            releaseType,
            Collections.unmodifiableList(ArrayList(dependencies))
        )
    }
}
