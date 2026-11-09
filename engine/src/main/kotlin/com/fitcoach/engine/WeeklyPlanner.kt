package com.fitcoach.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

enum class DayKind { REST, EASY, MODERATE, HARD }

data class PlannedDay(
    val date: LocalDate,
    val kind: DayKind,
    val targetLoad: Double,
    val minutes: Int,
    /** 已经过去的日子：实际完成的负荷；今天和以后为 null。 */
    val actualLoad: Double? = null,
) {
    val isPast: Boolean get() = actualLoad != null
}

enum class WeekNote { NONE, TARGET_REACHED, ILLNESS }

data class WeekPlan(
    val weekStart: LocalDate,
    val targetLoad: Double,
    val completedLoad: Double,
    val days: List<PlannedDay>,
    val note: WeekNote,
) {
    val progress: Double get() = if (targetLoad <= 0) 1.0 else (completedLoad / targetLoad).coerceIn(0.0, 1.0)
}

/**
 * 把当天建议扩展成一周（周一到周日）计划：
 * - 本周目标负荷 = 慢性负荷 × 7 × 系数（系数由 ACWR 基础等级决定，不因某一天的恢复波动而大起大落）；
 * - 已过去的日子按实际负荷分类；今天沿用当天建议；剩余日子按"强弱交替、强度日后不接强度日、
 *   每周至少 1 天（50 岁以上 2 天）休息、每周最多 2 个强度日"排布，再按权重分配剩余负荷。
 */
object WeeklyPlanner {

    private const val TRAINING_DAY_LOAD = 40.0
    private const val MAX_HARD_DAYS = 2

    private val weights = mapOf(DayKind.REST to 0.0, DayKind.EASY to 0.6, DayKind.MODERATE to 1.0, DayKind.HARD to 1.4)
    private val zones = mapOf(
        DayKind.EASY to (0.50 to 0.60),
        DayKind.MODERATE to (0.60 to 0.70),
        DayKind.HARD to (0.70 to 0.85),
    )

    fun plan(r: Recommendation): WeekPlan {
        val forDate = r.forDate
        val weekStart = forDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekDates = (0L..6L).map { weekStart.plusDays(it) }
        val loadsByDate = r.dailyLoads.associate { it.date to it.load }
        val sex = r.profile.sex
        val typical = r.typicalTrainingLoad ?: (30 * TrainingLoad.trimpPerMinute(0.65, sex))
        val minRest = if ((r.profile.age ?: 0) >= 50) 2 else 1

        val factor = when {
            r.possibleIllness -> 0.6
            r.baseLevel == Level.INCREASE -> if ((r.profile.age ?: 0) >= 50) 1.05 else 1.10
            r.baseLevel == Level.MAINTAIN -> 1.0
            else -> 0.85
        }
        val weeklyTarget = if (r.chronicLoad >= 5.0) r.chronicLoad * 7 * factor else typical * 3

        // ---- 已过去的日子 ----
        val past = weekDates.filter { it < forDate }.map { date ->
            val load = loadsByDate[date] ?: 0.0
            PlannedDay(date, classify(load, typical), load, 0, actualLoad = load)
        }
        val completed = past.sumOf { it.actualLoad ?: 0.0 }
        var restLeft = (minRest - past.count { it.kind == DayKind.REST }).coerceAtLeast(0)
        var hardLeft = (MAX_HARD_DAYS - past.count { it.kind == DayKind.HARD }).coerceAtLeast(0)

        // ---- 今天：沿用当天建议 ----
        val todayKind = when (r.level) {
            Level.REST -> DayKind.REST
            Level.DECREASE -> DayKind.EASY
            Level.MAINTAIN -> DayKind.MODERATE
            Level.INCREASE -> if (hardLeft > 0) DayKind.HARD else DayKind.MODERATE
        }
        if (todayKind == DayKind.REST) restLeft = (restLeft - 1).coerceAtLeast(0)
        if (todayKind == DayKind.HARD) hardLeft--
        val today = PlannedDay(forDate, todayKind, r.targetLoad, r.suggestedMinutes)

        // ---- 剩余日子 ----
        val futureDates = weekDates.filter { it > forDate }
        val remaining = weeklyTarget - completed - r.targetLoad
        val note = when {
            r.possibleIllness -> WeekNote.ILLNESS
            remaining <= 0 -> WeekNote.TARGET_REACHED
            else -> WeekNote.NONE
        }

        val kinds = mutableListOf<DayKind>()
        var prev = todayKind
        futureDates.forEachIndexed { i, _ ->
            val daysLeft = futureDates.size - i
            val kind = when {
                note == WeekNote.ILLNESS -> if (prev == DayKind.REST && i > 0) DayKind.EASY else DayKind.REST
                restLeft > 0 && (prev == DayKind.HARD || daysLeft <= restLeft) -> DayKind.REST
                prev == DayKind.HARD -> DayKind.EASY
                note == WeekNote.TARGET_REACHED -> if (prev == DayKind.EASY) DayKind.REST else DayKind.EASY
                hardLeft > 0 && (prev == DayKind.EASY || prev == DayKind.REST) -> DayKind.HARD
                prev == DayKind.MODERATE -> DayKind.EASY
                else -> DayKind.MODERATE
            }
            if (kind == DayKind.REST) restLeft = (restLeft - 1).coerceAtLeast(0)
            if (kind == DayKind.HARD) hardLeft--
            kinds += kind
            prev = kind
        }

        val totalWeight = kinds.sumOf { weights.getValue(it) }
        val future = futureDates.zip(kinds) { date, kind ->
            val load = when {
                kind == DayKind.REST -> 0.0
                note != WeekNote.NONE || totalWeight == 0.0 -> typical * 0.5
                // 单日不超过平时训练量的 1.5 倍，避免把一周欠下的量堆到一天
                else -> (remaining * weights.getValue(kind) / totalWeight).coerceAtMost(typical * 1.5)
            }
            PlannedDay(date, kind, load, minutesFor(kind, load, sex))
        }

        return WeekPlan(weekStart, weeklyTarget, completed, past + today + future, note)
    }

    private fun classify(load: Double, typical: Double): DayKind = when {
        load < TRAINING_DAY_LOAD -> DayKind.REST
        load < typical * 0.8 -> DayKind.EASY
        load < typical * 1.3 -> DayKind.MODERATE
        else -> DayKind.HARD
    }

    private fun minutesFor(kind: DayKind, load: Double, sex: Sex?): Int {
        val zone = zones[kind] ?: return 0
        if (load <= 0) return 0
        val perMinute = TrainingLoad.trimpPerMinute((zone.first + zone.second) / 2, sex)
        return ((load / perMinute / 5).roundToInt() * 5).coerceIn(10, 120)
    }
}
