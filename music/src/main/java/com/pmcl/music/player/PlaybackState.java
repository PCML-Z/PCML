package com.pmcl.music.player;

/**
 * 播放器状态枚举。
 */
public enum PlaybackState {
    /** 空闲（初始/停止后） */
    IDLE,
    /** 加载中（正在打开流） */
    LOADING,
    /** 播放中 */
    PLAYING,
    /** 已暂停 */
    PAUSED,
    /** 已停止 */
    STOPPED,
    /** 出错 */
    ERROR,
    /** 播放结束 */
    ENDED
}
