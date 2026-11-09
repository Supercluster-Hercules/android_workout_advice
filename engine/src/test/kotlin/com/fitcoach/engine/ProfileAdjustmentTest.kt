package com.fitcoach.engine

import com.fitcoach.engine.SampleData.Scenario
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileAdjustmentTest {

    private val engine = RecommendationEngine()
    private val end = LocalDate.of(2026, 11, 4)
    private val forDate = end.plusDays(1)

    private fun List<DailyMetrics>.withLastDays(n: Int, transform: (DailyMetrics) -> DailyMetrics) =
        mapIndexed { i, d -> if (i >= size - n) transform(d) else d }

    @Test
    fun `max heart rate formula depends on sex and age`() {
        assertEquals(180.0, UserProfile(age = 40, sex = Sex.MALE).estimatedMaxHr(), 1e-9)
        assertEquals(180.0, UserProfile(age = 40).estimatedMaxHr(), 1e-9)
        assertEquals(170.8, UserProfile(age = 40, sex = Sex.FEMALE).estimatedMaxHr(), 1e-9)
        assertEquals(175.0, UserProfile(age = 40, sex = Sex.FEMALE, maxHeartRate = 175).estimatedMaxHr(), 1e-9)
    }

    @Test
    fun `female TRIMP coefficient is used for women`() {
        val male = TrainingLoad.trimpPerMinute(0.6, Sex.MALE)
        val female = TrainingLoad.trimpPerMinute(0.6, Sex.FEMALE)
        assertEquals(0.6 * 0.64 * kotlin.math.exp(1.92 * 0.6), male, 1e-9)
        assertEquals(0.6 * 0.86 * kotlin.math.exp(1.67 * 0.6), female, 1e-9)

        val history = SampleData.generate(Scenario.BALANCED, end)
        val r = engine.recommend(history, forDate, UserProfile(age = 30, sex = Sex.FEMALE))
        assertTrue(Adjustment.FEMALE_LOAD_COEFFICIENT in r.adjustments)
    }

    @Test
    fun `older users get a smaller increase`() {
        val history = SampleData.generate(Scenario.DETRAINING, end)
        // 固定最大心率，让两人的负荷完全相同，只比较年龄带来的差别
        val young = engine.recommend(history, forDate, UserProfile(age = 30, maxHeartRate = 185))
        val older = engine.recommend(history, forDate, UserProfile(age = 55, maxHeartRate = 185))
        assertEquals(Level.INCREASE, young.level)
        assertEquals(Level.INCREASE, older.level)
        assertEquals(1.05 / 1.10, older.targetLoad / young.targetLoad, 1e-9)
        assertTrue(Adjustment.OLDER_CONSERVATIVE in older.adjustments)
    }

    @Test
    fun `older users are reminded to rest after 4 straight training days`() {
        val history = SampleData.generate(Scenario.BALANCED, end)
            .withLastDays(5) { it.copy(sessions = emptyList(), steps = 4000) } // 第 5 天休息
            .withLastDays(4) { it.copy(sessions = listOf(ExerciseSession(45.0, 145.0, "跑步"))) }
        val young = engine.recommend(history, forDate, UserProfile(age = 30))
        val older = engine.recommend(history, forDate, UserProfile(age = 55))
        assertTrue(young.signals.none { it is Signal.ConsecutiveTrainingDays })
        assertTrue(older.signals.any { it is Signal.ConsecutiveTrainingDays })
    }

    @Test
    fun `teenagers need more sleep`() {
        val history = SampleData.generate(Scenario.BALANCED, end).withLastDays(1) { it.copy(sleepMinutes = 390.0) }
        val adult = engine.recommend(history, forDate, UserProfile(age = 30))
        val teen = engine.recommend(history, forDate, UserProfile(age = 16))
        assertTrue(adult.signals.none { it is Signal.ShortSleep })
        assertTrue(teen.signals.any { it is Signal.ShortSleep && it.severity == Severity.MODERATE })
        assertTrue(Adjustment.YOUTH_SLEEP_NEED in teen.adjustments)
    }

    @Test
    fun `seniors use a lower ACWR ceiling`() {
        val r = engine.recommend(SampleData.generate(Scenario.BALANCED, end), forDate, UserProfile(age = 70))
        assertTrue(Adjustment.SENIOR_LOWER_ACWR in r.adjustments)
        assertTrue(Adjustment.OLDER_CONSERVATIVE in r.adjustments)
    }

    @Test
    fun `missing profile falls back to defaults`() {
        val r = engine.recommend(SampleData.generate(Scenario.BALANCED, end), forDate, UserProfile())
        assertEquals(190, r.maxHeartRate)
        assertEquals(listOf(Adjustment.DEFAULT_MAX_HR), r.adjustments)
    }
}
