package com.fitcoach.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class TrendPoint(val date: LocalDate, val value: Double)

data class WeekSummary(
    val weekStart: LocalDate,
    val totalLoad: Double,
    val trainingDays: Int,
    val avgRestingHr: Double?,
    val avgHrv: Double?,
    val avgSleepMinutes: Double?,
)

data class Trends(
    val weeks: List<WeekSummary>,
    val acwr: List<TrendPoint>,
    /** HRV 7 天滚动均值（毫秒）。 */
    val hrv7: List<TrendPoint>,
    val restingHr: List<TrendPoint>,
)

/** 长期趋势：每周汇总、ACWR 曲线、HRV 和静息心率走势。 */
object TrendAnalyzer {

    private const val TRAINING_DAY_LOAD = 40.0

    fun analyze(history: List<DailyMetrics>, profile: UserProfile, end: LocalDate, weeks: Int = 12): Trends {
        if (history.isEmpty()) return Trends(emptyList(), emptyList(), emptyList(), emptyList())
        val byDate = history.associateBy { it.date }
        val first = history.minOf { it.date }
        val allDates = RecommendationEngine.dates(first, end).toList()
        val rhrBaseline = RecommendationEngine.median(history.mapNotNull { it.restingHeartRate })
        val loads = allDates.map { d -> byDate[d]?.let { TrainingLoad.dailyLoad(it, profile, rhrBaseline) } ?: 0.0 }

        // ACWR：两条 EWMA 相除；前 14 天数据不足，不画
        val acute = LoadModel.ewmaSeries(loads, 7)
        val chronic = LoadModel.ewmaSeries(loads, 28)
        val acwr = allDates.indices.drop(13).mapNotNull { i ->
            if (chronic[i] < 5.0) null else TrendPoint(allDates[i], acute[i] / chronic[i])
        }

        val hrv7 = allDates.mapNotNull { d ->
            val values = (0L..6L).mapNotNull { byDate[d.minusDays(it)]?.hrvRmssd }
            if (values.size >= 3) TrendPoint(d, values.average()) else null
        }
        val restingHr = allDates.mapNotNull { d -> byDate[d]?.restingHeartRate?.let { TrendPoint(d, it) } }

        val loadByDate = allDates.zip(loads).toMap()
        val lastWeekStart = end.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekSummaries = (weeks - 1 downTo 0).map { lastWeekStart.minusWeeks(it.toLong()) }
            .filter { !it.plusDays(6).isBefore(first) }
            .map { start ->
                val days = (0L..6L).map { start.plusDays(it) }.filter { !it.isAfter(end) }
                val metrics = days.mapNotNull { byDate[it] }
                WeekSummary(
                    weekStart = start,
                    totalLoad = days.sumOf { loadByDate[it] ?: 0.0 },
                    trainingDays = days.count { (loadByDate[it] ?: 0.0) >= TRAINING_DAY_LOAD },
                    avgRestingHr = metrics.mapNotNull { it.restingHeartRate }.averageOrNull(),
                    avgHrv = metrics.mapNotNull { it.hrvRmssd }.averageOrNull(),
                    avgSleepMinutes = metrics.mapNotNull { it.sleepMinutes }.averageOrNull(),
                )
            }

        return Trends(weekSummaries, acwr, hrv7, restingHr)
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
