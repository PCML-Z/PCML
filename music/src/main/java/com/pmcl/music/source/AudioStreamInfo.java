package com.pmcl.music.source;

import java.util.Map;

/**
 * 音频流信息数据类。
 *
 * <p>由 {@link AudioSource#resolve(String)} 返回，描述一个可直接播放的音频流，
 * 包含元数据（标题/UP主/时长/封面）和播放所需的 URL、HTTP 头等。
 */
public final class AudioStreamInfo {

    /** 标题 */
    public final String title;
    /** UP主/作者 */
    public final String uploader;
    /** 时长毫秒（未知传 0） */
    public final long durationMs;
    /** 可直接播放的音频流 URL */
    public final String audioUrl;
    /** 封面图 URL（可空） */
    public final String coverUrl;
    /** 来源类型标识 */
    public final String sourceType;
    /** 原始用户输入 URL */
    public final String sourceUrl;
    /** 播放时需要带的 HTTP 请求头（如 Referer） */
    public final Map<String, String> headers;
    /** 视频 ID（BV号/ac号等） */
    public final String originalId;

    /** 全字段构造器 */
    public AudioStreamInfo(String title,
                           String uploader,
                           long durationMs,
                           String audioUrl,
                           String coverUrl,
                           String sourceType,
                           String sourceUrl,
                           Map<String, String> headers,
                           String originalId) {
        this.title = title;
        this.uploader = uploader;
        this.durationMs = durationMs;
        this.audioUrl = audioUrl;
        this.coverUrl = coverUrl;
        this.sourceType = sourceType;
        this.sourceUrl = sourceUrl;
        this.headers = headers;
        this.originalId = originalId;
    }
}
