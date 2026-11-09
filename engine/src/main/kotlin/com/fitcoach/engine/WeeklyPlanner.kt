package com.fitcoach.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

enum class DayKind { REST, EASY, MODERATE, HARD }

/** 训练日的内容。未设置目标时所有训练日都是有氧。 */
enum class SessionFocus { REST, CARDIO, STRENGTH_UPPER, STRENGTH_LOWER, STRENGTH_FULL }

data class PlannedDay(
    val date: LocalDate,
    val kind: DayKind,
    val targetLoad: Double,
    val minutes: Int,
    /** 已经过去的日子：实际完成的负荷；今天和以后为 null。 */
    val actualLoad: Double? = null,
    val focus: SessionFocus = if (kind == DayKind.REST) SessionFocus.REST else SessionFocus.CARDIO,
) {
    val isPast: Boolean get() = actualLoad != null
    val isStrength: Boolean get() = WeeklyPlanner.isStrength(focus)
}

enum class WeekNote { NONE, TARGET_REACHED, ILLNESS }

data class WeekPlan(
    val weekStart: LocalDate,
    val targetLoad: Double,
    val completedLoad: Double,
    val days: List<PlannedDay>,
    val note: WeekNote,
    val goal: Goal? = null,
    /** 本周已完成的有氧分钟数（不含力量训练）。 */
    val aerobicMinutesDone: Double = 0.0,
    /** 今天和之后计划的有氧分钟数。 */
    val aerobicMinutesPlanned: Int = 0,
) {
    val progress: Double get() = if (targetLoad <= 0) 1.0 else (completedLoad / targetLoad).coerceIn(0.0, 1.0)
    val aerobicMinutesTotal: Int get() = aerobicMinutesDone.roundToInt() + aerobicMinutesPlanned
}

/**
 * 把当天建议扩展成一周（周一到周日）计划：
 * - 本周目标负荷 = 慢性负荷 × 7 × 系数（系数由 ACWR 基础等级决定，不因某一天的恢复波动而大起大落）；
 * - 已过去的日子按实际负荷分类；今天沿用当天建议；剩余日子按"强弱交替、强度日后不接强度日、
 *   每周至少 1 天（50 岁以上 2 天）休息、每周最多 2 个强度日"排布，再按权重分配剩余负荷。
 *
 * 以上（强弱、休息、负荷）是安全层，与训练目标无关。训练目标只决定每个训练日练什么：
 * - 增肌：上下肢分化，每个部位每周 2 次，同一部位两次之间至少隔 48 小时；今天的部位还要参考肌肉恢复估算；
 * - 减脂 / 保持健康：每周 2 个全身力量日（间隔 ≥ 48 小时，尽量不放在强度日），其余为有氧。
 */
object WeeklyPlanner {

    private const val TRAINING_DAY_LOAD = 40.0
    private const val MAX_HARD_DAYS = 2
    /** 同一部位两次力量训练之间至少相隔的天数（48 小时）。 */
    const val MIN_DAYS_BETWEEN_SAME_GROUP = 2L
    /** 每个部位（增肌）或全身力量（减脂 / 保持）每周的次数。 */
    const val STRENGTH_SESSIONS_PER_WEEK = 2

    private val weights = mapOf(DayKind.REST to 0.0, DayKind.EASY to 0.6, DayKind.MODERATE to 1.0, DayKind.HARD to 1.4)
    private val zones = mapOf(
        DayKind.EASY to (0.50 to 0.60),
        DayKind.MODERATE to (0.60 to 0.70),
        DayKind.HARD to (0.70 to 0.85),
    )

    fun plan(
        r: Recommendation,
        strength: List<StrengthSession> = emptyList(),
        muscles: MuscleReport? = null,
    ): WeekPlan {
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
            val kind = classify(load, typical)
            PlannedDay(date, kind, load, 0, actualLoad = load, focus = focusOfPastDay(date, kind, strength))
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
        val futureLoads = kinds.map { kind ->
            when {
                kind == DayKind.REST -> 0.0
                note != WeekNote.NONE || totalWeight == 0.0 -> typical * 0.5
                // 单日不超过平时训练量的 1.5 倍，避免把一周欠下的量堆到一天
                else -> (remaining * weights.getValue(kind) / totalWeight).coerceAtMost(typical * 1.5)
            }
        }

        // ---- 训练目标：只给每个训练日指定内容，不改变强弱和负荷 ----
        val goal = r.profile.goal
        val upcoming = listOf(forDate to todayKind) + futureDates.zip(kinds)
        val focuses = if (goal == null || note == WeekNote.ILLNESS) {
            upcoming.map { (_, kind) -> defaultFocus(kind) }
        } else {
            assignFocus(goal, forDate, weekStart, upcoming, strength, muscles)
        }

        val todayFocus = focuses.first()
        val today = PlannedDay(
            forDate,
            todayKind,
            r.targetLoad,
            if (isStrength(todayFocus)) strengthMinutes(todayKind, r.targetLoad, sex) else r.suggestedMinutes,
            focus = todayFocus,
        )
        val future = futureDates.indices.map { i ->
            val focus = focuses[i + 1]
            val kind = kinds[i]
            val load = futureLoads[i]
            val minutes = if (isStrength(focus)) strengthMinutes(kind, load, sex) else minutesFor(kind, load, sex)
            PlannedDay(futureDates[i], kind, load, minutes, focus = focus)
        }

        val done = r.dailyLoads.filter { it.date >= weekStart && it.date < forDate }.sumOf { it.aerobicMinutes }
        val planned = (listOf(today) + future).filter { it.focus == SessionFocus.CARDIO }.sumOf { it.minutes }

        return WeekPlan(
            weekStart = weekStart,
            targetLoad = weeklyTarget,
            completedLoad = completed,
            days = past + today + future,
            note = note,
            goal = goal,
            aerobicMinutesDone = done,
            aerobicMinutesPlanned = planned,
        )
    }

