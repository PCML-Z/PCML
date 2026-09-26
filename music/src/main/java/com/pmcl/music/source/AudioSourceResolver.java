package com.pmcl.music.source;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 音频源解析器：本地 → B站 → A站 →（可选）快手 / 抖音 / 油管 → 直链。
 * 可选来源默认关闭，由设置打开后才解析；关掉时不会把页面地址当成音频直链。
 */
public class AudioSourceResolver {

    private static final long RESOLUTION_TTL_MS = 10L * 60L * 1000L;
    private static final int MAX_CACHE_ENTRIES = 64;

    private final List<AudioSource> sources;
    private final DirectAudioSource direct = new DirectAudioSource();
    private final KuaishouAudioSource kuaishou = new KuaishouAudioSource();
    private final DouyinAudioSource douyin = new DouyinAudioSource();
    private final YoutubeAudioSource youtube = new YoutubeAudioSource();

    private volatile boolean kuaishouEnabled;
    private volatile boolean douyinEnabled;
    private volatile boolean youtubeEnabled;
    private final Map<String, CachedResolution> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> resolveLocks = new ConcurrentHashMap<>();

    public AudioSourceResolver() {
        sources = List.of(
                new LocalAudioSource(),
                new BilibiliAudioSource(),
                new AcFunAudioSource()
        );
    }

    /** 快手、抖音、油管默认关闭。播放或添加链接前按设置同步。 */
    public void setOptionalSources(boolean kuaishou, boolean douyin, boolean youtube) {
        this.kuaishouEnabled = kuaishou;
        this.douyinEnabled = douyin;
        this.youtubeEnabled = youtube;
    }

    public AudioStreamInfo resolve(String url) throws IOException {
        return resolveInternal(url, false);
    }

    /** 忽略内存中的临时播放地址，重新向来源站点解析。 */
    public AudioStreamInfo resolveFresh(String url) throws IOException {
        return resolveInternal(url, true);
    }

    /** 播放地址失效时清掉对应条目，下次播放会重新解析。 */
    public void invalidate(String url) {
        if (url != null) cache.remove(url.trim());
    }

    private AudioStreamInfo resolveInternal(String url, boolean fresh) throws IOException {
        AudioSource selected = selectSource(url);
        if (selected instanceof LocalAudioSource) return resolveSource(selected, url);

        String key = url == null ? "" : url.trim();
        long now = System.currentTimeMillis();
        if (!fresh) {
            AudioStreamInfo hit = cached(key, now);
            if (hit != null) return hit;
        }

        Object lock = resolveLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                if (!fresh) {
                    AudioStreamInfo hit = cached(key, System.currentTimeMillis());
                    if (hit != null) return hit;
                }
                AudioStreamInfo resolved = resolveSource(selected, url);
                cache.put(key, new CachedResolution(resolved, System.currentTimeMillis()));
                trimCache();
                return resolved;
            }
        } finally {
            resolveLocks.remove(key, lock);
        }
    }

    private AudioSource selectSource(String url) throws IOException {
        for (AudioSource s : sources) {
            if (s.matches(url)) return s;
        }
        AudioSource optional = optionalSource(url);
        if (optional != null) return optional;
        return direct;
    }

    private AudioStreamInfo resolveSource(AudioSource source, String url) throws IOException {
        try {
            return source.resolve(url);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private AudioSource optionalSource(String url) throws IOException {
        if (kuaishou.matches(url)) {
            if (!kuaishouEnabled) throw new IOException("快手解析未开启，请在设置中打开");
            return kuaishou;
        }
        if (douyin.matches(url)) {
            if (!douyinEnabled) throw new IOException("抖音解析未开启，请在设置中打开");
            return douyin;
        }
        if (youtube.matches(url)) {
            if (!youtubeEnabled) throw new IOException("油管解析未开启，请在设置中打开");
            return youtube;
        }
        return null;
    }

    private AudioStreamInfo cached(String key, long now) {
        CachedResolution hit = cache.get(key);
        if (hit == null) return null;
        long age = now - hit.savedAt;
        if (age >= 0 && age < RESOLUTION_TTL_MS) return hit.info;
        cache.remove(key, hit);
        return null;
    }

    private void trimCache() {
        if (cache.size() <= MAX_CACHE_ENTRIES) return;
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, CachedResolution> entry : cache.entrySet()) {
            if (entry.getValue().savedAt < oldest) {
                oldest = entry.getValue().savedAt;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) cache.remove(oldestKey);
    }

    private static final class CachedResolution {
        final AudioStreamInfo info;
        final long savedAt;

        CachedResolution(AudioStreamInfo info, long savedAt) {
            this.info = info;
            this.savedAt = savedAt;
        }
    }
}
