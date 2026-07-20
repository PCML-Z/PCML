package com.pmcl.music.player;

/**
 * 播放器事件监听器接口。
 *
 * <p>所有方法均有默认实现（空），监听器只需重写关注的方法。
 */
public interface MusicPlayerListener {

    /** 播放状态变化 */
    default void onStateChanged(PlaybackState state) {}

    /** 播放进度回调（currentMs 当前位置，durationMs 总时长，未知为 0） */
    default void onProgress(long currentMs, long durationMs) {}

    /** 播放出错 */
    default void onError(String message) {}

    /** 当前曲目播放结束 */
    default void onTrackEnded() {}
}
