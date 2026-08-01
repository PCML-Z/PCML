package com.lash.pmcl.core.version

/**
 * 表示一个 Minecraft 版本元信息。
 */
data class McVersion(
    val id: String,
    val type: String,       // release / snapshot / old_beta
    val releaseTime: String,
    val url: String,        // version manifest url
    /** Mojang version.json 的 SHA-1（来自 version_manifest）；空表示未知 */
    val sha1: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is McVersion) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "McVersion{$id ($type)}"
}
