package com.pmcl.plugin.api

/**
 * Favorite Minecraft servers + ping.
 * Mutating methods require [com.pmcl.plugin.PluginPermission.MANAGE_SERVERS].
 * Ping requires [com.pmcl.plugin.PluginPermission.NETWORK].
 */
interface ServersApi {
    fun listFavorites(): List<ServerSummary>

    fun addFavorite(name: String, host: String, port: Int = 25565)

    fun removeFavorite(index: Int)

    fun updateFavorite(index: Int, name: String, host: String, port: Int)

    /** Full status ping (MOTD / players / version). */
    fun ping(host: String, port: Int = 25565): ServerPingResult

    fun getDirectConnectHost(): String

    fun getDirectConnectPort(): Int

    fun setDirectConnect(host: String, port: Int)
}
