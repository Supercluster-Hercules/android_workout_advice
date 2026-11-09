package com.fitcoach.engine.cache

import com.fitcoach.engine.DailyMetrics
import com.fitcoach.engine.ExerciseSession
import java.time.LocalDate

/*
 * 本地数据缓存与增量合并逻辑（纯 Kotlin，不依赖 Android，便于单元测试）。
 *
 * 去重原理：
 * - 每日汇总以「日期」为键，运动记录以 Health Connect 的「记录 id」为键，同一天 / 同一条记录只会存一份；
 * - 更新某段日期时，先整体删掉缓存中这段日期的旧数据，再写入新读到的数据（整段替换），
 *   所以重复同步、同步中途被打断再重来，结果都一样，不会重复累加。
 */

/** 某一天的汇总值（运动记录单独存）。 */
data class CachedDay(
    val steps: Int? = null,
    val restingHeartRate: Double? = null,
    val hrvRmssd: Double? = null,
    val sleepMinutes: Double? = null,
) {
    val isEmpty: Boolean
        get() = steps == null && restingHeartRate == null && hrvRmssd == null && sleepMinutes == null
}

data class CachedSession(
    /** Health Connect 记录 id，全局唯一。 */
    val id: String,
    val date: LocalDate,
    val durationMinutes: Double,
    val avgHeartRate: Double?,
    val type: String?,
    /** 记录最后修改时间；没变就可以复用之前算好的平均心率。 */
    val lastModifiedEpochMs: Long,
)

enum class RecordKind { STEPS, EXERCISE, RESTING_HEART_RATE, HRV, SLEEP }

/** Health Connect 变更记录的精简版。 */
sealed interface Change {
    data class Upsert(val kind: RecordKind, val id: String, val dates: Set<LocalDate>) : Change
    data class Delete(val id: String) : Change
}

data class SyncPlan(
    /** 需要重新读取的日期。 */
    val dirtyDates: Set<LocalDate>,
    /** 有无法定位日期的删除（步数 / 静息心率记录），需要重新聚合整个窗口的步数和静息心率。 */
    val refreshAllAggregates: Boolean,
) {
    val isEmpty: Boolean get() = dirtyDates.isEmpty() && !refreshAllAggregates
}

/** 日期区间 [start, end]（含两端）的完整快照，用来整体替换缓存里这段区间的数据。 */
data class RangeSnapshot(
    val start: LocalDate,
    val end: LocalDate,
    val steps: Map<LocalDate, Int> = emptyMap(),
    val restingHeartRate: Map<LocalDate, Double> = emptyMap(),
    val hrv: Map<LocalDate, Double> = emptyMap(),
    val sleepMinutes: Map<LocalDate, Double> = emptyMap(),
    val sessions: List<CachedSession> = emptyList(),
    /** HRV / 睡眠记录的 id → 所属日期，之后收到删除通知时用来定位日期。 */
    val recordDates: Map<String, LocalDate> = emptyMap(),
)

