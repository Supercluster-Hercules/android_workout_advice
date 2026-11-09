package com.fitcoach.engine

import com.fitcoach.engine.SampleData.Scenario
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlgorithmUpgradeTest {

    private val engine = RecommendationEngine()
    private val profile = UserProfile(age = 30)
    private val end = LocalDate.of(2026, 11, 4)
    private val forDate = end.plusDays(1)

    private fun balanced() = SampleData.generate(Scenario.BALANCED, end, days = 56)

    private fun List<DailyMetrics>.withDay(date: LocalDate, transform: (DailyMetrics) -> DailyMetrics) =
        if (any { it.date == date }) map { if (it.date == date) transform(it) else it }
        else this + transform(DailyMetrics(date))

    @Test
    fun `EWMA reacts faster to recent load than to old load`() {
        val recentSpike = List(21) { 50.0 } + List(7) { 100.0 }
        val oldSpike = List(7) { 100.0 } + List(21) { 50.0 }
        assertTrue(LoadModel.ewma(recentSpike, 7) > LoadModel.ewma(oldSpike, 7))
        assertEquals(50.0, LoadModel.ewma(List(28) { 50.0 }, 28), 1e-9)
    }

    @Test
    fun `max load for ACWR solves the EWMA inequality`() {
        val x = LoadModel.maxLoadForAcwr(acute = 60.0, chronic = 60.0, upper = 1.3, acuteSpan = 7, chronicSpan = 28)
        val la = LoadModel.lambda(7)
        val lc = LoadModel.lambda(28)
        val acwr = (la * x + (1 - la) * 60) / (lc * x + (1 - lc) * 60)
        assertEquals(1.3, acwr, 1e-9)
    }

    @Test
    fun `resting HR threshold adapts to personal variability`() {
        // 这位用户静息心率极其稳定（±1 bpm），+4 bpm 已经超出 2 个标准差
        val history = balanced().map { it.copy(restingHeartRate = 55.0 + (it.date.dayOfMonth % 3 - 1)) }
            .withDay(forDate) { it.copy(restingHeartRate = 59.5) }
        val r = engine.recommend(history, forDate, profile)
        val signal = r.signals.filterIsInstance<Signal.RestingHrElevated>().single()
        assertTrue(signal.threshold < 5.0, "threshold=${signal.threshold}")
        assertNotNull(r.recovery.restingHrThreshold)
    }

    @Test
    fun `single low HRV day is flagged as acute, not as a trend`() {
        val history = balanced().withDay(forDate) { it.copy(hrvRmssd = 35.0) }
        val r = engine.recommend(history, forDate, profile)
        val signal = r.signals.filterIsInstance<Signal.HrvSuppressed>().single()
        assertTrue(signal.acute)
        assertEquals(Severity.MODERATE, signal.severity)
        assertNotNull(r.recovery.hrv7DayAvg)
    }

    @Test
    fun `poor sleep efficiency is flagged`() {
        val history = balanced().withDay(forDate) { it.copy(sleepMinutes = 420.0, sleepEfficiency = 0.78) }
        val r = engine.recommend(history, forDate, profile)
        assertTrue(r.signals.any { it is Signal.LowSleepEfficiency })
    }

    @Test
    fun `fever forces rest even when training load is fine`() {
        val history = balanced().withDay(forDate) { it.copy(bodyTemperatureC = 38.1) }
        val r = engine.recommend(history, forDate, profile)
        assertEquals(Level.MAINTAIN, r.baseLevel)
        assertEquals(Level.REST, r.level)
        assertTrue(r.possibleIllness)
    }

    @Test
    fun `skin temperature rise also triggers illness warning`() {
        val history = balanced().withDay(forDate) { it.copy(skinTempDeltaC = 1.3) }
        val r = engine.recommend(history, forDate, profile)
        assertEquals(Level.REST, r.level)
        assertTrue(r.signals.filterIsInstance<Signal.PossibleIllness>().single().fever)
    }

    @Test
    fun `poor morning wellness downgrades the day`() {
        val history = balanced().withDay(forDate) { it.copy(wellness = 2) }
        val r = engine.recommend(history, forDate, profile)
        assertTrue(r.signals.any { it is Signal.PoorWellness })
        assertEquals(Level.DECREASE, r.level)
    }

    @Test
    fun `RPE raises load of sessions without heart rate`() {
        val day = DailyMetrics(end, sessions = listOf(ExerciseSession(60.0, avgHeartRate = null, type = "力量训练")))
        val default = TrainingLoad.dailyLoad(day, profile, null)
        val hard = TrainingLoad.dailyLoad(day.copy(sessionRpe = 9), profile, null)
        val easy = TrainingLoad.dailyLoad(day.copy(sessionRpe = 2), profile, null)
        assertTrue(hard > default && default > easy)
    }

    @Test
    fun `very hard session reported yesterday is a signal`() {
        val history = balanced().withDay(end) { it.copy(sessionRpe = 9) }
        val r = engine.recommend(history, forDate, profile)
        assertTrue(r.signals.any { it is Signal.VeryHardSessionReported })
    }

    @Test
    fun `menstrual cycle day is reported for women only and does not change the level`() {
        // 经期从 end-2 开始，持续 3 天 → forDate 是周期第 4 天
        val history = balanced().map { d ->
            if (d.date >= end.minusDays(2)) d.copy(menstruation = true) else d
        }
        val female = engine.recommend(history, forDate, UserProfile(age = 30, sex = Sex.FEMALE))
        val cycle = female.signals.filterIsInstance<Signal.MenstrualCycle>().single()
        assertEquals(4, cycle.dayOfCycle)
        val male = engine.recommend(history, forDate, UserProfile(age = 30, sex = Sex.MALE))
        assertTrue(male.signals.none { it is Signal.MenstrualCycle })
        assertEquals(male.baseLevel, female.baseLevel)
    }

    @Test
    fun `no menstruation data means no cycle signal`() {
        val r = engine.recommend(balanced(), forDate, UserProfile(age = 30, sex = Sex.FEMALE))
        assertNull(r.signals.filterIsInstance<Signal.MenstrualCycle>().firstOrNull())
    }
}
