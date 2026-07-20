package com.pmcl.music.source;

import java.io.IOException;

/**
 * 音频源解析接口：根据用户输入的 URL 解析出可直接播放的音频流信息。
 *
 * <p>实现类需提供：
 * <ul>
 *   <li>{@link #type()} - 返回来源类型标识（"bilibili" / "acfun" / "direct"）</li>
 *   <li>{@link #matches(String)} - 严格判断 URL 是否匹配本来源</li>
 *   <li>{@link #resolve(String)} - 解析 URL，返回可直接播放的音频流信息</li>
 * </ul>
 */
public interface AudioSource {

    /** 来源类型标识，如 "bilibili" / "acfun" / "direct" */
    String type();

    /** 严格判断 URL 是否匹配本来源（兜底实现可返回 true） */
    boolean matches(String url);

    /** 解析 URL，返回可直接播放的音频流信息 */
    AudioStreamInfo resolve(String url) throws IOException;
}
