package com.lash.pmcl.core.stats

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/**
 * 游戏时长追踪器：记录每次游玩会话，按版本/按天聚合统计。
 *
 * 数据持久化到构造函数传入的 [dataFile]，格式：
 * ```
 * {
 *   "sessions": [
 *     {"version": "1.20.4", "start": 1700000000000, "end": 1700003600000, "duration": 3600000},
 *     ...
 *   ]
 * }
 * ```
 *
 * 线程安全：recordStart/recordEnd 可在不同线程调用（启动线程 + 退出回调线程）。
 *
 * @param dataFile 持久化文件路径
 */
class PlayTimeTracker(private val dataFile: Path) {

    /** 单次游玩会话记录 */
    class Session @JvmOverloads constructor(
        val version: String,
        val start: Long,      // 开始时间戳（毫秒）
        val end: Long,        // 结束时间戳（毫秒）
        val duration: Long,   // 时长（毫秒）
        val instanceId: String = "",        // 实例 ID（可为空）
        val server: String = "",            // 服务器地址 host:port（可为空，单人游戏时为空）
        val worldName: String = "",         // 世界名称（可为空，多人游戏时为空）
        modIds: List<String> = emptyList()  // 会话期间已安装的 mod ID 列表（可为空）
    ) {
        val modIds: List<String> = ArrayList(modIds)
    }

    /** 按版本聚合的统计 */
    data class VersionStat(
        val version: String,
        val totalDuration: Long,  // 总时长（毫秒）
        val sessionCount: Int,    // 会话数
        val lastPlayed: Long      // 最后游玩时间戳
    )

    /** 按天聚合的统计 */
    data class DailyStat(
        val date: String,         // 日期 "yyyy-MM-dd"
        val totalDuration: Long,  // 当天总时长（毫秒）
        val sessionCount: Int     // 当天会话数
    )

    /** 统计总览 */
    data class OverallStat(
        val totalDuration: Long,       // 总时长（毫秒）
        val totalSessions: Int,        // 总会话数
        val versions: List<VersionStat> = emptyList(), // 按版本（按时长降序）
        val daily: List<DailyStat> = emptyList()       // 按天（按日期升序）
    )

    /** 时段热力图：7天(周一~周日) × 24小时的时长矩阵 */
    class HeatmapStat(
        /** durations[dayOfWeek][hour] = 毫秒，dayOfWeek: 0=周一...6=周日 */
        val durations: Array<LongArray>,
        val maxValue: Long,    // 最大单元格值，用于颜色映射
        val recentDays: Int
    )

    /** 周几分布统计 */
    data class WeekdayStat(
        val dayOfWeek: Int,        // 0=周一...6=周日
        val dayName: String,       // "周一"..."周日"
        val totalDuration: Long,   // 总时长（毫秒）
        val sessionCount: Int      // 会话数
    )

    /** 游玩记录（极值） */
    data class RecordsStat(
        val longestSession: Session?,   // 最长单次会话
        val longestStreakDays: Int,     // 最长连续游玩天数
        val currentStreakDays: Int,     // 当前连续游玩天数
        val firstPlayDate: String,      // 首次游玩日期 "yyyy-MM-dd"
        val mostPlayedHour: String,     // 最常游玩时段 "HH:00"
        val totalDays: Long             // 总游玩天数
    )

    /** 按维度细分的统计项（通用：模组/世界/服务器/实例） */
    data class BreakdownStat(
        val key: String,             // 维度键（modId / worldName / server / instanceId）
        val displayName: String,     // 显示名称
        val totalDuration: Long,     // 总时长（毫秒）
        val sessionCount: Int,       // 会话数
        val lastPlayed: Long         // 最后游玩时间戳
    )

    private val gson = Gson()
    private val sessions: MutableList<Session> = Collections.synchronizedList(ArrayList())

    /** 当前正在进行的会话：versionId → 开始时间戳 */
    private val activeStarts: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /** 当前会话上下文：versionId → 上下文信息（instanceId/server/world/modIds） */
    private val activeContexts: ConcurrentHashMap<String, SessionContext> = ConcurrentHashMap()

