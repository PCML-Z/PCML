package com.pmcl.music.lyrics;

/** 单行歌词：时间戳（毫秒）+ 文本 */
public final class LyricsLine {
    public final long timeMs;
    public final String text;

    public LyricsLine(long timeMs, String text) {
        this.timeMs = timeMs;
        this.text = text == null ? "" : text;
    }
}
