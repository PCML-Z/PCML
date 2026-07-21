package com.pmcl.music.player;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 音频播放器：基于 FFmpegFrameGrabber 解码 + javax.sound.sampled.SourceDataLine 输出。
 *
 * <p>核心流程：
 * <ol>
 *   <li>{@link #play(String, Map, long)} 在后台守护线程中启动 FFmpegFrameGrabber</li>
 *   <li>设置 HTTP headers / 重连 / analyzeduration 等优化参数</li>
 *   <li>强制 16-bit PCM 输出（{@code AV_SAMPLE_FMT_S16}）</li>
 *   <li>创建 SourceDataLine，按帧抓取 PCM 数据 → 应用音量 → 写入 line</li>
 *   <li>限频回调进度（500ms）</li>
 *   <li>结束/异常时清理资源</li>
 * </ol>
 *
 * <p>线程安全：所有可变状态用 volatile，监听器用 CopyOnWriteArrayList，
 * 播放在专用守护线程中执行。
 */
public class MusicPlayer {

    private final List<MusicPlayerListener> listeners = new CopyOnWriteArrayList<>();

    private volatile FFmpegFrameGrabber grabber;
    private volatile SourceDataLine line;
    private volatile Thread playThread;

    private volatile PlaybackState state = PlaybackState.IDLE;
    private volatile int volume = 80; // 0-100
    private volatile long durationMs;
    private volatile String currentUrl;
    private volatile Map<String, String> currentHeaders;

    private volatile boolean seekRequested;
    private volatile long seekTargetMs;

    /** 进度通知限频：上次通知的墙钟时间戳（ms） */
    private volatile long lastNotifyWallMs;

    // M56+M57: 可复用的帧缓冲区，避免每帧分配新数组造成 GC 压力
    private short[] volumeScratch = new short[0];
    private byte[] pcmBuf = new byte[0];

    // ---------------------------------------------------------------------------
    // 监听器
    // ---------------------------------------------------------------------------

    public void addListener(MusicPlayerListener l) {
        if (l != null) listeners.add(l);
    }

    public void removeListener(MusicPlayerListener l) {
        listeners.remove(l);
    }

    // ---------------------------------------------------------------------------
    // 状态查询
    // ---------------------------------------------------------------------------

    public PlaybackState getState() {
        return state;
    }

    public int getVolume() {
        return volume;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getCurrentMs() {
        FFmpegFrameGrabber g = grabber;
        // FFmpegFrameGrabber.getTimestamp() 返回微秒（μs），转换为毫秒
        return g != null ? g.getTimestamp() / 1000L : 0L;
    }

    // ---------------------------------------------------------------------------
    // 播放控制
    // ---------------------------------------------------------------------------

    /**
     * 开始播放指定 URL。
     *
     * @param url        音频流 URL
     * @param headers    HTTP 请求头（如 Referer / User-Agent），可为 null
     * @param durationMs 时长（ms），未知传 0
     */
    public void play(String url, Map<String, String> headers, long durationMs) {
        // 先清理旧播放
        stop();

        setState(PlaybackState.LOADING);
        currentUrl = url;
        currentHeaders = headers;
        this.durationMs = durationMs;
        lastNotifyWallMs = 0L;

        Thread t = new Thread(this::playbackLoop, "MusicPlayer-Play");
        t.setDaemon(true);
        t.start();
        playThread = t;
    }

    public void pause() {
        if (state == PlaybackState.PLAYING) {
            SourceDataLine l = line;
            if (l != null) {
                try { l.stop(); } catch (Throwable ignored) {}
            }
            setState(PlaybackState.PAUSED);
        }
    }

    public void resume() {
        if (state == PlaybackState.PAUSED) {
            SourceDataLine l = line;
            if (l != null) {
                try { l.start(); } catch (Throwable ignored) {}
            }
            setState(PlaybackState.PLAYING);
        }
    }

    public void stop() {
        Thread t = playThread;
        if (t != null) {
            t.interrupt();
        }
        state = PlaybackState.STOPPED;
        cleanup();
        setState(PlaybackState.IDLE);
        playThread = null;
    }

    /** 异步 seek：由播放线程在下一次循环中执行 grabber.setTimestamp */
    public void seekTo(long ms) {
        seekRequested = true;
        seekTargetMs = ms;
    }

    public void setVolume(int v) {
        this.volume = Math.max(0, Math.min(100, v));
    }

    // ---------------------------------------------------------------------------
    // 播放循环（运行在播放线程中）
    // ---------------------------------------------------------------------------

    private void playbackLoop() {
        String url = currentUrl;
        Map<String, String> headers = currentHeaders;
        try {
            FFmpegFrameGrabber g = new FFmpegFrameGrabber(url);
            // HTTP 请求头
            if (headers != null && !headers.isEmpty()) {
                g.setOption("headers", buildHeaderString(headers));
            }
            // 网络重连 / 优化参数
            g.setOption("reconnect", "1");
            g.setOption("reconnect_streamed", "1");
            g.setOption("reconnect_delay_max", "5");
            g.setOption("analyzeduration", "1000000"); // 1s，加快首帧
            // 强制 16-bit PCM 输出
            g.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
            g.start();
            grabber = g;

            int sampleRate = g.getSampleRate();
            int channels = g.getAudioChannels();
            if (channels <= 0) channels = 2; // 兜底
            if (sampleRate <= 0) sampleRate = 44100;

            AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            int bufferSize = 4096 * channels * 2;
            l.open(format, bufferSize);
            l.start();
            line = l;

            setState(PlaybackState.PLAYING);

            // 主播放循环
            while (!Thread.currentThread().isInterrupted() && state != PlaybackState.STOPPED) {
                // seek 处理
                if (seekRequested) {
                    try {
                        // setTimestamp 接收微秒（μs），seekTargetMs 是毫秒
                        g.setTimestamp(seekTargetMs * 1000L);
                    } catch (Throwable ignored) {}
                    seekRequested = false;
                    // 清空 line 缓冲，避免播放旧数据
                    try { l.flush(); l.start(); } catch (Throwable ignored) {}
                }

                if (state == PlaybackState.PAUSED) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                Frame frame = g.grabSamples();
                if (frame == null) break; // 播放结束
                if (frame.samples == null || frame.samples.length == 0) continue;

                byte[] pcm = frameToPcmBytes(frame, channels);
                if (pcm.length == 0) continue;

                applyVolume(pcm, volume);
                l.write(pcm, 0, pcm.length);

                // 进度回调（限频 500ms）
                // getTimestamp() 返回微秒，转换为毫秒
                long curMs = g.getTimestamp() / 1000L;
                notifyProgressThrottled(curMs, durationMs);
            }

            // 正常结束
            if (state != PlaybackState.STOPPED && state != PlaybackState.ERROR) {
                setState(PlaybackState.ENDED);
                notifyEnded();
            }
        } catch (Throwable e) {
            if (state != PlaybackState.STOPPED) {
                setState(PlaybackState.ERROR);
                notifyError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        } finally {
            cleanup();
        }
    }

    /**
     * 把 Frame.samples（ShortBuffer 数组，每个 channel 一个）合并为交错 16-bit PCM 字节。
     *
     * <p>JavaCV 默认按 planar 输出（每个声道一个 buffer）；
     * 但设置了 AV_SAMPLE_FMT_S16 后实际是交错格式（packed），samples[0] 即为所有样本。
     * 这里同时兼容两种情况。
     *
     * <p>M57 修复：复用 pcmBuf 字节缓冲区，避免每帧分配新数组。
     */
    private byte[] frameToPcmBytes(Frame frame, int channels) {
        Object first = frame.samples[0];
        if (first instanceof ShortBuffer sb) {
            // 单 buffer：视为交错 packed
            if (frame.samples.length == 1) {
                int remaining = sb.remaining();
                int byteLen = remaining * 2;
                // M57: 复用 pcmBuf，按需扩容
                if (pcmBuf.length < byteLen) pcmBuf = new byte[byteLen];
                // 用 ShortBuffer 视图批量写入，避免逐样本 putShort 调用
                ShortBuffer view = ByteBuffer.wrap(pcmBuf, 0, byteLen)
                        .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                view.put(sb);
                return java.util.Arrays.copyOf(pcmBuf, byteLen);
            }
            // 多 buffer：planar，需要交错合并
            int perChannel = sb.remaining();
            int totalSamples = perChannel * frame.samples.length;
            int byteLen = totalSamples * 2;
            // M57: 复用 volumeScratch 和 pcmBuf
            if (volumeScratch.length < totalSamples) volumeScratch = new short[totalSamples];
            if (pcmBuf.length < byteLen) pcmBuf = new byte[byteLen];
            for (int c = 0; c < frame.samples.length; c++) {
                ShortBuffer chBuf = (ShortBuffer) frame.samples[c];
                chBuf.rewind();
                for (int i = 0; i < perChannel; i++) {
                    volumeScratch[i * frame.samples.length + c] = chBuf.get();
                }
            }
            ByteBuffer bb = ByteBuffer.wrap(pcmBuf, 0, byteLen)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < totalSamples; i++) bb.putShort(volumeScratch[i]);
            return java.util.Arrays.copyOf(pcmBuf, byteLen);
        } else if (first instanceof ByteBuffer bb) {
            // 已经是字节缓冲，直接拷贝
            bb.order(ByteOrder.LITTLE_ENDIAN);
            int len = bb.remaining();
            if (pcmBuf.length < len) pcmBuf = new byte[len];
            bb.get(pcmBuf, 0, len);
            return java.util.Arrays.copyOf(pcmBuf, len);
        }
        return new byte[0];
    }

    /**
     * 对 16-bit PCM 小端字节应用音量（volume 0-100）。
     * <p>M56 修复：使用 ShortBuffer 批量读取替代每样本 ByteBuffer.getShort/putShort，降低 CPU 开销。
     */
    private void applyVolume(byte[] pcm, int vol) {
        if (vol == 100) return; // 100% 不处理
        if (vol <= 0) {
            java.util.Arrays.fill(pcm, (byte) 0);
            return;
        }
        double scale = vol / 100.0;
        ShortBuffer sb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int len = sb.remaining();
        // M56: 批量读取 short 数组，循环处理后再批量写回
        if (volumeScratch.length < len) volumeScratch = new short[len];
        sb.get(volumeScratch, 0, len);
        for (int i = 0; i < len; i++) {
            volumeScratch[i] = (short) Math.round(volumeScratch[i] * scale);
        }
        sb.rewind();
        sb.put(volumeScratch, 0, len);
    }

    /** 构建 FFmpeg headers 选项字符串：每行 "Key: Value\r\n" */
    private String buildHeaderString(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------
    // 状态通知
    // ---------------------------------------------------------------------------

    private void setState(PlaybackState s) {
        state = s;
        notifyStateChanged();
    }

    private void notifyStateChanged() {
        PlaybackState s = state;
        for (MusicPlayerListener l : listeners) {
            try { l.onStateChanged(s); } catch (Throwable ignored) {}
        }
    }

    private void notifyProgressThrottled(long curMs, long durMs) {
        long now = System.currentTimeMillis();
        if (now - lastNotifyWallMs < 500) return;
        lastNotifyWallMs = now;
        for (MusicPlayerListener l : listeners) {
            try { l.onProgress(curMs, durMs); } catch (Throwable ignored) {}
        }
    }

    private void notifyError(String msg) {
        for (MusicPlayerListener l : listeners) {
            try { l.onError(msg); } catch (Throwable ignored) {}
        }
    }

    private void notifyEnded() {
        for (MusicPlayerListener l : listeners) {
            try { l.onTrackEnded(); } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------------------
    // 资源清理
    // ---------------------------------------------------------------------------

    private void cleanup() {
        SourceDataLine l = line;
        if (l != null) {
            try { l.drain(); } catch (Throwable ignored) {}
            try { l.close(); } catch (Throwable ignored) {}
            line = null;
        }
        FFmpegFrameGrabber g = grabber;
        if (g != null) {
            try { g.stop(); } catch (Throwable ignored) {}
            try { g.close(); } catch (Throwable ignored) {}
            grabber = null;
        }
    }
}