    /** 活跃会话上下文（在 recordStart 时初始化，recordEnd 时读取） */
    private class SessionContext {
        var instanceId: String = ""
        var server: String = ""
        var worldName: String = ""
        var modIds: List<String> = emptyList()
    }

    init {
        load()
    }

    // ===== 会话记录 =====

    /**
     * 记录游戏启动（会话开始）。
     * @param versionId 版本 ID
     */
    fun recordStart(versionId: String) {
        recordStart(versionId, "", emptyList())
    }

    /**
     * 记录游戏启动（会话开始），携带实例和模组上下文。
     * @param versionId   版本 ID
     * @param instanceId  实例 ID（可为空）
     * @param modIds      会话期间已安装的 mod ID 列表（可为空）
     */
    fun recordStart(versionId: String, instanceId: String, modIds: List<String>) {
        if (versionId.isEmpty()) return
        activeStarts[versionId] = System.currentTimeMillis()
        val ctx = SessionContext()
        ctx.instanceId = instanceId
        ctx.modIds = ArrayList(modIds)
        activeContexts[versionId] = ctx
        // 立即持久化活跃会话，防止崩溃时丢失当前进行中的游戏时长
        saveActiveSessions()
    }

    /** 更新活跃会话的服务器地址（从游戏日志解析「Connecting to」时调用）。 */
    fun updateSessionServer(versionId: String, server: String) {
        val ctx = activeContexts[versionId]
        if (ctx != null && server.isNotEmpty()) {
            ctx.server = server
        }
    }

    /** 更新活跃会话的世界名称（从游戏日志解析单人世界加载时调用）。 */
    fun updateSessionWorld(versionId: String, worldName: String) {
        val ctx = activeContexts[versionId]
        if (ctx != null && worldName.isNotEmpty()) {
            ctx.worldName = worldName
        }
    }

    /**
     * 记录游戏退出（会话结束），计算时长并持久化。
     * @param versionId 版本 ID
     */
    fun recordEnd(versionId: String) {
        if (versionId.isEmpty()) return
        val start = activeStarts.remove(versionId) ?: return
        val ctx = activeContexts.remove(versionId)

        val end = System.currentTimeMillis()
        val duration = end - start
        if (duration < 1000) return  // 不足 1 秒不记录

        val session = if (ctx != null) {
            Session(versionId, start, end, duration, ctx.instanceId, ctx.server, ctx.worldName, ctx.modIds)
        } else {
            Session(versionId, start, end, duration)
        }
        sessions.add(session)
        save()
    }

    // ===== 统计查询 =====

    /**
     * 获取全部统计总览。
     * @param recentDays 按天统计的最近天数（如 7/30），0 表示全部
     */
    fun getOverallStats(recentDays: Int): OverallStat {
        var totalDuration = 0L
        // 按版本聚合
        val versionAgg = linkedMapOf<String, LongArray>() // version → [duration, count, lastPlayed]
        val snapshot: List<Session> = synchronized(sessions) { ArrayList(sessions) }
        val totalSessions = snapshot.size
        for (s in snapshot) {
            totalDuration += s.duration
            val agg = versionAgg.getOrPut(s.version) { LongArray(3) }
            agg[0] += s.duration
            agg[1] += 1
            if (s.end > agg[2]) agg[2] = s.end
        }

        val versionStats = versionAgg.entries.map { (k, v) ->
            VersionStat(k, v[0], v[1].toInt(), v[2])
        }.sortedByDescending { it.totalDuration }

        // 按天聚合（使用本地时区，与 heatmap/records 一致，避免 UTC 日界错位）
        val dailyAgg = linkedMapOf<String, LongArray>() // date → [duration, count]
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val zone = ZoneId.systemDefault()
        val cutoff = if (recentDays > 0) LocalDate.now(zone).minusDays((recentDays - 1).toLong()) else null

        for (s in snapshot) {
            val ld = LocalDate.ofInstant(Instant.ofEpochMilli(s.start), zone)
            if (cutoff != null && ld.isBefore(cutoff)) continue
            val date = ld.format(fmt)
            val agg = dailyAgg.getOrPut(date) { LongArray(2) }
            agg[0] += s.duration
            agg[1] += 1
        }

        val dailyStats = dailyAgg.entries.map { (k, v) ->
            DailyStat(k, v[0], v[1].toInt())
        }.sortedBy { it.date }

        return OverallStat(totalDuration, totalSessions, versionStats, dailyStats)
    }

