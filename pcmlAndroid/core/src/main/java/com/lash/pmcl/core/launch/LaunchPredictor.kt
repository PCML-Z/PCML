package com.lash.pmcl.core.launch

import com.lash.pmcl.core.preferences.Preferences
import com.lash.pmcl.core.stats.PlayTimeTracker
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 启动预判器：基于贝叶斯后验 + 指数加权概率模型，预测用户最可能启动的版本。
 *
 * ### 模型设计
 * ```
 *   后验 P(v|t) ∝ 先验 P(v) × 似然 P(t|v)
 *
 *   先验 P(v)：版本使用频率，按指数加权（近期会话权重高）
 *     P(v) = Σ w_i / Σ_all w_j,  w_i = exp(-λ · age_i_hours)
 *
 *   似然 P(t|v)：当前时段 (dayOfWeek, hour) 该版本的条件概率
 *     P(t|v) = duration[v][dayOfWeek][hour] / Σ_t duration[v][t]
 *
 *   后验归一化后得到每个候选版本的概率
 * ```
 *
 * ### 特征
 * - 时段感知：周一上午和周五晚上预判不同版本
 * - 近期偏好：指数加权让最近的游玩习惯权重更高
 * - 冷启动兜底：无历史数据时回退到 lastSelectedVersion / recentVersions
 * - 候选过滤：只考虑本地已安装版本
 *
 * ### 使用
 * ```
 *   val predictor = LaunchPredictor(playTimeTracker, preferences)
 *   val result = predictor.predict(installedVersionIds)
 *   if (result.confidence >= 0.5) {
 *       val predicted = result.topVersionId
 *       // 后台预启动该版本
 *   }
 * ```
 */
