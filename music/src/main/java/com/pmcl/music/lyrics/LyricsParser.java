package com.pmcl.music.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 简易 LRC / 增强 LRC 解析器 */
public final class LyricsParser {

    private static final Pattern TIME_TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?]");
    /** 最大输入长度 1MB，防止恶意歌词导致 OOM */
    private static final int MAX_INPUT_LENGTH = 1024 * 1024;
    /** 最大解析行数 */
    private static final int MAX_LINES = 10000;

    private LyricsParser() {}

    public static List<LyricsLine> parse(String content) {
        List<LyricsLine> lines = new ArrayList<>();
        if (content == null || content.isBlank()) return lines;
        if (content.length() > MAX_INPUT_LENGTH) {
            // 截断而非拒绝，保留前 1MB 内容
            content = content.substring(0, MAX_INPUT_LENGTH);
        }
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = TIME_TAG.matcher(line);
            List<Long> times = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                times.add(toMs(m.group(1), m.group(2), m.group(3)));
                lastEnd = m.end();
            }
            if (times.isEmpty()) continue;
            String text = line.substring(lastEnd).trim();
            // 去掉行内增强标签 <mm:ss.xx>
            text = text.replaceAll("<\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?>", "").trim();
            for (Long t : times) {
                if (lines.size() >= MAX_LINES) break;
                lines.add(new LyricsLine(t, text));
            }
            if (lines.size() >= MAX_LINES) break;
        }
        lines.sort(Comparator.comparingLong(a -> a.timeMs));
        if (!lines.isEmpty()) return lines;
        return parsePlain(content);
    }

    /** 没有时间轴的纯文本：每行一条，timeMs 为 -1，不参与高亮。 */
    private static List<LyricsLine> parsePlain(String content) {
        List<LyricsLine> lines = new ArrayList<>();
        if (content == null || content.isBlank()) return lines;
        if (content.length() > MAX_INPUT_LENGTH) {
            content = content.substring(0, MAX_INPUT_LENGTH);
        }
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.matches("\\[[A-Za-z]+:[^\\]]*\\]")) continue;
            if (lines.size() >= MAX_LINES) break;
            lines.add(new LyricsLine(-1L, line));
        }
        return lines;
    }

    /** 根据当前进度找歌词行索引；无时间轴或尚未到第一句时返回 -1 */
    public static int indexAt(List<LyricsLine> lines, long currentMs) {
        if (lines == null || lines.isEmpty()) return -1;
        int ans = -1;
        for (int i = 0; i < lines.size(); i++) {
            long t = lines.get(i).timeMs;
            if (t < 0) continue;
            if (t <= currentMs) ans = i;
            else break;
        }
        return ans;
    }

    private static long toMs(String m, String s, String frac) {
        long minutes = Long.parseLong(m);
        long seconds = Long.parseLong(s);
        long ms = 0;
        if (frac != null && !frac.isEmpty()) {
            String f = frac.length() >= 3 ? frac.substring(0, 3)
                    : (frac + "000").substring(0, 3);
            ms = Long.parseLong(f);
        }
        return minutes * 60_000L + seconds * 1000L + ms;
    }
}
