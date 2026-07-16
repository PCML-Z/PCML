package com.pmcl.core.stats;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏时长追踪器：记录每次游玩会话，按版本/按天聚合统计。
 * <p>
 * 数据持久化到 {@code ~/.pmcl/playtime.json}，格式：
 * <pre>
 * {
 *   "sessions": [
 *     {"version": "1.20.4", "start": 1700000000000, "end": 1700003600000, "duration": 3600000},
 *     ...
 *   ]
 * }
 * </pre>
 * <p>
 * 线程安全：recordStart/recordEnd 可在不同线程调用（启动线程 + 退出回调线程）。
 */
public final class PlayTimeTracker {

    /** 单次游玩会话记录 */
    public static final class Session {
        public final String version;
        public final long start;      // 开始时间戳（毫秒）
        public final long end;        // 结束时间戳（毫秒）
        public final long duration;   // 时长（毫秒）

        public Session(String version, long start, long end, long duration) {
            this.version = version;
            this.start = start;
            this.end = end;
            this.duration = duration;
        }
    }

    /** 按版本聚合的统计 */
    public static final class VersionStat {
        public final String version;
        public final long totalDuration;  // 总时长（毫秒）
        public final int sessionCount;    // 会话数
        public final long lastPlayed;     // 最后游玩时间戳

        public VersionStat(String version, long totalDuration, int sessionCount, long lastPlayed) {
            this.version = version;
            this.totalDuration = totalDuration;
            this.sessionCount = sessionCount;
            this.lastPlayed = lastPlayed;
        }
    }

    /** 按天聚合的统计 */
    public static final class DailyStat {
        public final String date;         // 日期 "yyyy-MM-dd"
        public final long totalDuration;  // 当天总时长（毫秒）
        public final int sessionCount;    // 当天会话数

        public DailyStat(String date, long totalDuration, int sessionCount) {
            this.date = date;
            this.totalDuration = totalDuration;
            this.sessionCount = sessionCount;
        }
    }

    /** 统计总览 */
    public static final class OverallStat {
        public final long totalDuration;       // 总时长（毫秒）
        public final int totalSessions;        // 总会话数
        public final List<VersionStat> versions; // 按版本（按时长降序）
        public final List<DailyStat> daily;    // 按天（按日期升序）

        public OverallStat(long totalDuration, int totalSessions,
                           List<VersionStat> versions, List<DailyStat> daily) {
            this.totalDuration = totalDuration;
            this.totalSessions = totalSessions;
            this.versions = versions != null ? versions : Collections.emptyList();
            this.daily = daily != null ? daily : Collections.emptyList();
        }
    }

    /** 时段热力图：7天(周一~周日) × 24小时的时长矩阵 */
    public static final class HeatmapStat {
        /** durations[dayOfWeek][hour] = 毫秒，dayOfWeek: 0=周一...6=周日 */
        public final long[][] durations;
        public final long maxValue; // 最大单元格值，用于颜色映射
        public final int recentDays;

        public HeatmapStat(long[][] durations, long maxValue, int recentDays) {
            this.durations = durations;
            this.maxValue = maxValue;
            this.recentDays = recentDays;
        }
    }

    /** 周几分布统计 */
    public static final class WeekdayStat {
        public final int dayOfWeek;        // 0=周一...6=周日
        public final String dayName;       // "周一"..."周日"
        public final long totalDuration;   // 总时长（毫秒）
        public final int sessionCount;     // 会话数

        public WeekdayStat(int dayOfWeek, String dayName, long totalDuration, int sessionCount) {
            this.dayOfWeek = dayOfWeek;
            this.dayName = dayName;
            this.totalDuration = totalDuration;
            this.sessionCount = sessionCount;
        }
    }

    /** 游玩记录（极值） */
    public static final class RecordsStat {
        public final Session longestSession;        // 最长单次会话
        public final int longestStreakDays;          // 最长连续游玩天数
        public final int currentStreakDays;          // 当前连续游玩天数
        public final String firstPlayDate;           // 首次游玩日期 "yyyy-MM-dd"
        public final String mostPlayedHour;          // 最常游玩时段 "HH:00"
        public final long totalDays;                 // 总游玩天数

