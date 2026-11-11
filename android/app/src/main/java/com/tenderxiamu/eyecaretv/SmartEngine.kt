package com.tenderxiamu.eyecaretv

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 智能自适应护眼引擎（Android 移植版）。
 * 与桌面端 smart_engine.py 逻辑一致，但使用 SharedPreferences 持久化，
 * 避免引入 JSON / 文件 IO 等额外依赖。
 *
 * 三个组件：
 * 1. FatigueModel：权重式疲劳指数预测 + 在线学习（snooze/complete 反馈调整）
 * 2. AdaptiveIntervalBandit：UCB1 多臂老虎机，学习最优提醒间隔
 * 3. InsightGenerator：从最近 30 次会话提炼个性化洞察
 */
class SmartEngine(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- 疲劳模型 ----
    private var fatigueWeights = DEFAULT_WEIGHTS.toMutableMap()
    private var fatigueTrainCount = 0

    // ---- 自适应间隔（老虎机） ----
    private val banditCounts = CANDIDATE_INTERVALS.associateWithTo(mutableMapOf()) { 0 }
    private val banditRewards = CANDIDATE_INTERVALS.associateWithTo(mutableMapOf()) { 0.0 }

    // ---- 会话 ----
    private var snoozeStreak = 0
    private var sessionActiveMs = 0L
    private var sessionIdleMs = 0L
    private var lastBreakEpochSec = System.currentTimeMillis() / 1000.0
    private var pendingSession: SessionRecord? = null

    init {
        load()
    }

    // ================== 公共 API ==================

    /** 每秒由计时逻辑调用，累计活跃 / 空闲时间（毫秒）。 */
    fun recordTick(activeMs: Long, idleMs: Long) {
        sessionActiveMs += activeMs
        sessionIdleMs += idleMs
    }

    /** 计算当前疲劳指数 0-100。 */
    fun fatigueScore(accumulatedMinutes: Double): Double {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val total = sessionActiveMs + sessionIdleMs
        val idleRatio = if (total == 0L) 1.0 else sessionIdleMs.toDouble() / total
        val features = extractFeatures(
            consecutiveMinutes = accumulatedMinutes,
            timeSinceBreak = (System.currentTimeMillis() / 1000.0 - lastBreakEpochSec) / 60.0,
            snoozeStreak = snoozeStreak,
            hour = hour,
            idleRatio = idleRatio,
        )
        return predict(features)
    }

    /** 提醒触发时调用，记录本次会话快照。 */
    fun onReminderTriggered(accumulatedMinutes: Double) {
        val cal = Calendar.getInstance()
        val total = sessionActiveMs + sessionIdleMs
        val idleRatio = if (total == 0L) 1.0 else sessionIdleMs.toDouble() / total
        pendingSession = SessionRecord(
            timestamp = System.currentTimeMillis() / 1000.0,
            screenMinutes = accumulatedMinutes,
            idleRatio = idleRatio,
            snoozed = false,
            completed = false,
            hour = cal.get(Calendar.HOUR_OF_DAY),
            weekday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7, // 0 = Monday
        )
    }

    /** 用户延迟了提醒。 */
    fun onSnooze() {
        snoozeStreak++
        pendingSession?.snoozed = true
    }

    /** 用户完整看完提醒（或因 snooze 后正常结束）。 */
    fun onCompleted(remindMinutes: Int) {
        snoozeStreak = 0
        lastBreakEpochSec = System.currentTimeMillis() / 1000.0
        sessionActiveMs = 0L
        sessionIdleMs = 0L

        val session = pendingSession ?: run { save(); return }
        session.completed = !session.snoozed

        appendSession(session)

        val features = extractFeatures(
            consecutiveMinutes = session.screenMinutes,
            timeSinceBreak = session.screenMinutes,
            snoozeStreak = 0, // 刚清零
            hour = session.hour,
            idleRatio = session.idleRatio,
        )
        updateFatigue(features, if (session.snoozed) "snooze" else "complete")

        val reward = if (session.completed) 1.0 else -0.3
        updateBandit(remindMinutes, reward)

        pendingSession = null
        save()
    }

    /** AI 建议的提醒间隔（分钟）。数据不足时返回当前设置值。 */
    fun suggestedInterval(currentInterval: Int): Int {
        if (banditCounts.values.sum() < 3) return currentInterval
        return selectBandit(currentInterval)
    }

    /** 返回最近 30 条会话生成的洞察列表。 */
    fun insights(): List<String> = InsightGenerator(loadSessions()).generate()

    /** 今日统计（供 ReportFragment 使用）。 */
    fun dailyStats(): DailyStats {
        val startOfDay = startOfDayEpochSec()
        val today = loadSessions().filter { it.timestamp >= startOfDay }
        val completed = today.count { it.completed }
        val snoozed = today.count { it.snoozed }
        val totalScreen = today.sumOf { it.screenMinutes }
        return DailyStats(
            breaks = completed,
            snoozes = snoozed,
            totalScreenMinutes = totalScreen,
            bestInterval = bestBanditInterval(),
            fatigueTrainingSamples = fatigueTrainCount,
        )
    }

    // ================== 疲劳模型 ==================

    private fun extractFeatures(
        consecutiveMinutes: Double,
        timeSinceBreak: Double,
        snoozeStreak: Int,
        hour: Int,
        idleRatio: Double,
    ): FatigueFeatures = FatigueFeatures(
        consecutiveMinutes = (consecutiveMinutes / 120.0).coerceIn(0.0, 1.0),
        timeSinceBreak = (timeSinceBreak / 120.0).coerceIn(0.0, 1.0),
        snoozeStreak = (snoozeStreak / 5.0).coerceIn(0.0, 1.0),
        hourFactor = hourFactor(hour),
        idleRatio = 1.0 - idleRatio.coerceIn(0.0, 1.0),
    )

    private fun hourFactor(hour: Int): Double = when {
        hour in 22..23 || hour in 0..5 -> 1.0
        hour in 13..16 -> 0.7
        else -> 0.4
    }

    private fun predict(f: FatigueFeatures): Double {
        val raw = FEATURE_NAMES.sumOf { fatigueWeights[it]!! * f.featureValue(it) }
        val weightSum = fatigueWeights.values.sum().coerceAtLeast(1e-6)
        return (raw / weightSum * 100.0).coerceIn(0.0, 100.0)
    }

    private fun updateFatigue(f: FatigueFeatures, feedback: String) {
        fatigueTrainCount++
        val lr = LEARNING_RATE / (1.0 + fatigueTrainCount * 0.01)
        when (feedback) {
            "snooze" -> {
                FEATURE_NAMES.forEach { name ->
                    val value = f.featureValue(name)
                    fatigueWeights[name] = fatigueWeights[name]!! * (1.0 - lr * value * 0.5)
                }
            }
            "complete" -> {
                FEATURE_NAMES.forEach { name ->
                    val default = DEFAULT_WEIGHTS[name]!!
                    fatigueWeights[name] = fatigueWeights[name]!! +
                        (default - fatigueWeights[name]!!) * lr * 0.1
                }
            }
        }
        // 裁剪
        fatigueWeights.forEach { (k, v) ->
            fatigueWeights[k] = v.coerceIn(0.01, 0.6)
        }
    }

    // ================== 老虎机 ==================

    private fun selectBandit(currentInterval: Int): Int {
        val total = banditCounts.values.sum()
        if (total < CANDIDATE_INTERVALS.size) {
            val untried = CANDIDATE_INTERVALS.filter { banditCounts[it] == 0 }
            if (untried.isNotEmpty()) {
                return untried.minBy { kotlin.math.abs(it - currentInterval) }
            }
        }
        var best = currentInterval
        var bestScore = -1.0
        for (interval in CANDIDATE_INTERVALS) {
            val n = banditCounts[interval]!!
            if (n == 0) return interval
            val mean = banditRewards[interval]!! / n
            val bonus = UCB1_C * sqrt(ln(total.toDouble()) / n)
            val score = mean + bonus
            if (score > bestScore) { bestScore = score; best = interval }
        }
        return best
    }

    private fun updateBandit(interval: Int, reward: Double) {
        if (!banditCounts.containsKey(interval)) return
        banditCounts[interval] = banditCounts[interval]!! + 1
        banditRewards[interval] = banditRewards[interval]!! + reward
    }

    private fun bestBanditInterval(): Int {
        val tried = CANDIDATE_INTERVALS
            .filter { banditCounts[it]!! > 0 }
            .associateWith { banditRewards[it]!! / banditCounts[it]!! }
        if (tried.isEmpty()) return 30
        return tried.entries.maxBy { it.value }.key
    }

    // ================== 持久化 ==================

    private fun save() {
        val sessions = loadSessions().toMutableList()
        // appendSession 内部已写入，这里只存模型和老虎机
        prefs.edit()
            .putString(KEY_WEIGHTS, fatigueWeights.entries.joinToString(",") { "${it.key}=${it.value}" })
            .putInt(KEY_TRAIN_COUNT, fatigueTrainCount)
            .putString(KEY_BANDIT_COUNTS, banditCounts.entries.joinToString(",") { "${it.key}=${it.value}" })
            .putString(KEY_BANDIT_REWARDS, banditRewards.entries.joinToString(",") { "${it.key}=${it.value}" })
            .putInt(KEY_SNOOZE_STREAK, snoozeStreak)
            .putLong(KEY_LAST_BREAK, (lastBreakEpochSec * 1000).toLong())
            .apply()
    }

    private fun load() {
        // weights
        prefs.getString(KEY_WEIGHTS, null)?.let { str ->
            runCatching {
                str.split(",").forEach { kv ->
                    val (k, v) = kv.split("=", limit = 2)
                    DEFAULT_WEIGHTS.keys.firstOrNull { it == k }?.let { fatigueWeights[it] = v.toDouble() }
                }
            }
        }
        fatigueTrainCount = prefs.getInt(KEY_TRAIN_COUNT, 0)
        // bandit
        prefs.getString(KEY_BANDIT_COUNTS, null)?.let { str ->
            runCatching {
                str.split(",").forEach { kv ->
                    val (k, v) = kv.split("=", limit = 2)
                    banditCounts[k.toInt()] = v.toInt()
                }
            }
        }
        prefs.getString(KEY_BANDIT_REWARDS, null)?.let { str ->
            runCatching {
                str.split(",").forEach { kv ->
                    val (k, v) = kv.split("=", limit = 2)
                    banditRewards[k.toInt()] = v.toDouble()
                }
            }
        }
        snoozeStreak = prefs.getInt(KEY_SNOOZE_STREAK, 0)
        lastBreakEpochSec = prefs.getLong(KEY_LAST_BREAK, System.currentTimeMillis()).toDouble() / 1000.0
    }

    private fun appendSession(record: SessionRecord) {
        val sessions = loadSessions().toMutableList()
        sessions.add(record)
        // 只保留最近 300 条，避免 SharedPreferences 膨胀
        if (sessions.size > 300) sessions.subList(0, sessions.size - 300).clear()
        val json = sessions.joinToString(";") { it.toCompact() }
        prefs.edit().putString(KEY_SESSIONS, json).apply()
    }

    private fun loadSessions(): List<SessionRecord> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return raw.split(";").mapNotNull { runCatching { SessionRecord.fromCompact(it) }.getOrNull() }
    }

    private fun startOfDayEpochSec(): Double {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis / 1000.0
    }

    // ================== 数据类 ==================

    data class FatigueFeatures(
        val consecutiveMinutes: Double,
        val timeSinceBreak: Double,
        val snoozeStreak: Double,
        val hourFactor: Double,
        val idleRatio: Double,
    ) {
        fun featureValue(name: String): Double = when (name) {
            "consecutive_minutes" -> consecutiveMinutes
            "time_since_break" -> timeSinceBreak
            "snooze_streak" -> snoozeStreak.toDouble()
            "hour_factor" -> hourFactor
            "idle_ratio" -> idleRatio
            else -> 0.0
        }
    }

    data class SessionRecord(
        val timestamp: Double,
        val screenMinutes: Double,
        val idleRatio: Double,
        var snoozed: Boolean,
        var completed: Boolean,
        val hour: Int,
        val weekday: Int,
    ) {
        /** 紧凑序列化（短字段名 + ~ 分隔）。 */
        fun toCompact(): String =
            "$timestamp~$screenMinutes~$idleRatio~${if (snoozed) 1 else 0}~${if (completed) 1 else 0}~$hour~$weekday"

        companion object {
            fun fromCompact(s: String): SessionRecord? {
                val parts = s.split("~")
                if (parts.size < 7) return null
                return SessionRecord(
                    timestamp = parts[0].toDoubleOrNull() ?: return null,
                    screenMinutes = parts[1].toDoubleOrNull() ?: 0.0,
                    idleRatio = parts[2].toDoubleOrNull() ?: 1.0,
                    snoozed = parts[3] == "1",
                    completed = parts[4] == "1",
                    hour = parts[5].toIntOrNull() ?: 0,
                    weekday = parts[6].toIntOrNull() ?: 0,
                )
            }
        }
    }

    data class DailyStats(
        val breaks: Int,
        val snoozes: Int,
        val totalScreenMinutes: Double,
        val bestInterval: Int,
        val fatigueTrainingSamples: Int,
    )

    companion object {
        private const val PREFS_NAME = "eyecare_smart"
        private const val KEY_WEIGHTS = "weights"
        private const val KEY_TRAIN_COUNT = "train_count"
        private const val KEY_BANDIT_COUNTS = "bandit_counts"
        private const val KEY_BANDIT_REWARDS = "bandit_rewards"
        private const val KEY_SNOOZE_STREAK = "snooze_streak"
        private const val KEY_LAST_BREAK = "last_break"
        private const val KEY_SESSIONS = "sessions"

        private const val LEARNING_RATE = 0.05
        private const val UCB1_C = 1.41 // sqrt(2)

        val CANDIDATE_INTERVALS = listOf(20, 25, 30, 35, 40, 45, 50, 60)

        val DEFAULT_WEIGHTS = linkedMapOf(
            "consecutive_minutes" to 0.35,
            "time_since_break" to 0.20,
            "snooze_streak" to 0.18,
            "hour_factor" to 0.17,
            "idle_ratio" to 0.10,
        )

        val FEATURE_NAMES = DEFAULT_WEIGHTS.keys.toList()
    }
}