    fun isStrength(f: SessionFocus) =
        f == SessionFocus.STRENGTH_UPPER || f == SessionFocus.STRENGTH_LOWER || f == SessionFocus.STRENGTH_FULL

    private fun defaultFocus(kind: DayKind) = if (kind == DayKind.REST) SessionFocus.REST else SessionFocus.CARDIO

    private fun focusOf(regions: Set<BodyRegion>): SessionFocus = when {
        BodyRegion.UPPER in regions && BodyRegion.LOWER in regions -> SessionFocus.STRENGTH_FULL
        BodyRegion.UPPER in regions -> SessionFocus.STRENGTH_UPPER
        BodyRegion.LOWER in regions -> SessionFocus.STRENGTH_LOWER
        else -> SessionFocus.STRENGTH_FULL
    }

    private fun focusOfPastDay(date: LocalDate, kind: DayKind, strength: List<StrengthSession>): SessionFocus {
        val logged = strength.filter { it.date == date }
        return when {
            logged.isNotEmpty() -> focusOf(logged.flatMap { it.regions() }.toSet())
            kind == DayKind.REST -> SessionFocus.REST
            else -> SessionFocus.CARDIO
        }
    }

    /**
     * 按目标给今天和之后的训练日分配内容（休息日保持休息）。
     * 已记录的力量训练（包括上周末的）会计入本周次数和 48 小时间隔。
     */
    private fun assignFocus(
        goal: Goal,
        forDate: LocalDate,
        weekStart: LocalDate,
        upcoming: List<Pair<LocalDate, DayKind>>,
        strength: List<StrengthSession>,
        muscles: MuscleReport?,
    ): List<SessionFocus> {
        val count = mutableMapOf(BodyRegion.UPPER to 0, BodyRegion.LOWER to 0)
        val last = mutableMapOf<BodyRegion, LocalDate>()
        var sessionsThisWeek = 0
        var lastAny: LocalDate? = null
        strength.filter { it.date < forDate }.sortedBy { it.start }.forEach { s ->
            val regions = s.regions()
            if (s.date >= weekStart) {
                regions.forEach { count[it] = count.getValue(it) + 1 }
                sessionsThisWeek++
            }
            regions.forEach { last[it] = s.date }
            lastAny = s.date
        }

        fun spaced(prev: LocalDate?, date: LocalDate) = prev == null || prev.plusDays(MIN_DAYS_BETWEEN_SAME_GROUP) <= date

        // 今天的部位还要看肌肉恢复估算
        fun ready(region: BodyRegion, date: LocalDate) = date != forDate || muscles == null || muscles.regionReady(region)

        val regionsAll = listOf(BodyRegion.UPPER, BodyRegion.LOWER)
        return upcoming.mapIndexed { i, (date, kind) ->
            if (kind == DayKind.REST) return@mapIndexed SessionFocus.REST
            when (goal) {
                Goal.MUSCLE_GAIN -> {
                    fun need(r: BodyRegion) = STRENGTH_SESSIONS_PER_WEEK - count.getValue(r)
                    val daysLeft = upcoming.drop(i).count { it.second != DayKind.REST }
                    val feasible = regionsAll.filter { need(it) > 0 && spaced(last[it], date) && ready(it, date) }
                    val chosen: Set<BodyRegion> = when {
                        feasible.isEmpty() -> emptySet()
                        // 剩下的训练日不够分别练上下肢时，合并成全身
                        feasible.size == 2 && need(BodyRegion.UPPER) + need(BodyRegion.LOWER) > daysLeft -> feasible.toSet()
                        // 优先还差得多的部位；一样多时选更久没练的
                        else -> setOf(
                            feasible.sortedWith(
                                compareByDescending<BodyRegion> { need(it) }
                                    .thenBy { last[it]?.toEpochDay() ?: Long.MIN_VALUE },
                            ).first(),
                        )
                    }
                    chosen.forEach {
                        count[it] = count.getValue(it) + 1
                        last[it] = date
                    }
                    if (chosen.isEmpty()) SessionFocus.CARDIO else focusOf(chosen)
                }
                Goal.FAT_LOSS, Goal.MAINTAIN -> {
                    val need = STRENGTH_SESSIONS_PER_WEEK - sessionsThisWeek
                    val nonHardLeft = upcoming.drop(i + 1).count { it.second == DayKind.EASY || it.second == DayKind.MODERATE }
                    // 强度日留给有氧（间歇），除非后面的非强度日不够安排
                    val avoidHard = kind == DayKind.HARD && nonHardLeft >= need
                    val regions = regionsAll.filter { ready(it, date) }
                    if (need <= 0 || avoidHard || !spaced(lastAny, date) || regions.isEmpty()) {
                        SessionFocus.CARDIO
                    } else {
                        sessionsThisWeek++
                        lastAny = date
                        focusOf(regions.toSet())
                    }
                }
            }
        }
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

    /** 力量训练日的时长：按 RPE（轻松日 5、中等日 7、强度日 8）把目标负荷换算成分钟。 */
    fun strengthMinutes(kind: DayKind, load: Double, sex: Sex?): Int {
        if (load <= 0) return 0
        val perMinute = TrainingLoad.trimpPerMinute(TrainingLoad.hrrFromRpe(strengthRpe(kind)), sex)
        return ((load / perMinute / 5).roundToInt() * 5).coerceIn(20, 90)
    }

    fun strengthRpe(kind: DayKind): Int = when (kind) {
        DayKind.EASY -> 5
        DayKind.HARD -> 8
        else -> 7
    }
}