class LaunchPredictor(
    private val playTimeTracker: PlayTimeTracker,
    private val preferences: Preferences
) {

    /**
     * 预测用户最可能启动的版本。
     *
     * @param installedVersionIds 本地已安装的版本 ID 集合（只在这些候选中预测）
     * @return 预测结果，包含 top 版本、置信度、所有候选的概率分布
     */
    fun predict(installedVersionIds: Set<String>?): PredictionResult {
        if (installedVersionIds.isNullOrEmpty()) {
            return PredictionResult.empty()
        }

        // 当前时段
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val dayOfWeek = now.dayOfWeek.value - 1 // 周一=0, 周日=6
        val hour = now.hour

        // 取最近 30 天的会话历史
        val sessions = playTimeTracker.getSessions(0, 1000)
        if (sessions.isEmpty()) {
            return coldStartFallback(installedVersionIds)
        }

        // ===== 1. 计算先验 P(v)：指数加权的版本使用频率 =====
        // 只统计 installedVersionIds 中的版本
        val priorWeights: MutableMap<String, Double> = HashMap()
        var totalPrior = 0.0
        val nowMillis = System.currentTimeMillis()
        for (s in sessions) {
            if (!installedVersionIds.contains(s.version)) continue
            // 指数加权：age 越大权重越小（半衰期约 7 天）
            val ageHours = (nowMillis - s.start) / 3_600_000.0
            val weight = Math.exp(-LAMBDA * ageHours)
            // 用 duration 归一化（长会话权重略高，但封顶避免单次超长会话主导）
            val durationWeight = minOf(1.0, s.duration / 3_600_000.0) // 1 小时封顶
            val w = weight * (0.5 + 0.5 * durationWeight)
            priorWeights.merge(s.version, w) { a, b -> a + b }
            totalPrior += w
        }

        if (totalPrior == 0.0 || priorWeights.isEmpty()) {
            return coldStartFallback(installedVersionIds)
        }

        // 归一化先验
        val prior: MutableMap<String, Double> = HashMap()
        for ((key, value) in priorWeights) {
            prior[key] = value / totalPrior
        }

        // ===== 2. 计算似然 P(t|v)：当前时段 (dayOfWeek, hour) 各版本的条件概率 =====
        // duration[v][dayOfWeek][hour] 累计，按版本单独统计
        val versionHeatmaps: MutableMap<String, Array<LongArray>> = HashMap()
        for (s in sessions) {
            if (!installedVersionIds.contains(s.version)) continue
            val hm = versionHeatmaps.getOrPut(s.version) { Array(7) { LongArray(24) } }
            val sTime = Instant.ofEpochMilli(s.start).atZone(ZoneId.systemDefault())
            val sDow = sTime.dayOfWeek.value - 1
            val sHour = sTime.hour
            hm[sDow][sHour] += s.duration
        }

        // P(t|v) = duration[v][dow][hour] / Σ_t duration[v][t]
        val likelihood: MutableMap<String, Double> = HashMap()
        for ((v, hm) in versionHeatmaps) {
            var totalForVersion = 0L
            for (d in 0 until 7) {
                for (h in 0 until 24) {
                    totalForVersion += hm[d][h]
                }
            }
            if (totalForVersion > 0) {
                likelihood[v] = hm[dayOfWeek][hour].toDouble() / totalForVersion
            } else {
                likelihood[v] = 0.0
            }
        }

        // ===== 3. 后验 P(v|t) ∝ P(v) × P(t|v)，归一化 =====
        val posterior: MutableMap<String, Double> = HashMap()
        var totalPosterior = 0.0
        for (v in installedVersionIds) {
            val p = prior.getOrDefault(v, 0.0)
            val l = likelihood.getOrDefault(v, 0.0)
            // 即使似然为 0（当前时段从未玩过），仍保留先验的 20% 作为探索项
            // 避免完全排除"偶尔玩但当前时段没玩过"的版本
            var post = p * (0.2 * p + 0.8 * l)
            // 补丁：若该版本有先验但当前时段似然为 0，给一个小残值避免完全归零
            if (l == 0.0 && p > 0.0) {
                post = p * 0.05
            }
            posterior[v] = post
            totalPosterior += post
        }

        if (totalPosterior == 0.0) {
            return coldStartFallback(installedVersionIds)
        }

        // 归一化
        for ((key, value) in posterior) {
            posterior[key] = value / totalPosterior
        }

        // ===== 4. 选 top =====
        var topVersion: String? = null
        var topConfidence = 0.0
        for ((key, value) in posterior) {
            if (value > topConfidence) {
                topConfidence = value
                topVersion = key
            }
        }

        // ===== 5. 最近使用 + lastSelected 的增强项 =====
        // 最近 5 分钟内玩过的版本，置信度大幅提升（用户可能重启游戏）
        val lastPlayed = preferences.getAllLastPlayedTimes()
        if (topVersion != null) {
            val last = lastPlayed[topVersion]
            if (last != null && nowMillis - last < 5 * 60_000L) {
                // 5 分钟内玩过，置信度 ×1.5（封顶 0.95）
                topConfidence = minOf(0.95, topConfidence * 1.5)
            }
        }

        return PredictionResult(topVersion, topConfidence, posterior, sessions.size)
    }

    /**
     * 冷启动兜底：无历史数据时，回退到 lastSelectedVersion / recentVersions。
     */
    private fun coldStartFallback(installedVersionIds: Set<String>): PredictionResult {
        // 优先 lastSelectedVersion
        val lastSelected = preferences.getLastSelectedVersion()
        if (lastSelected.isNotEmpty() && installedVersionIds.contains(lastSelected)) {
            val dist: MutableMap<String, Double> = HashMap()
            dist[lastSelected] = COLD_START_FALLBACK
            return PredictionResult(lastSelected, COLD_START_FALLBACK, dist, 0)
        }

        // 其次 recentVersions 的第一个（LRU 最顶）
        val recents = preferences.getRecentVersions()
        for (v in recents) {
            if (installedVersionIds.contains(v)) {
                val dist: MutableMap<String, Double> = HashMap()
                dist[v] = COLD_START_FALLBACK
                return PredictionResult(v, COLD_START_FALLBACK, dist, 0)
            }
        }

        return PredictionResult.empty()
    }

    /**
     * 预测结果。
     *
     * @param topVersionId  最高概率版本（可能为 null）
     * @param confidence    置信度 [0, 1]
     * @param distribution  所有候选版本的概率分布
     * @param sampleSize    用于预测的历史会话数
     */
    data class PredictionResult(
        val topVersionId: String?,
        val confidence: Double,
        val distribution: Map<String, Double>,
        val sampleSize: Int
    ) {
        fun shouldPreheat(): Boolean =
            topVersionId != null && confidence >= CONFIDENCE_THRESHOLD

        companion object {
            fun empty(): PredictionResult =
                PredictionResult(null, 0.0, emptyMap(), 0)
        }
    }

    companion object {
        /** 指数衰减系数（半衰期约 7 天）：age_hours × λ，7天 ≈ 168h，e^(-0.004×168) ≈ 0.51 */
        private const val LAMBDA = 0.004
        /** 预启动置信度阈值：低于此值不预启动，避免误判浪费资源 */
        const val CONFIDENCE_THRESHOLD = 0.4
        /** 冷启动兜底权重：当历史数据不足时，lastSelectedVersion 的兜底置信度 */
        private const val COLD_START_FALLBACK = 0.5
    }
}