    /** 获取最近 N 天的每日时长（补零：没有游玩的天也返回 0） */
    fun getDailyStatsWithZeros(days: Int): List<DailyStat> {
        val overall = getOverallStats(days)
        val existing = linkedMapOf<String, DailyStat>()
        for (d in overall.daily) {
            existing[d.date] = d
        }

        val result = ArrayList<DailyStat>()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val today = LocalDate.now()
        for (i in days - 1 downTo 0) {
            val ld = today.minusDays(i.toLong())
            val date = ld.format(fmt)
            result.add(existing[date] ?: DailyStat(date, 0L, 0))
        }
        return result
    }

    /**
     * 获取时段热力图：7天(周一~周日) × 24小时的时长矩阵。
     * @param recentDays 统计最近多少天的数据，0 表示全部
     */
    fun getHeatmap(recentDays: Int): HeatmapStat {
        val snapshot: List<Session> = synchronized(sessions) { ArrayList(sessions) }
        val durations = Array(7) { LongArray(24) }
        val zone = ZoneId.systemDefault()
        val cutoff = if (recentDays > 0) LocalDate.now().minusDays(recentDays.toLong()) else null

        for (s in snapshot) {
            val ld = LocalDate.ofInstant(Instant.ofEpochMilli(s.start), zone)
            if (cutoff != null && ld.isBefore(cutoff)) continue
            val dow = ld.dayOfWeek.value - 1 // 周一=1→0, 周日=7→6
            // 用会话开始时间的时段归属
            val hour = Instant.ofEpochMilli(s.start).atZone(zone).hour
            durations[dow][hour] += s.duration
        }

        var max = 0L
        for (row in durations) for (v in row) if (v > max) max = v
        return HeatmapStat(durations, max, recentDays)
    }

    /**
     * 获取周几分布：周一~周日的时长与会话数。
     * @param recentDays 统计最近多少天的数据，0 表示全部
     */
    fun getWeekdayDistribution(recentDays: Int): List<WeekdayStat> {
        val snapshot: List<Session> = synchronized(sessions) { ArrayList(sessions) }
        val durations = LongArray(7)
        val counts = IntArray(7)
        val zone = ZoneId.systemDefault()
        val cutoff = if (recentDays > 0) LocalDate.now().minusDays(recentDays.toLong()) else null
        val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        for (s in snapshot) {
            val ld = LocalDate.ofInstant(Instant.ofEpochMilli(s.start), zone)
            if (cutoff != null && ld.isBefore(cutoff)) continue
            val dow = ld.dayOfWeek.value - 1
            durations[dow] += s.duration
            counts[dow]++
        }

        val result = ArrayList<WeekdayStat>()
        for (i in 0 until 7) {
            result.add(WeekdayStat(i, names[i], durations[i], counts[i]))
        }
        return result
    }

    /**
     * 获取游玩记录（极值）：最长会话、连续天数、首次游玩、最常时段。
     */
    fun getRecords(): RecordsStat {
        val snapshot: List<Session> = synchronized(sessions) { ArrayList(sessions) }
        if (snapshot.isEmpty()) {
            return RecordsStat(null, 0, 0, "", "", 0L)
        }

        val zone = ZoneId.systemDefault()
        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        // 最长会话
        var longest = snapshot[0]
        for (s in snapshot) {
            if (s.duration > longest.duration) longest = s
        }

        // 收集所有游玩日期（去重排序）
        val playDays = TreeSet<LocalDate>()
        for (s in snapshot) {
            playDays.add(LocalDate.ofInstant(Instant.ofEpochMilli(s.start), zone))
        }
        val totalDays = playDays.size.toLong()

        // 最长连续游玩天数
        var longestStreak = 0
        var currentRun = 0
        var prev: LocalDate? = null
        for (ld in playDays) {
            if (prev != null && ld == prev.plusDays(1)) {
                currentRun++
            } else {
                currentRun = 1
            }
            if (currentRun > longestStreak) longestStreak = currentRun
            prev = ld
        }

        // 当前连续天数（从今天/昨天往回数）
        var currentStreak = 0
        val today = LocalDate.now()
        var cursor = today
        // 如果今天没玩但昨天玩了，从昨天开始算
        if (!playDays.contains(cursor)) {
            cursor = today.minusDays(1)
        }
        while (playDays.contains(cursor)) {
            currentStreak++
            cursor = cursor.minusDays(1)
        }

        // 首次游玩日期
        val firstPlay = playDays.first()!!.format(dateFmt)

        // 最常游玩时段
        val hourDurations = LongArray(24)
        for (s in snapshot) {
            val hour = Instant.ofEpochMilli(s.start).atZone(zone).hour
            hourDurations[hour] += s.duration
        }
        var bestHour = 0
        for (i in 1 until 24) {
            if (hourDurations[i] > hourDurations[bestHour]) bestHour = i
        }
        val mostPlayedHour = "%02d:00".format(bestHour)

        return RecordsStat(longest, longestStreak, currentStreak, firstPlay, mostPlayedHour, totalDays)
    }

