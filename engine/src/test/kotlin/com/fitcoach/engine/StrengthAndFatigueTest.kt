package com.fitcoach.engine

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrengthAndFatigueTest {

    private val day = LocalDate.of(2026, 11, 9)
    private val profile = UserProfile(age = 30, sex = Sex.MALE)

    private fun legDay(start: LocalDateTime, rpe: Int = 8) = StrengthSession(
        id = "legs-$start",
        start = start,
        durationMinutes = 60.0,
        sessionRpe = rpe,
        exercises = listOf(ExerciseEntry("squat", 5, 5, 100.0), ExerciseEntry("leg_press", 4, 10, 150.0)),
    )

    // ---------------- 力量训练负荷 ----------------

    @Test
    fun `strength load is session RPE times minutes converted to TRIMP via hrrFromRpe`() {
        val s = legDay(day.atTime(18, 0), rpe = 7)
        assertEquals(7 * 60.0, StrengthLoad.fosterLoad(s), 1e-9)
        val expected = 60.0 * TrainingLoad.trimpPerMinute(TrainingLoad.hrrFromRpe(7), Sex.MALE)
        assertEquals(expected, StrengthLoad.trimp(s, Sex.MALE), 1e-9)
    }

    @Test
    fun `session RPE takes precedence over heart rate because HR underestimates lifting`() {
        val withHr = ExerciseSession(60.0, avgHeartRate = 105.0, type = "力量训练", rpe = 8)
        val load = TrainingLoad.sessionLoad(withHr, restingHr = 55.0, maxHr = 187.0)
        assertEquals(60.0 * TrainingLoad.trimpPerMinute(TrainingLoad.hrrFromRpe(8)), load, 1e-9)
        val hrOnly = TrainingLoad.sessionLoad(withHr.copy(rpe = null), restingHr = 55.0, maxHr = 187.0)
        assertTrue(load > hrOnly * 2, "RPE load $load should be well above HR load $hrOnly")
    }

    @Test
    fun `logged strength replaces the Health Connect strength session on the same day`() {
        val history = listOf(
            DailyMetrics(day.minusDays(1), sessions = listOf(ExerciseSession(40.0, 120.0, "跑步"))),
            DailyMetrics(
                day,
                sessions = listOf(ExerciseSession(30.0, 100.0, "跑步"), ExerciseSession(60.0, 100.0, "力量训练")),
            ),
        )
        val logged = legDay(day.atTime(18, 0))
        val merged = StrengthLoad.mergeInto(history, listOf(logged, legDay(day.plusDays(1).atTime(18, 0))))

        val today = merged.single { it.date == day }
        assertEquals(listOf("跑步", StrengthLoad.SESSION_TYPE), today.sessions.map { it.type })
        assertEquals(8, today.sessions.last().rpe)
        // 历史里没有的日期会补一条
        assertTrue(merged.any { it.date == day.plusDays(1) && it.sessions.size == 1 })
        assertEquals(history[0], merged[0])
    }

    @Test
    fun `strength sessions feed into the ACWR load model`() {
        val s = legDay(day.minusDays(1).atTime(18, 0), rpe = 8)
        val history = (1..20L).map { DailyMetrics(day.minusDays(it), steps = 3000) }
        val merged = StrengthLoad.mergeInto(history, listOf(s))
        val r = RecommendationEngine().recommend(merged, day, profile)
        assertEquals(StrengthLoad.trimp(s, Sex.MALE), r.dailyLoads.last().load, 1e-6)
        assertEquals(0.0, r.dailyLoads.last().aerobicMinutes, 1e-9)
    }

    @Test
    fun `weekly hard sets count primary as 1 and secondary as half a set`() {
        val sessions = listOf(
            StrengthSession(
                "a", day.atTime(18, 0), 50.0, 7,
                listOf(ExerciseEntry("bench_press", 4, 8, 60.0), ExerciseEntry("squat", 3, 5, 100.0)),
            ),
            // 热身 / 技术练习不计
            StrengthSession("b", day.minusDays(1).atTime(18, 0), 20.0, 2, listOf(ExerciseEntry("bench_press", 5, 5, 40.0))),
            // 超出统计区间
            StrengthSession("c", day.minusDays(8).atTime(18, 0), 50.0, 8, listOf(ExerciseEntry("bench_press", 5, 5, 60.0))),
        )
        val sets = StrengthLoad.hardSets(sessions, day.minusDays(6), day)
        assertEquals(4.0, sets.getValue(MuscleGroup.CHEST), 1e-9)
        assertEquals(2.0, sets.getValue(MuscleGroup.TRICEPS), 1e-9)
        assertEquals(2.0, sets.getValue(MuscleGroup.SHOULDERS), 1e-9)
        assertEquals(3.0, sets.getValue(MuscleGroup.QUADS), 1e-9)
        assertEquals(1.5, sets.getValue(MuscleGroup.CORE), 1e-9)
        // 深蹲对腘绳肌只是辅助（0.25），不算有效组
        assertEquals(0.0, sets.getValue(MuscleGroup.HAMSTRINGS), 1e-9)
        assertEquals(MuscleGroup.entries.size, sets.size)
    }

    @Test
    fun `every catalog exercise has a primary muscle and only known weights`() {
        ExerciseCatalog.all.forEach { e ->
            assertTrue(e.muscles.values.any { it == 1.0 }, e.id)
            assertTrue(e.muscles.values.all { it in setOf(1.0, 0.5, 0.25) }, e.id)
        }
        assertEquals(ExerciseCatalog.all.size, ExerciseCatalog.all.map { it.id }.toSet().size)
    }

    // ---------------- 有氧类型 → 肌群 ----------------

    @Test
    fun `cardio types map to muscle groups by keyword`() {
        assertEquals(CardioKind.RUN, CardioMuscleMap.kindOf("跑步机"))
        assertEquals(CardioKind.RUN, CardioMuscleMap.kindOf("长距离跑"))
        assertEquals(CardioKind.HIKE, CardioMuscleMap.kindOf("徒步"))
        assertEquals(CardioKind.WALK, CardioMuscleMap.kindOf("Walking"))
        assertEquals(CardioKind.CYCLE, CardioMuscleMap.kindOf("骑行"))
        assertEquals(CardioKind.SWIM, CardioMuscleMap.kindOf("游泳"))
        assertNull(CardioMuscleMap.kindOf("力量训练"))
        assertNull(CardioMuscleMap.kindOf("瑜伽"))
        assertNull(CardioMuscleMap.kindOf(null))
    }

    @Test
    fun `running loads the legs and swimming loads the upper body`() {
        val at = day.atTime(20, 0)
        val run = MuscleFatigueModel.estimate(
            listOf(DailyMetrics(day, sessions = listOf(ExerciseSession(90.0, null, "跑步", rpe = 7)))),
            emptyList(), emptyList(), profile, at,
        )
        assertTrue(run.status(MuscleGroup.QUADS).recovery < 0.7)
        assertTrue(run.status(MuscleGroup.CALVES).recovery < 0.7)
        assertEquals(1.0, run.status(MuscleGroup.CHEST).recovery, 1e-9)
        assertEquals(TrainingFocus.UPPER_BODY, run.focus)

        val swim = MuscleFatigueModel.estimate(
            listOf(DailyMetrics(day, sessions = listOf(ExerciseSession(90.0, null, "游泳", rpe = 7)))),
            emptyList(), emptyList(), profile, at,
        )
        assertTrue(swim.status(MuscleGroup.BACK).recovery < swim.status(MuscleGroup.QUADS).recovery)
        assertEquals(TrainingFocus.LOWER_BODY, swim.focus)
    }

    // ---------------- 疲劳衰减 ----------------

    @Test
    fun `heavy leg day leaves quads unrecovered next morning and recovered after a few days`() {
        val session = legDay(day.minusDays(1).atTime(18, 0), rpe = 9)
        fun at(t: LocalDateTime) = MuscleFatigueModel.estimate(emptyList(), listOf(session), emptyList(), profile, t)

        val nextMorning = at(day.atTime(8, 0))
        val quads = nextMorning.status(MuscleGroup.QUADS)
        assertTrue(quads.recovery < 0.7, "quads ${quads.recovery}")
        assertTrue(nextMorning.regionReady(BodyRegion.UPPER))
        assertEquals(TrainingFocus.UPPER_BODY, nextMorning.focus)
        assertEquals(MuscleGroup.QUADS, nextMorning.unrecovered.first().muscle)
        assertTrue(quads.hoursToRecovered > 0)

        val later = at(day.plusDays(1).atTime(8, 0)).status(MuscleGroup.QUADS).recovery
        assertTrue(later > quads.recovery)
        assertTrue(at(day.plusDays(4).atTime(8, 0)).status(MuscleGroup.QUADS).recovery >= MuscleFatigueModel.RECOVERED)
    }

    @Test
    fun `hours to recovered matches the exponential decay`() {
        val session = legDay(day.atTime(18, 0), rpe = 9)
        val first = MuscleFatigueModel.estimate(emptyList(), listOf(session), emptyList(), profile, day.atTime(20, 0))
        val quads = first.status(MuscleGroup.QUADS)
        val then = MuscleFatigueModel.estimate(
            emptyList(), listOf(session), emptyList(), profile, day.atTime(20, 0).plusHours(quads.hoursToRecovered.toLong()),
        )
        assertEquals(MuscleFatigueModel.RECOVERED, then.status(MuscleGroup.QUADS).recovery, 0.01)
    }

    @Test
    fun `small muscles recover faster than large ones for the same dose`() {
        val doses = listOf(MuscleDose(day.atTime(8, 0), MuscleGroup.QUADS, 8.0))
        val at = day.atTime(8, 0).plusHours(36)
        val large = MuscleFatigueModel.fatigueAt(doses, at, MuscleGroup.QUADS.recoveryTauHours)
        val small = MuscleFatigueModel.fatigueAt(doses, at, MuscleGroup.BICEPS.recoveryTauHours)
        assertTrue(small < large)
    }

    // ---------------- 酸痛自评 ----------------

    @Test
    fun `recent soreness report is blended into the estimate`() {
        val at = day.atTime(10, 0)
        val report = SorenessReport(at.minusHours(2), MuscleGroup.CHEST, 8)
        val r = MuscleFatigueModel.estimate(emptyList(), emptyList(), listOf(report), profile, at)
        val chest = r.status(MuscleGroup.CHEST)
        assertEquals(1.0, chest.modelRecovery, 1e-9)
        val w = 0.6 * exp(-2.0 / 24)
        assertEquals((1 - w) * 1.0 + w * 0.2, chest.recovery, 1e-6)
        assertEquals(8, chest.sorenessScore)
        assertTrue(!chest.recovered)
    }

    @Test
    fun `soreness reported before the latest session or too long ago is ignored`() {
        val session = legDay(day.atTime(18, 0))
        val reports = listOf(
            SorenessReport(day.atTime(9, 0), MuscleGroup.QUADS, 9), // 训练之前
            SorenessReport(day.minusDays(3).atTime(9, 0), MuscleGroup.CHEST, 9), // 太久以前
        )
        val r = MuscleFatigueModel.estimate(emptyList(), listOf(session), reports, profile, day.plusDays(1).atTime(8, 0))
        assertNull(r.status(MuscleGroup.QUADS).sorenessScore)
        assertNull(r.status(MuscleGroup.CHEST).sorenessScore)
        assertEquals(r.status(MuscleGroup.QUADS).modelRecovery, r.status(MuscleGroup.QUADS).recovery, 1e-9)
    }

    private fun calibratedScale(score: Int): Double {
        // 四周、每周一次腿部训练，每次训练后 30 小时自评股四头肌酸痛
        val sessions = (0..3).map { legDay(day.minusWeeks(it.toLong()).minusDays(3).atTime(18, 0)) }
        val reports = sessions.map { SorenessReport(it.end.plusHours(30), MuscleGroup.QUADS, score) }
        return MuscleFatigueModel.estimate(emptyList(), sessions, reports, profile, day.atTime(8, 0))
            .status(MuscleGroup.QUADS).tauScale
    }

    @Test
    fun `no soreness after leg days shortens the personal recovery time`() {
        val scale = calibratedScale(0)
        assertTrue(scale < 1.0, "scale $scale")
        assertTrue(scale >= 0.6)
    }

    @Test
    fun `strong soreness after leg days lengthens the personal recovery time`() {
        val scale = calibratedScale(9)
        assertTrue(scale > 1.0, "scale $scale")
        assertTrue(scale <= 1.6)
    }

    @Test
    fun `without training data the report says so and everything is recovered`() {
        val r = MuscleFatigueModel.estimate(emptyList(), emptyList(), emptyList(), profile, day.atTime(8, 0))
        assertTrue(!r.hasData)
        assertEquals(TrainingFocus.ANY, r.focus)
        assertTrue(r.unrecovered.isEmpty())
        assertTrue(!r.calibrated)
    }

    @Test
    fun `unlogged Health Connect strength sessions are counted so the UI can ask for details`() {
        val history = listOf(
            DailyMetrics(day.minusDays(1), sessions = listOf(ExerciseSession(45.0, null, "力量训练"))),
            DailyMetrics(day.minusDays(2), sessions = listOf(ExerciseSession(45.0, null, "Strength training"))),
        )
        val logged = legDay(day.minusDays(2).atTime(18, 0))
        val merged = StrengthLoad.mergeInto(history, listOf(logged))
        val r = MuscleFatigueModel.estimate(merged, listOf(logged), emptyList(), profile, day.atTime(8, 0))
        assertEquals(1, r.unloggedStrengthDays)
    }
}
