package com.lash.pmcl.core.modloader

/**
 * 一个模组加载器的可用版本。
 */
data class ModLoaderVersion(
    val loader: ModLoader,
    /** MC 版本，如 "1.20.4" */
    val gameVersion: String,
    /** 加载器版本，如 "0.15.7"（fabric）或 "47.2.0"（forge） */
    val loaderVersion: String,
    val stable: Boolean
) {
    override fun toString(): String = "$loader $loaderVersion (MC $gameVersion)"
}