    // ===== 细分统计 =====

    /**
     * 按模组细分：统计每个 mod 的游玩时长（该 mod 存在的会话时长之和）。
     * @param topN 返回前 N 个（按时长降序），0 表示全部
     */
    fun getModBreakdown(topN: Int): List<BreakdownStat> {
        val snapshot: List<Session> = synchronized(sessions) { ArrayList(sessions) }
        val agg = linkedMapOf<String, LongArray>() // modId → [duration, count, lastPlayed]
        for (s in snapshot) {
            if (s.modIds.isEmpty()) continue
            for (modId in s.modIds) {
                val v = agg.getOrPut(modId) { LongArray(3) }
                v[0] += s.duration
                v[1] += 1
                if (s.end > v[2]) v[2] = s.end
            }
        }
        val result = agg.entries.map { (k, v) ->
            BreakdownStat(k, k, v[0], v[1].toInt(), v[2])
        }.sortedByDescending { it.totalDuration }
        return if (topN > 0 && result.size > topN) ArrayList(result.subList(0, topN)) else result
    }

    /** 按服务器细分：统计每个服务器的游玩时长 */
    fun getServerBreakdown(): List<BreakdownStat> =
        getBreakdownByField { it.server }

    /** 按世界细分：统计每个单人世界的游玩时长 */
    fun getWorldBreakdown(): List<BreakdownStat> =
        getBreakdownByField { it.worldName }

    /** 按实例细分：统计每个实例的游玩时长 */
    fun getInstanceBreakdown(): List<BreakdownStat> =
        getBreakdownByField { it.instanceId }

    /** 通用按字段聚合 */
    private fun getBreakdownByField(keyExtractor: (Session) -> String): List<BreakdownStat> {
        val snapshot: List<Session> = synchronized(sessions) { ArrayList(sessions) }
        val agg = linkedMapOf<String, LongArray>()
        for (s in snapshot) {
            val key = keyExtractor(s)
            if (key.isEmpty()) continue
            val v = agg.getOrPut(key) { LongArray(3) }
            v[0] += s.duration
            v[1] += 1
            if (s.end > v[2]) v[2] = s.end
        }
        return agg.entries.map { (k, v) ->
            BreakdownStat(k, k, v[0], v[1].toInt(), v[2])
        }.sortedByDescending { it.totalDuration }
    }

    /**
     * 分页获取会话列表（按开始时间降序）。
     * @param offset 偏移量
     * @param limit 数量上限
     */
    fun getSessions(offset: Int, limit: Int): List<Session> {
        val snapshot = synchronized(sessions) { ArrayList(sessions) }
        // 按开始时间降序
        snapshot.sortByDescending { it.start }
        if (offset >= snapshot.size) return emptyList()
        val end = minOf(offset + limit, snapshot.size)
        return ArrayList(snapshot.subList(offset, end))
    }

    /** 获取总会话数 */
    fun getSessionCount(): Int = synchronized(sessions) { sessions.size }

    // ===== 持久化 =====