        public RecordsStat(Session longestSession, int longestStreakDays, int currentStreakDays,
                           String firstPlayDate, String mostPlayedHour, long totalDays) {
            this.longestSession = longestSession;
            this.longestStreakDays = longestStreakDays;
            this.currentStreakDays = currentStreakDays;
            this.firstPlayDate = firstPlayDate;
            this.mostPlayedHour = mostPlayedHour;
            this.totalDays = totalDays;
        }
    }

    private final Path dataFile;
    private final Gson gson = new Gson();
    private final List<Session> sessions = Collections.synchronizedList(new ArrayList<>());

    /** 当前正在进行的会话：versionId → 开始时间戳 */
    private final ConcurrentHashMap<String, Long> activeStarts = new ConcurrentHashMap<>();

    public PlayTimeTracker(Path dataFile) {
        this.dataFile = dataFile;
        load();
    }

    // ===== 会话记录 =====

    /**
     * 记录游戏启动（会话开始）。
     * @param versionId 版本 ID
     */
    public void recordStart(String versionId) {
        if (versionId == null || versionId.isEmpty()) return;
        activeStarts.put(versionId, System.currentTimeMillis());
    }

    /**
     * 记录游戏退出（会话结束），计算时长并持久化。
     * @param versionId 版本 ID
     */
    public void recordEnd(String versionId) {
        if (versionId == null || versionId.isEmpty()) return;
        Long start = activeStarts.remove(versionId);
        if (start == null) return;

        long end = System.currentTimeMillis();
        long duration = end - start;
        if (duration < 1000) return; // 不足 1 秒不记录

        Session session = new Session(versionId, start, end, duration);
        sessions.add(session);
        save();
    }

    // ===== 统计查询 =====

