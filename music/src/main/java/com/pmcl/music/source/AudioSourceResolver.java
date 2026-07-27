package com.pmcl.music.source;

import java.io.IOException;
import java.util.List;

/**
 * 音频源解析器：本地 → B站 → A站 → 直链。
 * B站/A站匹配失败时不再错误回退为「把页面当音频直链」。
 */
public class AudioSourceResolver {

    private final List<AudioSource> sources;
    private final DirectAudioSource direct = new DirectAudioSource();

    public AudioSourceResolver() {
        sources = List.of(
                new LocalAudioSource(),
                new BilibiliAudioSource(),
                new AcFunAudioSource()
        );
    }

    public AudioStreamInfo resolve(String url) throws IOException {
        IOException last = null;
        for (AudioSource s : sources) {
            if (!s.matches(url)) continue;
            try {
                return s.resolve(url);
            } catch (IOException e) {
                last = e;
                // 已匹配的专用源失败则直接抛出，避免误走直链
                throw e;
            } catch (RuntimeException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        try {
            return direct.resolve(url);
        } catch (RuntimeException e) {
            if (last != null) throw last;
            throw new IOException(e.getMessage(), e);
        }
    }
}