    private fun load() {
        try {
            if (!Files.exists(dataFile)) return
            val content = String(Files.readAllBytes(dataFile), StandardCharsets.UTF_8)
            val root = JsonParser.parseString(content).asJsonObject
            if (!root.has("sessions")) return
            val arr = root.getAsJsonArray("sessions")!!
            synchronized(sessions) {
                sessions.clear()
                for (i in 0 until arr.size()) {
                    // 单条损坏不中止整文件加载
                    try {
                        if (!arr[i].isJsonObject) continue
                        val o = arr[i].asJsonObject
                        val version = safeStr(o, "version")
                        val start = o.get("start")?.asLong ?: 0L
                        val end = o.get("end")?.asLong ?: 0L
                        val duration = o.get("duration")?.asLong ?: (end - start)
                        val instanceId = safeStr(o, "instanceId")
                        val server = safeStr(o, "server")
                        val worldName = safeStr(o, "worldName")
                        val modIds = ArrayList<String>()
                        if (o.has("modIds") && o.get("modIds")?.isJsonArray == true) {
                            val modArr = o.getAsJsonArray("modIds")!!
                            for (me in modArr) {
                                if (me.isJsonPrimitive) modIds.add(me.asString)
                            }
                        }
                        if (version.isNotEmpty() && duration > 0) {
                            sessions.add(Session(version, start, end, duration,
                                    instanceId, server, worldName, modIds))
                        }
                    } catch (entryErr: Throwable) {
                        System.err.println("[PlayTimeTracker] 跳过损坏会话条目 #$i: ${entryErr.message}")
                    }
                }
            }
            // 加载崩溃前未结束的活跃会话
            if (root.has("active") && root.get("active")?.isJsonObject == true) {
                val active = root.getAsJsonObject("active")!!
                for ((versionId, value) in active.entrySet()) {
                    try {
                        if (!value.isJsonObject) continue
                        val a = value.asJsonObject
                        val start = a.get("start")?.asLong ?: 0L
                        if (start > 0) {
                            activeStarts[versionId] = start
                            val ctx = SessionContext()
                            ctx.instanceId = safeStr(a, "instanceId")
                            ctx.server = safeStr(a, "server")
                            ctx.worldName = safeStr(a, "worldName")
                            if (a.has("modIds") && a.get("modIds")?.isJsonArray == true) {
                                val mods = ArrayList<String>()
                                val modArr = a.getAsJsonArray("modIds")!!
                                for (me in modArr) {
                                    if (me.isJsonPrimitive) mods.add(me.asString)
                                }
                                ctx.modIds = mods
                            }
                            activeContexts[versionId] = ctx
                        }
                    } catch (activeErr: Throwable) {
                        System.err.println("[PlayTimeTracker] 跳过损坏活跃会话: ${activeErr.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            // 加载失败不阻断启动，但必须可观测
            System.err.println("[PlayTimeTracker] 加载 playtime 数据失败: ${t.message}")
            try {
                if (Files.exists(dataFile)) {
                    val bak = dataFile.resolveSibling("${dataFile.fileName}.corrupt.${System.currentTimeMillis()}.bak")
                    Files.copy(dataFile, bak, StandardCopyOption.REPLACE_EXISTING)
                    System.err.println("[PlayTimeTracker] 已备份损坏文件到 $bak")
                }
            } catch (bakErr: Exception) {
                System.err.println("[PlayTimeTracker] 备份损坏文件失败: ${bakErr.message}")
            }
        }
        // 恢复崩溃前未结束的会话（补记时长，防止数据丢失）
        recoverCrashedSessions()
    }

    @Synchronized
    private fun save() {
        try {
            dataFile.parent?.let { Files.createDirectories(it) }
            val root = JsonObject()
            val arr = JsonArray()
            val snapshot: List<Session> = synchronized(sessions) { ArrayList(sessions) }
            for (s in snapshot) {
                val o = JsonObject()
                o.addProperty("version", s.version)
                o.addProperty("start", s.start)
                o.addProperty("end", s.end)
                o.addProperty("duration", s.duration)
                if (s.instanceId.isNotEmpty()) o.addProperty("instanceId", s.instanceId)
                if (s.server.isNotEmpty()) o.addProperty("server", s.server)
                if (s.worldName.isNotEmpty()) o.addProperty("worldName", s.worldName)
                if (s.modIds.isNotEmpty()) {
                    val modArr = JsonArray()
                    for (modId in s.modIds) modArr.add(modId)
                    o.add("modIds", modArr)
                }
                arr.add(o)
            }
            root.add("sessions", arr)
            // 同时持久化活跃会话（用于崩溃恢复）
            if (activeStarts.isNotEmpty()) {
                val active = JsonObject()
                for ((versionId, startTime) in activeStarts) {
                    val a = JsonObject()
                    a.addProperty("start", startTime)
                    val ctx = activeContexts[versionId]
                    if (ctx != null) {
                        if (ctx.instanceId.isNotEmpty()) a.addProperty("instanceId", ctx.instanceId)
                        if (ctx.server.isNotEmpty()) a.addProperty("server", ctx.server)
                        if (ctx.worldName.isNotEmpty()) a.addProperty("worldName", ctx.worldName)
                        if (ctx.modIds.isNotEmpty()) {
                            val modArr = JsonArray()
                            for (modId in ctx.modIds) modArr.add(modId)
                            a.add("modIds", modArr)
                        }
                    }
                    active.add(versionId, a)
                }
                root.add("active", active)
            }
            // 原子写入：先写临时文件再 move，防止并发写损坏或 JVM 崩溃截断
            val tmp = dataFile.resolveSibling("${dataFile.fileName}.tmp")
            Files.write(tmp, gson.toJson(root).toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(tmp, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING)
            }
            // Android 沙箱已管理文件权限，无需调用 TokenEncryptor.hardenFilePermissions
        } catch (e: IOException) {
            System.err.println("[PlayTimeTracker] 保存失败: ${e.message}")
        }
    }

    /** 仅持久化活跃会话（轻量级，recordStart 时调用） */
    private fun saveActiveSessions() {
        save()
    }

    /** 启动时恢复崩溃前未结束的会话 */
    private fun recoverCrashedSessions() {
        if (activeStarts.isEmpty()) return
        val now = System.currentTimeMillis()
        val recovered = ArrayList<Session>()
        for ((versionId, start) in activeStarts) {
            var duration = now - start
            // 仅恢复 1 秒到 24 小时之间的会话（过滤时钟异常）
            if (duration < 1000) continue
            if (duration > 24L * 3600_000L) {
                System.err.println("[PlayTimeTracker] 崩溃恢复: 会话时长 ${duration}ms 超过 24h，截断（可能系统时间被篡改）")
                duration = 24L * 3600_000L
            }
            val ctx = activeContexts[versionId]
            val session = if (ctx != null) {
                Session(versionId, start, now, duration, ctx.instanceId, ctx.server, ctx.worldName, ctx.modIds)
            } else {
                Session(versionId, start, now, duration)
            }
            recovered.add(session)
            System.err.println("[PlayTimeTracker] 崩溃恢复: 补记会话 $versionId 时长 ${duration / 1000}s")
        }
        if (recovered.isNotEmpty()) {
            synchronized(sessions) {
                sessions.addAll(recovered)
            }
            activeStarts.clear()
            activeContexts.clear()
            save()
        }
    }

    private fun safeStr(o: JsonObject?, key: String): String {
        if (o == null || !o.has(key)) return ""
        val el = o.get(key)
        if (el == null || el.isJsonNull) return ""
        return try {
            el.asString
        } catch (t: Throwable) {
            ""
        }
    }

    companion object {
        /** 格式化时长（毫秒 → "1h 23m" / "23m 45s" / "45s"） */
        @JvmStatic
        fun formatDuration(millis: Long): String {
            if (millis <= 0) return "0s"
            val totalSec = millis / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m ${s}s"
                else -> "${s}s"
            }
        }

        /** 格式化时长（简短版 "1.5h" / "23m" / "45s"） */
        @JvmStatic
        fun formatDurationShort(millis: Long): String {
            if (millis <= 0) return "0"
            val totalMin = millis / 60000.0
            if (totalMin >= 60) {
                val h = totalMin / 60.0
                return "%.1fh".format(h)
            }
            if (totalMin >= 1) {
                return "${totalMin.toInt()}m"
            }
            return "${millis / 1000}s"
        }
    }
}
