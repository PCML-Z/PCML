package com.pmcl.music.source;

import java.io.IOException;
import java.util.List;

/**
 * 音频源解析器：按优先级尝试 B站 → A站 → 直链，失败时回退到直链。
 *
 * <p>{@link DirectAudioSource} 的 {@code matches()} 始终返回 true，因此作为兜底，
 * B站/A站解析失败时会自动回退到直链播放。
 */
public class AudioSourceResolver {

    private final List<AudioSource> sources;

    public AudioSourceResolver() {
        // 顺序：B站优先 → A站 → 直链兜底
        sources = List.of(
                new BilibiliAudioSource(),
                new AcFunAudioSource(),
                new DirectAudioSource()
        );
    }

    /**
     * 解析 URL，返回可直接播放的音频流信息。
     * 优先尝试 B站/A站，匹配且解析成功则返回；
     * 解析失败时回退到下一个 source，最终由直链兜底。
     */
    public AudioStreamInfo resolve(String url) throws IOException {
        for (AudioSource s : sources) {
            if (s.matches(url)) {
                try {
                    return s.resolve(url);
                } catch (IOException e) {
                    // 当前 source 解析失败，尝试下一个
                    continue;
                }
            }
        }
        // 兜底：直接调用最后一个 source（直链）
        return sources.get(sources.size() - 1).resolve(url);
    }
}
