package com.pmcl.music.source;

import java.util.Map;

/**
 * 直链音频源：作为兜底实现，直接使用用户输入的 URL 作为音频流。
 *
 * <p>{@link #matches(String)} 始终返回 true，作为 {@link AudioSourceResolver} 的最后一道兜底。
 */
public class DirectAudioSource implements AudioSource {

    private static final String TYPE = "direct";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean matches(String url) {
        // 兜底：始终返回 true
        return true;
    }

    @Override
    public AudioStreamInfo resolve(String url) {
        String title = extractTitle(url);
        return new AudioStreamInfo(
                title,
                "",
                0L,
                url,
                "",
                TYPE,
                url,
                Map.of(),
                ""
        );
    }

    /** 取 URL 最后一段路径作为标题 */
    private static String extractTitle(String url) {
        if (url == null || url.isBlank()) return "";
        // 去掉 query 和 fragment
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        int f = path.indexOf('#');
        if (f >= 0) path = path.substring(0, f);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? url : name;
    }
}
