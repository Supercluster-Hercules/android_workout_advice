package com.fitcoach.engine

import com.fitcoach.engine.SampleData.Scenario
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalTest {

    private val engine = RecommendationEngine()
    private val end = LocalDate.of(2026, 11, 8) // 周日
    private val monday = end.plusDays(1)

    private fun profile(goal: Goal?) = UserProfile(age = 30, sex = Sex.MALE, goal = goal)

    private fun recommend(scenario: Scenario, goal: Goal?, forDate: LocalDate = monday): Recommendation =
        engine.recommend(SampleData.generate(scenario, forDate.minusDays(1), days = 56), forDate, profile(goal))

    // ---------------- 安全层不受目标影响 ----------------

    @Test
    fun `goal never changes the safety layer`() {
        for (scenario in Scenario.entries) {
            val base = recommend(scenario, null)
            for (goal in Goal.entries) {
                val r = recommend(scenario, goal)
                assertEquals(base.level, r.level, "$scenario $goal")
                assertEquals(base.baseLevel, r.baseLevel, "$scenario $goal")
                assertEquals(base.targetLoad, r.targetLoad, 1e-9)
                assertEquals(base.suggestedMinutes, r.suggestedMinutes)
                assertEquals(base.signals, r.signals, "$scenario $goal")
                assertEquals(base.acwr, r.acwr)
            }
        }
    }

    @Test
    fun `goal keeps the weekly day kinds and loads and only changes the content`() {
        for (scenario in Scenario.entries) {
            val base = WeeklyPlanner.plan(recommend(scenario, null))
            assertTrue(base.days.all { it.focus == SessionFocus.REST || it.focus == SessionFocus.CARDIO })
            for (goal in Goal.entries) {
                val week = WeeklyPlanner.plan(recommend(scenario, goal))
                assertEquals(base.days.map { it.kind }, week.days.map { it.kind }, "$scenario $goal")
                assertEquals(base.days.map { it.targetLoad }, week.days.map { it.targetLoad }, "$scenario $goal")
                assertEquals(base.targetLoad, week.targetLoad, 1e-9)
                week.days.filter { it.kind == DayKind.REST && !it.isPast }.forEach { assertEquals(SessionFocus.REST, it.focus) }
            }
        }
    }

    // ---------------- 增肌：上下肢各 2 次，间隔 48 小时 ----------------

    private fun hits(day: PlannedDay, region: BodyRegion) = when (day.focus) {
        SessionFocus.STRENGTH_FULL -> true
        SessionFocus.STRENGTH_UPPER -> region == BodyRegion.UPPER
        SessionFocus.STRENGTH_LOWER -> region == BodyRegion.LOWER
        else -> false
    }

    @Test
    fun `muscle gain trains each region twice a week with at least 48 hours between`() {
        for (scenario in listOf(Scenario.BALANCED, Scenario.DETRAINING, Scenario.PHONE_ONLY)) {
            val week = WeeklyPlanner.plan(recommend(scenario, Goal.MUSCLE_GAIN))
            assertEquals(DayOfWeek.MONDAY, week.days.first().date.dayOfWeek)
            val trainingDays = week.days.count { it.kind != DayKind.REST }
            for (region in listOf(BodyRegion.UPPER, BodyRegion.LOWER)) {
                val dates = week.days.filter { hits(it, region) }.map { it.date }
                assertTrue(dates.size <= 2, "$scenario $region ${week.days.map { it.focus }}")
                if (trainingDays >= 4) assertEquals(2, dates.size, "$scenario $region ${week.days.map { it.focus }}")
                dates.zipWithNext().forEach { (a, b) ->
                    assertTrue(ChronoUnit.DAYS.between(a, b) >= 2, "$scenario $region $dates")
                }
            }
        }
    }

    @Test
    fun `muscle gain avoids the region that is still recovering today`() {
        val forDate = monday
        val legs = StrengthSession(
            "legs", forDate.minusDays(1).atTime(18, 0), 60.0, 9,
            listOf(ExerciseEntry("squat", 6, 5, 100.0), ExerciseEntry("leg_press", 4, 10, 150.0)),
        )
        val history = StrengthLoad.mergeInto(SampleData.generate(Scenario.DETRAINING, forDate.minusDays(1), 56), listOf(legs))
        val r = engine.recommend(history, forDate, profile(Goal.MUSCLE_GAIN))
        val muscles = MuscleFatigueModel.estimate(history, listOf(legs), emptyList(), r.profile, forDate.atTime(8, 0))
        assertEquals(TrainingFocus.UPPER_BODY, muscles.focus)

        val week = WeeklyPlanner.plan(r, listOf(legs), muscles)
        val today = week.days.single { it.date == forDate }
        assertTrue(today.focus != SessionFocus.STRENGTH_LOWER && today.focus != SessionFocus.STRENGTH_FULL, "${today.focus}")
        // 昨天（周日）练过下肢：最早周二再练下肢（48 小时）
        val firstLower = week.days.firstOrNull { !it.isPast && hits(it, BodyRegion.LOWER) }
        if (firstLower != null) assertTrue(firstLower.date >= forDate.plusDays(1), "${firstLower.date}")
    }

    // ---------------- 减脂 / 保持：2 个全身力量日 ----------------

    @Test
    fun `fat loss and maintain plan two spaced strength days and keep hard days for cardio`() {
        for (goal in listOf(Goal.FAT_LOSS, Goal.MAINTAIN)) {
            for (scenario in listOf(Scenario.BALANCED, Scenario.DETRAINING)) {
                val week = WeeklyPlanner.plan(recommend(scenario, goal))
                val strengthDays = week.days.filter { it.isStrength }
                assertTrue(strengthDays.size <= 2, "$goal $scenario ${week.days.map { it.focus }}")
                if (week.days.count { it.kind == DayKind.EASY || it.kind == DayKind.MODERATE } >= 3) {
                    assertEquals(2, strengthDays.size, "$goal $scenario ${week.days.map { it.kind to it.focus }}")
                    assertTrue(strengthDays.none { it.kind == DayKind.HARD }, "$goal $scenario")
                }
                strengthDays.zipWithNext().forEach { (a, b) ->
                    assertTrue(ChronoUnit.DAYS.between(a.date, b.date) >= 2)
                }
                strengthDays.filter { !it.isPast }.forEach { assertTrue(it.minutes in 20..90) }
            }
        }
    }

    @Test
    fun `strength already logged this week counts toward the two sessions`() {
        val forDate = end.plusDays(3) // 周三
        val logged = listOf(
            StrengthSession("m", forDate.minusDays(2).atTime(18, 0), 50.0, 7, listOf(ExerciseEntry("squat", 3, 8, 60.0), ExerciseEntry("bench_press", 3, 8, 50.0))),
        )
        val history = StrengthLoad.mergeInto(SampleData.generate(Scenario.BALANCED, forDate.minusDays(1), 56), logged)
        val r = engine.recommend(history, forDate, profile(Goal.MAINTAIN))
        val week = WeeklyPlanner.plan(r, logged)
        assertEquals(SessionFocus.STRENGTH_FULL, week.days.single { it.date == forDate.minusDays(2) }.focus)
        assertTrue(week.days.count { it.isStrength } <= 2)
    }

    @Test
    fun `maintain reports aerobic minutes for the WHO comparison`() {
        val forDate = end.plusDays(4) // 周四
        val r = recommend(Scenario.BALANCED, Goal.MAINTAIN, forDate)
        val week = WeeklyPlanner.plan(r)
        val expectedDone = r.dailyLoads.filter { it.date >= week.weekStart && it.date < forDate }.sumOf { it.aerobicMinutes }
        assertTrue(expectedDone > 0)
        assertEquals(expectedDone, week.aerobicMinutesDone, 1e-9)
        val planned = week.days.filter { !it.isPast && it.focus == SessionFocus.CARDIO }.sumOf { it.minutes }
        assertEquals(planned, week.aerobicMinutesPlanned)
    }

    // ---------------- 今天的内容 ----------------

    private fun prescription(scenario: Scenario, goal: Goal): Pair<Recommendation, Prescription> {
        val history = SampleData.generate(scenario, monday.minusDays(1), 56)
        val r = engine.recommend(history, monday, profile(goal))
        val week = WeeklyPlanner.plan(r)
        val today = week.days.single { it.date == monday }
        return r to GoalAdvisor.prescribe(r, today, null, GoalAdvisor.recentAvgSteps(history, r))
    }

    @Test
    fun `increase means more steps for fat loss and more sets for muscle gain`() {
        val (r, fat) = prescription(Scenario.DETRAINING, Goal.FAT_LOSS)
        assertEquals(Level.INCREASE, r.level)
        val history = SampleData.generate(Scenario.DETRAINING, monday.minusDays(1), 56)
        val avg = GoalAdvisor.recentAvgSteps(history, r)!!
        assertEquals(((avg + 2000) / 500).let { Math.round(it).toInt() * 500 }, fat.stepTarget)
        assertEquals(0, fat.setsDeltaPerExercise)
        assertEquals(0.8, fat.easyShare)

        val (_, muscle) = prescription(Scenario.DETRAINING, Goal.MUSCLE_GAIN)
        assertEquals(1, muscle.setsDeltaPerExercise)
        assertEquals(0.025, muscle.weightIncreasePct)
        assertEquals(null, muscle.stepTarget)
    }

    @Test
    fun `decrease removes a set for muscle gain and rest means recovery`() {
        val (r, p) = prescription(Scenario.OVERREACHING, Goal.MUSCLE_GAIN)
        if (r.level == Level.DECREASE) assertEquals(-1, p.setsDeltaPerExercise)
        if (r.level == Level.REST) assertEquals(Modality.RECOVERY, p.modality)

        val (illR, ill) = prescription(Scenario.FATIGUED, Goal.FAT_LOSS)
        if (illR.level == Level.REST) {
            assertEquals(Modality.RECOVERY, ill.modality)
            assertEquals(0, ill.aerobicMinutes + ill.strengthMinutes)
        }
    }

    @Test
    fun `prescription follows the planned focus of today`() {
        for (goal in Goal.entries) {
            val history = SampleData.generate(Scenario.BALANCED, monday.minusDays(1), 56)
            val r = engine.recommend(history, monday, profile(goal))
            val today = WeeklyPlanner.plan(r).days.single { it.date == monday }
            val p = GoalAdvisor.prescribe(r, today, null, null)
            when {
                today.isStrength -> assertEquals(today.minutes, p.strengthMinutes)
                today.focus == SessionFocus.CARDIO -> assertEquals(today.minutes, p.aerobicMinutes)
            }
        }
    }

    @Test
    fun `undertrained muscles are below ten weekly sets and recovered`() {
        val sessions = SampleData.strengthSessions(end, 14)
        val muscles = MuscleFatigueModel.estimate(emptyList(), sessions, emptyList(), profile(Goal.MUSCLE_GAIN), monday.atTime(8, 0))
        val r = recommend(Scenario.BALANCED, Goal.MUSCLE_GAIN)
        val today = WeeklyPlanner.plan(r, sessions, muscles).days.single { it.date == monday }
        val p = GoalAdvisor.prescribe(r, today, muscles, null)
        assertTrue(p.undertrained.isNotEmpty())
        p.undertrained.forEach { m ->
            assertTrue(muscles.status(m).weeklyHardSets < 10.0)
            assertTrue(muscles.status(m).recovered)
        }
    }
}
