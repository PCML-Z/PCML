package com.lash.pmcl.ui.screens

import java.util.concurrent.CopyOnWriteArrayList

/** 音乐播放器全局状态，供 MusicScreen 写入、MiniMusicBar 读取 */
object MusicState {
    var currentTrack: String = ""
    var isPlaying: Boolean = false
    var currentMs: Int = 0
    var durationMs: Int = 0

    fun reset() {
        currentTrack = ""; isPlaying = false; currentMs = 0; durationMs = 0
    }
}