// ---------------------------------------------------------------------------
// 洞察生成器（与桌面端 InsightGenerator 逻辑一致）
// ---------------------------------------------------------------------------

class InsightGenerator(private val records: List<SmartEngine.SessionRecord>) {

    fun generate(): List<String> {
        val out = mutableListOf<String>()
        if (records.isEmpty()) {
            out += "还没有足够的使用数据，多用几天我会更了解你的用眼习惯～"
            return out
        }
        val recent = records.takeLast(30)

        // 1. 延迟率
        val snoozeRate = recent.count { it.snoozed }.toDouble() / recent.size
        when {
            snoozeRate > 0.5 -> out += "你有 ${(snoozeRate * 100).toInt()}% 的提醒被延迟，可能当前间隔偏短，AI 已自动学习更适合你的节奏。"
            snoozeRate < 0.1 -> out += "你几乎从不延迟提醒，护眼习惯很棒，继续保持！"
        }

        // 2. 高峰时段
        val hourCounts = recent.groupingBy { it.hour }.eachCount()
        hourCounts.maxByOrNull { it.value }?.let { (hour, _) ->
            out += "你最常在 ${hour}:00 左右被提醒，这是你的用眼高峰时段。"
        }

        // 3. 平均屏幕时长
        val avgScreen = recent.map { it.screenMinutes }.average()
        when {
            avgScreen > 40 -> out += "平均每次连续用屏 ${"%.0f".format(avgScreen)} 分钟，建议适当缩短单次时长。"
            avgScreen < 20 -> out += "平均每次用屏仅 ${"%.0f".format(avgScreen)} 分钟，节奏很健康。"
        }

        // 4. 工作日 vs 周末
        val weekday = recent.filter { it.weekday < 5 }
        val weekend = recent.filter { it.weekday >= 5 }
        if (weekday.isNotEmpty() && weekend.isNotEmpty()) {
            val wd = weekday.map { it.screenMinutes }.average()
            val we = weekend.map { it.screenMinutes }.average()
            if (wd > we + 10) {
                out += "工作日平均用屏 ${"%.0f".format(wd)} 分钟，比周末多不少，工作日更要注意休息。"
            }
        }

        // 5. 深夜
        val lateNight = recent.count { it.hour >= 22 || it.hour < 6 }
        if (lateNight > 0) {
            out += "检测到 $lateNight 次深夜用眼，夜间蓝光对眼睛刺激更大，建议调低屏幕亮度。"
        }

        if (out.isEmpty()) {
            out += "目前数据还不多，再多用几天我能给你更准确的建议～"
        }
        return out
    }
}
