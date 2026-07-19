package com.pmcl.core.launch;

import com.pmcl.core.stats.PlayTimeTracker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动预判器：基于贝叶斯后验 + 指数加权概率模型，预测用户最可能启动的版本。
 *
 * <h3>模型设计</h3>
 * <pre>
 *   后验 P(v|t) ∝ 先验 P(v) × 似然 P(t|v)
 *
 *   先验 P(v)：版本使用频率，按指数加权（近期会话权重高）
 *     P(v) = Σ w_i / Σ_all w_j,  w_i = exp(-λ · age_i_hours)
 *
 *   似然 P(t|v)：当前时段 (dayOfWeek, hour) 该版本的条件概率
 *     P(t|v) = duration[v][dayOfWeek][hour] / Σ_t duration[v][t]
 *
 *   后验归一化后得到每个候选版本的概率
 * </pre>
 *
 * <h3>特征</h3>
 * <ul>
 *   <li>时段感知：周一上午和周五晚上预判不同版本</li>
 *   <li>近期偏好：指数加权让最近的游玩习惯权重更高</li>
 *   <li>冷启动兜底：无历史数据时回退到 lastSelectedVersion / recentVersions</li>
 *   <li>候选过滤：只考虑本地已安装版本</li>
 * </ul>
 *
 * <h3>使用</h3>
 * <pre>{@code
 *   LaunchPredictor predictor = new LaunchPredictor(playTimeTracker, preferences);
 *   PredictionResult result = predictor.predict(installedVersionIds);
 *   if (result.confidence >= 0.5) {
 *       String predicted = result.topVersionId;
 *       // 后台预启动该版本
 *   }
 * }</pre>
 */
public final class LaunchPredictor {

    /** 指数衰减系数（半衰期约 7 天）：age_hours × λ，7天 ≈ 168h，e^(-0.004×168) ≈ 0.51 */
    private static final double LAMBDA = 0.004;
    /** 预启动置信度阈值：低于此值不预启动，避免误判浪费资源 */
    public static final double CONFIDENCE_THRESHOLD = 0.4;
    /** 冷启动兜底权重：当历史数据不足时，lastSelectedVersion 的兜底置信度 */
    private static final double COLD_START_FALLBACK = 0.5;

    private final PlayTimeTracker playTimeTracker;
    private final com.pmcl.core.preferences.Preferences preferences;

    public LaunchPredictor(PlayTimeTracker playTimeTracker,
                           com.pmcl.core.preferences.Preferences preferences) {
        this.playTimeTracker = playTimeTracker;
        this.preferences = preferences;
    }