class MetricsCache(
    days: Map<LocalDate, CachedDay> = emptyMap(),
    sessions: Map<String, CachedSession> = emptyMap(),
    recordDates: Map<String, LocalDate> = emptyMap(),
    /** Health Connect 变更令牌；null 表示还没做过完整导入。 */
    var changesToken: String? = null,
    /** 生成令牌时已授权的权限；权限变了必须重新完整导入。 */
    var tokenPermissions: Set<String> = emptySet(),
    var lastSyncEpochMs: Long = 0,
) {
    private val _days = days.toMutableMap()
    private val _sessions = sessions.toMutableMap()
    private val _recordDates = recordDates.toMutableMap()

    val days: Map<LocalDate, CachedDay> get() = _days
    val sessions: Map<String, CachedSession> get() = _sessions
    val recordDates: Map<String, LocalDate> get() = _recordDates

    val hasCompletedFullSync: Boolean get() = changesToken != null

    fun clear() {
        _days.clear()
        _sessions.clear()
        _recordDates.clear()
        changesToken = null
        tokenPermissions = emptySet()
    }

    /** 用快照整体替换 [RangeSnapshot.start]..[RangeSnapshot.end] 的数据。 */
    fun applySnapshot(s: RangeSnapshot) {
        val range = s.start..s.end
        for (date in dates(s.start, s.end)) {
            putDay(date, CachedDay(s.steps[date], s.restingHeartRate[date], s.hrv[date], s.sleepMinutes[date]))
        }
        _sessions.values.removeAll { it.date in range }
        s.sessions.filter { it.date in range }.forEach { _sessions[it.id] = it }
        _recordDates.values.removeAll { it in range }
        _recordDates.putAll(s.recordDates.filterValues { it in range })
    }

    /** 只替换步数和静息心率（这两项由 Health Connect 按天聚合，不跟踪单条记录）。 */
    fun applyAggregates(
        start: LocalDate,
        end: LocalDate,
        steps: Map<LocalDate, Int>,
        restingHeartRate: Map<LocalDate, Double>,
    ) {
        for (date in dates(start, end)) {
            val old = _days[date] ?: CachedDay()
            putDay(date, old.copy(steps = steps[date], restingHeartRate = restingHeartRate[date]))
        }
    }

    /** 根据变更记录算出需要重新读取哪些日期。窗口 [windowStart, today] 之外的变更忽略。 */
    fun plan(changes: List<Change>, windowStart: LocalDate, today: LocalDate): SyncPlan {
        val window = windowStart..today
        val dirty = mutableSetOf<LocalDate>()
        var refreshAll = false
        for (change in changes) {
            when (change) {
                is Change.Upsert -> dirty += change.dates.filter { it in window }
                is Change.Delete -> {
                    val date = _sessions[change.id]?.date ?: _recordDates[change.id]
                    if (date != null) {
                        if (date in window) dirty += date
                    } else {
                        // 可能是步数或静息心率记录被删了，我们不知道它在哪天
                        refreshAll = true
                    }
                }
            }
        }
        return SyncPlan(dirty, refreshAll)
    }

    /**
     * 如果这条运动记录之前算过平均心率且没被修改过，就直接复用，省掉一次查询。
     * 最近两天的记录除外：手表可能还在陆续同步心率数据。
     */
    fun reusableSession(id: String, lastModifiedEpochMs: Long, date: LocalDate, today: LocalDate): CachedSession? =
        _sessions[id]?.takeIf { it.lastModifiedEpochMs == lastModifiedEpochMs && date < today.minusDays(1) }

    fun toDailyMetrics(start: LocalDate, end: LocalDate): List<DailyMetrics> {
        val sessionsByDate = _sessions.values.groupBy { it.date }
        return dates(start, end).map { date ->
            val day = _days[date] ?: CachedDay()
            DailyMetrics(
                date = date,
                steps = day.steps,
                sessions = sessionsByDate[date].orEmpty().map {
                    ExerciseSession(it.durationMinutes, it.avgHeartRate, it.type)
                },
                restingHeartRate = day.restingHeartRate,
                hrvRmssd = day.hrvRmssd,
                sleepMinutes = day.sleepMinutes,
            )
        }.toList()
    }

    /** 删除 [keepFrom] 之前的数据，避免缓存无限增长。 */
    fun prune(keepFrom: LocalDate) {
        _days.keys.removeAll { it < keepFrom }
        _sessions.values.removeAll { it.date < keepFrom }
        _recordDates.values.removeAll { it < keepFrom }
    }

    private fun putDay(date: LocalDate, day: CachedDay) {
        if (day.isEmpty) _days.remove(date) else _days[date] = day
    }
}

fun dates(start: LocalDate, end: LocalDate): Sequence<LocalDate> =
    generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }

/** 把零散日期合并成连续区间，例如 {1,2,3,7} → [1..3, 7..7]，每个区间只需读取一次。 */
fun contiguousRanges(dates: Set<LocalDate>): List<Pair<LocalDate, LocalDate>> {
    val result = mutableListOf<Pair<LocalDate, LocalDate>>()
    for (date in dates.sorted()) {
        val last = result.lastOrNull()
        if (last != null && last.second.plusDays(1) == date) {
            result[result.lastIndex] = last.first to date
        } else {
            result += date to date
        }
    }
    return result
}
