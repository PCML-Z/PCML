package com.pmcl.plugin.api

/**
 * Host music transport controls (implemented by UI bridge, not core).
 * Mutating methods require [com.pmcl.plugin.PluginPermission.CONTROL_MUSIC].
 * [nowPlaying] is free.
 */
interface MusicApi {
    fun nowPlaying(): MusicPlaybackSummary

    fun pause()

    fun resume()

    fun stop()

    fun playNext()

    fun playPrevious()

    fun setVolume(volume: Int)
}
