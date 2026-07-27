package com.pmcl.plugin.api

/**
 * Multiplayer room control (EasyTier / ConnectX / Terracotta backends via host).
 * Mutating methods require [com.pmcl.plugin.PluginPermission.CONTROL_ROOMS].
 */
interface RoomsApi {
    fun state(): RoomStateSummary

    fun createRoom()

    fun joinRoom(invitationCode: String)

    fun leaveRoom()

    /** Invitation / room code when currently in a room; null otherwise. */
    fun invitation(): String?

    fun virtualIp(): String?
}