    /**
     * 获取全部统计总览。
     * @param recentDays 按天统计的最近天数（如 7/30），0 表示全部
     */
    public OverallStat getOverallStats(int recentDays) {
        long totalDuration = 0;
        int totalSessions;

        // 按版本聚合
        Map<String, long[]> versionAgg = new LinkedHashMap<>(); // version → [duration, count, lastPlayed]
        List<Session> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions);
        }
        totalSessions = snapshot.size();
        for (Session s : snapshot) {
            totalDuration += s.duration;
            long[] agg = versionAgg.computeIfAbsent(s.version,
                    k -> new long[]{0, 0, 0});
            agg[0] += s.duration;
            agg[1] += 1;
            if (s.end > agg[2]) agg[2] = s.end;
        }

        List<VersionStat> versionStats = new ArrayList<>();
        for (Map.Entry<String, long[]> e : versionAgg.entrySet()) {
            long[] v = e.getValue();
            versionStats.add(new VersionStat(e.getKey(), v[0], (int) v[1], v[2]));
        }
        // 按总时长降序
        versionStats.sort((a, b) -> Long.compare(b.totalDuration, a.totalDuration));

        // 按天聚合
        Map<String, long[]> dailyAgg = new LinkedHashMap<>(); // date → [duration, count]
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate cutoff = recentDays > 0 ? LocalDate.now().minusDays(recentDays - 1) : null;

        for (Session s : snapshot) {
            LocalDate ld = LocalDate.ofEpochDay(s.start / 86400000);
            if (cutoff != null && ld.isBefore(cutoff)) continue;
            String date = ld.format(fmt);
            long[] agg = dailyAgg.computeIfAbsent(date, k -> new long[]{0, 0});
            agg[0] += s.duration;
            agg[1] += 1;
        }

        List<DailyStat> dailyStats = new ArrayList<>();
        for (Map.Entry<String, long[]> e : dailyAgg.entrySet()) {
            long[] v = e.getValue();
            dailyStats.add(new DailyStat(e.getKey(), v[0], (int) v[1]));
        }
        // 按日期升序
        dailyStats.sort((a, b) -> a.date.compareTo(b.date));

        return new OverallStat(totalDuration, totalSessions, versionStats, dailyStats);
    }

    /** 获取最近 N 天的每日时长（补零：没有游玩的天也返回 0） */
    public List<DailyStat> getDailyStatsWithZeros(int days) {
        OverallStat overall = getOverallStats(days);
        Map<String, DailyStat> existing = new LinkedHashMap<>();
        for (DailyStat d : overall.daily) {
            existing.put(d.date, d);
        }

        List<DailyStat> result = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate ld = today.minusDays(i);
            String date = ld.format(fmt);
            DailyStat d = existing.get(date);
            if (d != null) {
                result.add(d);
            } else {
                result.add(new DailyStat(date, 0, 0));
            }
        }
        return result;
    }

    /**
     * 获取时段热力图：7天(周一~周日) × 24小时的时长矩阵。
     * @param recentDays 统计最近多少天的数据，0 表示全部
     */
    public HeatmapStat getHeatmap(int recentDays) {
        List<Session> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions);
        }
        long[][] durations = new long[7][24];
        ZoneId zone = ZoneId.systemDefault();
        LocalDate cutoff = recentDays > 0 ? LocalDate.now().minusDays(recentDays) : null;

        for (Session s : snapshot) {
            LocalDate ld = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(s.start), zone);
            if (cutoff != null && ld.isBefore(cutoff)) continue;
            int dow = ld.getDayOfWeek().getValue() - 1; // 周一=1→0, 周日=7→6
            int hour = ld.atStartOfDay(zone).getHour();
            // 用会话开始时间的时段归属
            hour = java.time.Instant.ofEpochMilli(s.start).atZone(zone).getHour();
            durations[dow][hour] += s.duration;
        }

        long max = 0;
        for (long[] row : durations) for (long v : row) if (v > max) max = v;
        return new HeatmapStat(durations, max, recentDays);
    }

    /**
     * 获取周几分布：周一~周日的时长与会话数。
     * @param recentDays 统计最近多少天的数据，0 表示全部
     */
    public List<WeekdayStat> getWeekdayDistribution(int recentDays) {
        List<Session> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions);
        }
        long[] durations = new long[7];
        int[] counts = new int[7];
        ZoneId zone = ZoneId.systemDefault();
        LocalDate cutoff = recentDays > 0 ? LocalDate.now().minusDays(recentDays) : null;
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

        for (Session s : snapshot) {
            LocalDate ld = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(s.start), zone);
            if (cutoff != null && ld.isBefore(cutoff)) continue;
            int dow = ld.getDayOfWeek().getValue() - 1;
            durations[dow] += s.duration;
            counts[dow]++;
        }

        List<WeekdayStat> result = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            result.add(new WeekdayStat(i, names[i], durations[i], counts[i]));
        }
        return result;
    }

    /**
     * 获取游玩记录（极值）：最长会话、连续天数、首次游玩、最常时段。
     */
    public RecordsStat getRecords() {
        List<Session> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions);
        }
        if (snapshot.isEmpty()) {
            return new RecordsStat(null, 0, 0, "", "", 0);
        }

        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // 最长会话
        Session longest = snapshot.get(0);
        for (Session s : snapshot) {
            if (s.duration > longest.duration) longest = s;
        }

        // 收集所有游玩日期（去重排序）
        TreeMap<LocalDate, Boolean> playDays = new TreeMap<>();
        for (Session s : snapshot) {
            playDays.put(LocalDate.ofInstant(java.time.Instant.ofEpochMilli(s.start), zone), true);
        }
        long totalDays = playDays.size();

        // 最长连续游玩天数
        int longestStreak = 0;
        int currentRun = 0;
        LocalDate prev = null;
        for (LocalDate ld : playDays.keySet()) {
            if (prev != null && ld.equals(prev.plusDays(1))) {
                currentRun++;
            } else {
                currentRun = 1;
            }
            if (currentRun > longestStreak) longestStreak = currentRun;
            prev = ld;
        }

        // 当前连续天数（从今天/昨天往回数）
        int currentStreak = 0;
        LocalDate today = LocalDate.now();
        LocalDate cursor = today;
        // 如果今天没玩但昨天玩了，从昨天开始算
        if (!playDays.containsKey(cursor)) {
            cursor = today.minusDays(1);
        }
        while (playDays.containsKey(cursor)) {
            currentStreak++;
            cursor = cursor.minusDays(1);
        }

        // 首次游玩日期
        String firstPlay = playDays.firstKey().format(dateFmt);

        // 最常游玩时段
        long[] hourDurations = new long[24];
        for (Session s : snapshot) {
            int hour = java.time.Instant.ofEpochMilli(s.start).atZone(zone).getHour();
            hourDurations[hour] += s.duration;
        }
        int bestHour = 0;
        for (int i = 1; i < 24; i++) {
            if (hourDurations[i] > hourDurations[bestHour]) bestHour = i;
        }
        String mostPlayedHour = String.format("%02d:00", bestHour);

        return new RecordsStat(longest, longestStreak, currentStreak,
                firstPlay, mostPlayedHour, totalDays);
    }

    /**
     * 分页获取会话列表（按开始时间降序）。
     * @param offset 偏移量
     * @param limit 数量上限
     */
    public List<Session> getSessions(int offset, int limit) {
        List<Session> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions);
        }
        // 按开始时间降序
        snapshot.sort((a, b) -> Long.compare(b.start, a.start));
        if (offset >= snapshot.size()) return Collections.emptyList();
        int end = Math.min(offset + limit, snapshot.size());
        return new ArrayList<>(snapshot.subList(offset, end));
    }

    /** 获取总会话数 */
    public int getSessionCount() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    // ===== 持久化 =====

    private void load() {
        try {
            if (!Files.exists(dataFile)) return;
            String content = new String(Files.readAllBytes(dataFile), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (!root.has("sessions")) return;
            JsonArray arr = root.getAsJsonArray("sessions");
            synchronized (sessions) {
                sessions.clear();
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject o = arr.get(i).getAsJsonObject();
                    String version = safeStr(o, "version");
                    long start = o.has("start") ? o.get("start").getAsLong() : 0;
                    long end = o.has("end") ? o.get("end").getAsLong() : 0;
                    long duration = o.has("duration") ? o.get("duration").getAsLong() : (end - start);
                    if (!version.isEmpty() && duration > 0) {
                        sessions.add(new Session(version, start, end, duration));
                    }
                }
            }
        } catch (Throwable ignored) {
            // 加载失败不阻断启动
        }
    }

    private void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            List<Session> snapshot;
            synchronized (sessions) {
                snapshot = new ArrayList<>(sessions);
            }
            for (Session s : snapshot) {
                JsonObject o = new JsonObject();
                o.addProperty("version", s.version);
                o.addProperty("start", s.start);
                o.addProperty("end", s.end);
                o.addProperty("duration", s.duration);
                arr.add(o);
            }
            root.add("sessions", arr);
            Files.write(dataFile, gson.toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 保存失败不阻断运行
        }
    }

    private static String safeStr(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try { return o.get(key).getAsString(); } catch (Throwable t) { return ""; }
    }

    // ===== 工具方法 =====

    /** 格式化时长（毫秒 → "1h 23m" / "23m 45s" / "45s"） */
    public static String formatDuration(long millis) {
        if (millis <= 0) return "0s";
        long totalSec = millis / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    /** 格式化时长（简短版 "1.5h" / "23m" / "45s"） */
    public static String formatDurationShort(long millis) {
        if (millis <= 0) return "0";
        double totalMin = millis / 60000.0;
        if (totalMin >= 60) {
            double h = totalMin / 60.0;
            return String.format("%.1fh", h);
        }
        if (totalMin >= 1) {
            return (int) totalMin + "m";
        }
        return (millis / 1000) + "s";
    }
}