    /**
     * 预测用户最可能启动的版本。
     *
     * @param installedVersionIds 本地已安装的版本 ID 集合（只在这些候选中预测）
     * @return 预测结果，包含 top 版本、置信度、所有候选的概率分布
     */
    public PredictionResult predict(Set<String> installedVersionIds) {
        if (installedVersionIds == null || installedVersionIds.isEmpty()) {
            return PredictionResult.empty();
        }

        // 当前时段
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        int dayOfWeek = now.getDayOfWeek().getValue() - 1; // 周一=0, 周日=6
        int hour = now.getHour();

        // 取最近 30 天的会话历史
        List<PlayTimeTracker.Session> sessions = playTimeTracker.getSessions(0, 1000);
        if (sessions.isEmpty()) {
            return coldStartFallback(installedVersionIds);
        }

        // ===== 1. 计算先验 P(v)：指数加权的版本使用频率 =====
        // 只统计 installedVersionIds 中的版本
        Map<String, Double> priorWeights = new HashMap<>();
        double totalPrior = 0.0;
        long nowMillis = System.currentTimeMillis();
        for (PlayTimeTracker.Session s : sessions) {
            if (!installedVersionIds.contains(s.version)) continue;
            // 指数加权：age 越大权重越小（半衰期约 7 天）
            double ageHours = (nowMillis - s.start) / 3_600_000.0;
            double weight = Math.exp(-LAMBDA * ageHours);
            // 用 duration 归一化（长会话权重略高，但封顶避免单次超长会话主导）
            double durationWeight = Math.min(1.0, s.duration / 3_600_000.0); // 1 小时封顶
            double w = weight * (0.5 + 0.5 * durationWeight);
            priorWeights.merge(s.version, w, Double::sum);
            totalPrior += w;
        }

        if (totalPrior == 0.0 || priorWeights.isEmpty()) {
            return coldStartFallback(installedVersionIds);
        }

        // 归一化先验
        Map<String, Double> prior = new HashMap<>();
        for (Map.Entry<String, Double> e : priorWeights.entrySet()) {
            prior.put(e.getKey(), e.getValue() / totalPrior);
        }

        // ===== 2. 计算似然 P(t|v)：当前时段 (dayOfWeek, hour) 各版本的条件概率 =====
        // duration[v][dayOfWeek][hour] 累计，按版本单独统计
        Map<String, long[][]> versionHeatmaps = new HashMap<>();
        for (PlayTimeTracker.Session s : sessions) {
            if (!installedVersionIds.contains(s.version)) continue;
            long[][] hm = versionHeatmaps.computeIfAbsent(s.version, k -> new long[7][24]);
            ZonedDateTime sTime = Instant.ofEpochMilli(s.start).atZone(ZoneId.systemDefault());
            int sDow = sTime.getDayOfWeek().getValue() - 1;
            int sHour = sTime.getHour();
            hm[sDow][sHour] += s.duration;
        }

        // P(t|v) = duration[v][dow][hour] / Σ_t duration[v][t]
        Map<String, Double> likelihood = new HashMap<>();
        for (Map.Entry<String, long[][]> e : versionHeatmaps.entrySet()) {
            String v = e.getKey();
            long[][] hm = e.getValue();
            long totalForVersion = 0;
            for (int d = 0; d < 7; d++) {
                for (int h = 0; h < 24; h++) {
                    totalForVersion += hm[d][h];
                }
            }
            if (totalForVersion > 0) {
                likelihood.put(v, (double) hm[dayOfWeek][hour] / totalForVersion);
            } else {
                likelihood.put(v, 0.0);
            }
        }

        // ===== 3. 后验 P(v|t) ∝ P(v) × P(t|v)，归一化 =====
        Map<String, Double> posterior = new HashMap<>();
        double totalPosterior = 0.0;
        for (String v : installedVersionIds) {
            double p = prior.getOrDefault(v, 0.0);
            double l = likelihood.getOrDefault(v, 0.0);
            // 即使似然为 0（当前时段从未玩过），仍保留先验的 20% 作为探索项
            // 避免完全排除"偶尔玩但当前时段没玩过"的版本
            double post = p * (0.2 * p + 0.8 * l);
            // 补丁：若该版本有先验但当前时段似然为 0，给一个小残值避免完全归零
            if (l == 0.0 && p > 0.0) {
                post = p * 0.05;
            }
            posterior.put(v, post);
            totalPosterior += post;
        }

        if (totalPosterior == 0.0) {
            return coldStartFallback(installedVersionIds);
        }

        // 归一化
        for (Map.Entry<String, Double> e : posterior.entrySet()) {
            e.setValue(e.getValue() / totalPosterior);
        }

        // ===== 4. 选 top =====
        String topVersion = null;
        double topConfidence = 0.0;
        for (Map.Entry<String, Double> e : posterior.entrySet()) {
            if (e.getValue() > topConfidence) {
                topConfidence = e.getValue();
                topVersion = e.getKey();
            }
        }

        // ===== 5. 最近使用 + lastSelected 的增强项 =====
        // 最近 5 分钟内玩过的版本，置信度大幅提升（用户可能重启游戏）
        Map<String, Long> lastPlayed = preferences.getLastPlayedTimesRaw();
        if (topVersion != null) {
            Long last = lastPlayed.get(topVersion);
            if (last != null && (nowMillis - last) < 5 * 60_000L) {
                // 5 分钟内玩过，置信度 ×1.5（封顶 0.95）
                topConfidence = Math.min(0.95, topConfidence * 1.5);
            }
        }

        return new PredictionResult(topVersion, topConfidence, posterior, sessions.size());
    }

    /**
     * 冷启动兜底：无历史数据时，回退到 lastSelectedVersion / recentVersions。
     */
    private PredictionResult coldStartFallback(Set<String> installedVersionIds) {
        // 优先 lastSelectedVersion
        String lastSelected = preferences.getLastSelectedVersion();
        if (lastSelected != null && !lastSelected.isEmpty() && installedVersionIds.contains(lastSelected)) {
            Map<String, Double> dist = new HashMap<>();
            dist.put(lastSelected, COLD_START_FALLBACK);
            return new PredictionResult(lastSelected, COLD_START_FALLBACK, dist, 0);
        }

        // 其次 recentVersions 的第一个（LRU 最顶）
        List<String> recents = preferences.getRecentVersions();
        for (String v : recents) {
            if (installedVersionIds.contains(v)) {
                Map<String, Double> dist = new HashMap<>();
                dist.put(v, COLD_START_FALLBACK);
                return new PredictionResult(v, COLD_START_FALLBACK, dist, 0);
            }
        }

        return PredictionResult.empty();
    }

    /**
     * 预测结果。
     *
     * @param topVersionId  最高概率版本（可能为 null）
     * @param confidence    置信度 [0, 1]
     * @param distribution  所有候选版本的概率分布
     * @param sampleSize    用于预测的历史会话数
     */
    public static final class PredictionResult {
        public final String topVersionId;
        public final double confidence;
        public final Map<String, Double> distribution;
        public final int sampleSize;

        public PredictionResult(String topVersionId, double confidence,
                                Map<String, Double> distribution, int sampleSize) {
            this.topVersionId = topVersionId;
            this.confidence = confidence;
            this.distribution = Collections.unmodifiableMap(distribution);
            this.sampleSize = sampleSize;
        }

        public static PredictionResult empty() {
            return new PredictionResult(null, 0.0, Collections.emptyMap(), 0);
        }

        public boolean shouldPreheat() {
            return topVersionId != null && confidence >= CONFIDENCE_THRESHOLD;
        }
    }
}
