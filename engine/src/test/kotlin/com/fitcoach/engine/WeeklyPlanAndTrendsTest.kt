package com.fitcoach.engine

import com.fitcoach.engine.SampleData.Scenario
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeeklyPlanAndTrendsTest {

    private val engine = RecommendationEngine()
    private val end = LocalDate.of(2026, 11, 4) // 周三
    private val forDate = end.plusDays(1)        // 周四

    private fun plan(scenario: Scenario, profile: UserProfile = UserProfile(age = 30)): Pair<Recommendation, WeekPlan> {
        val r = engine.recommend(SampleData.generate(scenario, end, days = 56), forDate, profile)
        return r to WeeklyPlanner.plan(r)
    }

    @Test
    fun `week runs Monday to Sunday with past days filled in`() {
        val (r, week) = plan(Scenario.BALANCED)
        assertEquals(7, week.days.size)
        assertEquals(DayOfWeek.MONDAY, week.weekStart.dayOfWeek)
        assertEquals(3, week.days.count { it.isPast }) // 周一到周三
        val today = week.days.single { it.date == forDate }
        assertEquals(r.suggestedMinutes, today.minutes)
        assertTrue(week.targetLoad > 0)
    }

    @Test
    fun `no two hard days in a row and at most two hard days`() {
        for (scenario in Scenario.entries) {
            val (_, week) = plan(scenario)
            val kinds = week.days.map { it.kind }
            assertTrue(kinds.zipWithNext().none { (a, b) -> a == DayKind.HARD && b == DayKind.HARD }, "$scenario $kinds")
            assertTrue(kinds.count { it == DayKind.HARD } <= 2, "$scenario $kinds")
        }
    }

    @Test
    fun `remaining load is distributed over future days`() {
        val (r, week) = plan(Scenario.DETRAINING)
        val future = week.days.filter { it.date > forDate }
        val remaining = week.targetLoad - week.completedLoad - r.targetLoad
        if (week.note == WeekNote.NONE) {
            assertTrue(future.sumOf { it.targetLoad } <= remaining + 1e-6)
            assertTrue(future.any { it.kind != DayKind.REST && it.minutes > 0 })
        }
    }

    @Test
    fun `older users get at least two rest days per week`() {
        val (_, week) = plan(Scenario.OVERREACHING, UserProfile(age = 60))
        assertTrue(week.days.count { it.kind == DayKind.REST } >= 2, week.days.map { it.kind }.toString())
    }

    @Test
    fun `illness turns the rest of the week into recovery`() {
        val history = SampleData.generate(Scenario.BALANCED, end, days = 56) +
            DailyMetrics(forDate, bodyTemperatureC = 38.2)
        val r = engine.recommend(history, forDate, UserProfile(age = 30))
        val week = WeeklyPlanner.plan(r)
        assertEquals(WeekNote.ILLNESS, week.note)
        assertTrue(week.days.filter { it.date >= forDate }.none { it.kind == DayKind.HARD || it.kind == DayKind.MODERATE })
    }

    @Test
    fun `trends summarize weeks and produce series`() {
        val history = SampleData.generate(Scenario.BALANCED, end, days = 84)
        val trends = TrendAnalyzer.analyze(history, UserProfile(age = 30), end, weeks = 12)
        assertEquals(12, trends.weeks.size)
        assertTrue(trends.weeks.all { it.weekStart.dayOfWeek == DayOfWeek.MONDAY })
        // 规律训练：每周 4 个训练日（最后一周只到周三）
        assertTrue(trends.weeks.dropLast(1).all { it.trainingDays == 4 }, trends.weeks.map { it.trainingDays }.toString())
        assertTrue(trends.acwr.isNotEmpty() && trends.acwr.all { it.value in 0.6..1.5 })
        assertTrue(trends.hrv7.isNotEmpty())
        assertEquals(84, trends.restingHr.size)
    }

    @Test
    fun `trends handle empty history`() {
        val trends = TrendAnalyzer.analyze(emptyList(), UserProfile(), end)
        assertTrue(trends.weeks.isEmpty())
    }
}
