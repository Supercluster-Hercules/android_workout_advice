package com.fitcoach.engine

import com.fitcoach.engine.SampleData.Scenario
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommendationEngineTest {

    private val engine = RecommendationEngine()
    private val profile = UserProfile(age = 30)

    // 2026-11-04 是周三，最后一天不是长距离跑日，避免"昨天练得很狠"的信号干扰基础场景
    private val end = LocalDate.of(2026, 11, 4)
    private val forDate = end.plusDays(1)

    private fun run(scenario: Scenario): Recommendation =
        engine.recommend(SampleData.generate(scenario, end), forDate, profile).also { r ->
            println(
                "%-12s level=%-8s base=%-8s acwr=%s minutes=%d hr=%s conf=%s signals=%s".format(
                    scenario, r.level, r.baseLevel, r.acwr?.let { "%.2f".format(it) }, r.suggestedMinutes,
                    r.heartRateRange, r.confidence, r.signals,
                ),
            )
        }

    @Test
    fun `TRIMP per minute grows with intensity`() {
        val values = listOf(0.3, 0.5, 0.7, 0.9).map(TrainingLoad::trimpPerMinute)
        assertEquals(values.sorted(), values)
    }

    @Test
    fun `balanced training keeps load steady`() {
        val r = run(Scenario.BALANCED)
        val acwr = assertNotNull(r.acwr)
        assertTrue(acwr in 0.8..1.3, "acwr=$acwr")
        assertEquals(Level.MAINTAIN, r.level)
        assertEquals(Confidence.HIGH, r.confidence)
        assertTrue(Signal.RecoveryGood in r.signals)
        assertTrue(r.suggestedMinutes in 20..90)
    }

    @Test
    fun `detraining suggests adding load`() {
        val r = run(Scenario.DETRAINING)
        assertTrue(r.acwr!! < 0.8)
        assertEquals(Level.INCREASE, r.level)
        assertTrue(r.suggestedMinutes > 0)
    }

    @Test
    fun `sudden spike suggests backing off`() {
        val r = run(Scenario.OVERREACHING)
        assertTrue(r.acwr!! > 1.3)
        assertTrue(r.baseLevel == Level.DECREASE)
        assertTrue(r.signals.any { it is Signal.ConsecutiveTrainingDays })
        assertEquals(Level.REST, r.level)
        assertEquals(0, r.suggestedMinutes)
    }

    @Test
    fun `poor recovery downgrades an otherwise normal day`() {
        val r = run(Scenario.FATIGUED)
        assertEquals(Level.MAINTAIN, r.baseLevel)
        assertTrue(r.signals.any { it is Signal.RestingHrElevated })
        assertTrue(r.signals.any { it is Signal.HrvSuppressed })
        assertTrue(r.signals.any { it is Signal.ShortSleep })
        assertEquals(Level.REST, r.level)
    }

    @Test
    fun `phone-only data still produces a recommendation`() {
        val r = run(Scenario.PHONE_ONLY)
        assertNotNull(r.acwr)
        assertEquals(Level.MAINTAIN, r.level)
        assertEquals(Confidence.MEDIUM, r.confidence)
        assertNull(r.recovery.restingHr)
        assertTrue(r.signals.none { it is Signal.RecoveryGood })
    }

    @Test
    fun `too little history gives low confidence and no ACWR`() {
        val history = SampleData.generate(Scenario.BALANCED, end, days = 7)
        val r = engine.recommend(history, forDate, profile)
        assertNull(r.acwr)
        assertEquals(Confidence.LOW, r.confidence)
        assertTrue(r.signals.any { it is Signal.InsufficientData })
    }

    @Test
    fun `no data at all does not crash`() {
        val r = engine.recommend(emptyList(), forDate, profile)
        assertEquals(Level.MAINTAIN, r.level)
        assertEquals(Confidence.LOW, r.confidence)
        assertTrue(r.suggestedMinutes > 0)
    }

    @Test
    fun `increase is capped so ACWR stays under the safe limit`() {
        val r = run(Scenario.DETRAINING)
        // 按 EWMA 推算做完目标负荷后的新 ACWR
        val la = LoadModel.lambda(7)
        val lc = LoadModel.lambda(28)
        val newAcute = la * r.targetLoad + (1 - la) * r.acuteLoad
        val newChronic = lc * r.targetLoad + (1 - lc) * r.chronicLoad
        assertTrue(newAcute / newChronic <= 1.3 + 1e-9, "newAcwr=${newAcute / newChronic}")
    }

    @Test
    fun `heart rate range uses estimated max HR`() {
        val r = run(Scenario.BALANCED)
        val hr = assertNotNull(r.heartRateRange)
        // 30 岁：最大心率 ≈ 187，静息约 55，60%~70% HRR ≈ 134~147
        assertTrue(hr.low in 125..140 && hr.high in 140..155, "hr=$hr")
    }
}
