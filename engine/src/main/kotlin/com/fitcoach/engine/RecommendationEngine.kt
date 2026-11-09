package com.fitcoach.engine

import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class EngineConfig(
    val acuteDays: Int = 7,
    val chronicDays: Int = 28,
    /** 计算 ACWR 至少需要的有数据天数。 */
    val minDaysForAcwr: Int = 14,
    val minChronicLoad: Double = 5.0,
    val acwrLow: Double = 0.8,
    val acwrUpper: Double = 1.3,
    val acwrHigh: Double = 1.5,
    /** 日负荷达到该值才算"训练日"。 */
    val trainingDayLoad: Double = 40.0,
    val minBaselineSamples: Int = 5,
    val increaseFactor: Double = 1.10,
    val decreaseFactor: Double = 0.60,
)

/**
 * 根据历史数据给出某一天 ([forDate]) 的训练建议。
 *
 * - 负荷只统计 forDate 之前的 28 天（forDate 当天的运动还没发生）。
 * - 恢复信号（静息心率、HRV、睡眠）优先取 forDate 当天的值（早上打开 app 时已有昨晚睡眠和晨起心率），
 *   没有则取前一天。
 *
 * 决策流程：
 * 1. 由 ACWR 得出基础等级：<0.8 加量，0.8~1.3 维持，1.3~1.5 减量，>1.5 减量（高风险）；
 * 2. 恢复信号累计扣分（中度 1 分、重度 2 分）：1~2 分降一档，3~4 分降两档，≥5 分直接休息；
 * 3. 按最终等级给出目标负荷，换算成建议时长和心率区间。
 */
class RecommendationEngine(private val config: EngineConfig = EngineConfig()) {

    fun recommend(
        history: List<DailyMetrics>,
        forDate: LocalDate,
        profile: UserProfile = UserProfile(),
    ): Recommendation {
        val (t, adjustments) = thresholdsFor(profile)
        val byDate = history.associateBy { it.date }
        val windowDates = (config.chronicDays downTo 1).map { forDate.minusDays(it.toLong()) }
        val windowDays = windowDates.map { byDate[it] }
        val daysWithData = windowDays.count { it?.hasAnyData == true }

        // ---- 负荷 ----
        val rhrBaselineAll = median(windowDays.mapNotNull { it?.restingHeartRate })
        val loads = windowDates.map { date ->
            byDate[date]?.let { TrainingLoad.dailyLoad(it, profile, rhrBaselineAll) } ?: 0.0
        }
        val acute = loads.takeLast(config.acuteDays).average()
        val chronic = loads.average()
        val acwr = if (daysWithData >= config.minDaysForAcwr && chronic >= config.minChronicLoad) {
            acute / chronic
        } else null

        val trainingLoads = loads.filter { it >= config.trainingDayLoad }
        val typicalTrainingLoad = if (trainingLoads.isNotEmpty()) trainingLoads.average() else null

        val signals = mutableListOf<Signal>()

        // ---- 基础等级 ----
        val baseLevel = when {
            acwr == null -> {
                signals += Signal.InsufficientData(daysWithData, config.minDaysForAcwr)
                Level.MAINTAIN
            }
            acwr < config.acwrLow -> Level.INCREASE.also { signals += Signal.AcwrLow(acwr) }
            acwr <= t.acwrUpper -> Level.MAINTAIN.also { signals += Signal.AcwrOptimal(acwr) }
            acwr <= config.acwrHigh -> Level.DECREASE.also { signals += Signal.AcwrElevated(acwr) }
            else -> Level.DECREASE.also { signals += Signal.AcwrHigh(acwr) }
        }

        // ---- 恢复信号 ----
        val recovery = analyzeRecovery(byDate, forDate, windowDates, signals, t)

        val consecutive = loads.reversed().takeWhile { it >= config.trainingDayLoad }.size
        when {
            consecutive >= t.consecutiveSevere ->
                signals += Signal.ConsecutiveTrainingDays(consecutive, Severity.SEVERE)
            consecutive >= t.consecutiveModerate ->
                signals += Signal.ConsecutiveTrainingDays(consecutive, Severity.MODERATE)
        }

        val last7 = loads.takeLast(config.acuteDays)
        val mean7 = last7.average()
        if (mean7 >= config.trainingDayLoad / 2) {
            val sd = sqrt(last7.sumOf { (it - mean7) * (it - mean7) } / last7.size)
            val monotony = if (sd < 1e-6) Double.POSITIVE_INFINITY else mean7 / sd
            if (monotony > 2.0) signals += Signal.HighMonotony(monotony)
        }

        if (typicalTrainingLoad != null) {
            val ratio = loads.last() / typicalTrainingLoad
            if (ratio > 1.6) signals += Signal.HardSessionYesterday(ratio)
        }

        val penalty = signals.sumOf { it.severity.penalty }
        if (penalty == 0 && (recovery.restingHr != null || recovery.hrv != null || recovery.lastSleepMinutes != null)) {
            signals += Signal.RecoveryGood
        }
        val downgrade = when {
            penalty == 0 -> 0
            penalty <= 2 -> 1
            penalty <= 4 -> 2
            else -> 3
        }
        val level = Level.entries[(baseLevel.ordinal + downgrade).coerceAtMost(Level.REST.ordinal)]

        // ---- 目标负荷 → 时长与心率区间 ----
        val reference = typicalTrainingLoad
            ?: (30 * TrainingLoad.trimpPerMinute(0.65, profile.sex)) // 没有历史训练时默认约 30 分钟中等强度
        var target = when (level) {
            Level.INCREASE -> reference * t.increaseFactor
            Level.MAINTAIN -> reference
            Level.DECREASE -> reference * config.decreaseFactor
            Level.REST -> 0.0
        }
        if (acwr != null && (level == Level.INCREASE || level == Level.MAINTAIN)) {
            // 保证做完这次训练后 ACWR 不超过安全上限
            val cap = t.acwrUpper * chronic * config.acuteDays - last7.drop(1).sum()
            target = target.coerceAtMost(cap)
        }
        target = target.coerceAtLeast(0.0)

        val zone = zoneFor(level)
        val minutes = if (zone == null || target <= 0.0) 0 else {
            val perMinute = TrainingLoad.trimpPerMinute((zone.first + zone.second) / 2, profile.sex)
            roundTo5(target / perMinute).coerceIn(10, 120)
        }
        val hrRange = zone?.let {
            val rest = recovery.restingHr ?: rhrBaselineAll ?: TrainingLoad.DEFAULT_RESTING_HR
            val max = profile.estimatedMaxHr()
            HeartRateRange(
                low = (rest + it.first * (max - rest)).roundToInt(),
                high = (rest + it.second * (max - rest)).roundToInt(),
            )
        }

        val hasHeartData = windowDays.any { d ->
            d != null && (d.restingHeartRate != null || d.hrvRmssd != null || d.sessions.any { it.avgHeartRate != null })
        }
        val confidence = when {
            daysWithData >= 21 && hasHeartData -> Confidence.HIGH
            daysWithData >= config.minDaysForAcwr -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return Recommendation(
            forDate = forDate,
            level = level,
            baseLevel = baseLevel,
            confidence = confidence,
            acuteLoad = acute,
            chronicLoad = chronic,
            acwr = acwr,
            targetLoad = target,
            suggestedMinutes = minutes,
            heartRateRange = hrRange,
            signals = signals,
            recovery = recovery,
            dailyLoads = windowDates.zip(loads) { d, l -> DailyLoad(d, l) },
            profile = profile,
            maxHeartRate = profile.estimatedMaxHr().roundToInt(),
            adjustments = adjustments,
        )
    }

    /** 随年龄、性别变化的阈值。 */
    private data class Thresholds(
        val increaseFactor: Double,
        val acwrUpper: Double,
        val consecutiveModerate: Int,
        val consecutiveSevere: Int,
        val sleepModerate: Double,
        val sleepSevere: Double,
        val sleepDebt: Double,
    )

    private fun thresholdsFor(profile: UserProfile): Pair<Thresholds, List<Adjustment>> {
        val adjustments = mutableListOf<Adjustment>()
        adjustments += when {
            profile.maxHeartRate != null -> Adjustment.MEASURED_MAX_HR
            profile.age != null -> Adjustment.ESTIMATED_MAX_HR
            else -> Adjustment.DEFAULT_MAX_HR
        }
        if (profile.sex == Sex.FEMALE) adjustments += Adjustment.FEMALE_LOAD_COEFFICIENT

        var t = Thresholds(
            increaseFactor = config.increaseFactor,
            acwrUpper = config.acwrUpper,
            consecutiveModerate = 5,
            consecutiveSevere = 7,
            sleepModerate = 360.0,
            sleepSevere = 300.0,
            sleepDebt = 390.0,
        )
        val age = profile.age
        if (age != null) {
            // 年长者恢复更慢：加量更保守，更早提醒休息
            if (age >= 50) {
                t = t.copy(increaseFactor = 1.05, consecutiveModerate = 4, consecutiveSevere = 6)
                adjustments += Adjustment.OLDER_CONSERVATIVE
            }
            if (age >= 65) {
                t = t.copy(acwrUpper = 1.2)
                adjustments += Adjustment.SENIOR_LOWER_ACWR
            }
            // 青少年推荐睡眠 8–10 小时
            if (age < 18) {
                t = t.copy(sleepModerate = 420.0, sleepSevere = 360.0, sleepDebt = 450.0)
                adjustments += Adjustment.YOUTH_SLEEP_NEED
            }
        }
        return t to adjustments
    }

    private fun analyzeRecovery(
        byDate: Map<LocalDate, DailyMetrics>,
        forDate: LocalDate,
        windowDates: List<LocalDate>,
        signals: MutableList<Signal>,
        t: Thresholds,
    ): RecoveryStatus {
        val candidates = listOf(forDate, forDate.minusDays(1))

        fun latest(selector: (DailyMetrics) -> Double?): Pair<Double, LocalDate>? =
            candidates.firstNotNullOfOrNull { d -> byDate[d]?.let(selector)?.let { it to d } }

        fun baseline(selector: (DailyMetrics) -> Double?, exclude: LocalDate): Double? {
            val values = windowDates.filter { it != exclude }.mapNotNull { byDate[it]?.let(selector) }
            return if (values.size >= config.minBaselineSamples) median(values) else null
        }

        // 静息心率：比基线高 5 bpm 为中度，8 bpm 为重度
        val rhr = latest { it.restingHeartRate }
        val rhrBaseline = rhr?.let { baseline({ d -> d.restingHeartRate }, it.second) }
        if (rhr != null && rhrBaseline != null) {
            val delta = rhr.first - rhrBaseline
            when {
                delta >= 8 -> signals += Signal.RestingHrElevated(delta, Severity.SEVERE)
                delta >= 5 -> signals += Signal.RestingHrElevated(delta, Severity.MODERATE)
            }
        }

        // HRV：低于基线 15% 为中度，25% 为重度
        val hrv = latest { it.hrvRmssd }
        val hrvBaseline = hrv?.let { baseline({ d -> d.hrvRmssd }, it.second) }
        if (hrv != null && hrvBaseline != null && hrvBaseline > 0) {
            val ratio = hrv.first / hrvBaseline
            when {
                ratio <= 0.75 -> signals += Signal.HrvSuppressed(ratio, Severity.SEVERE)
                ratio <= 0.85 -> signals += Signal.HrvSuppressed(ratio, Severity.MODERATE)
            }
        }

        // 睡眠（成人）：昨晚 <5h 重度，<6h 中度；否则近 3 晚平均 <6.5h 视为睡眠负债。青少年各加 1 小时
        val sleep = latest { it.sleepMinutes }
        val recentSleeps = (0L..2L).mapNotNull { byDate[forDate.minusDays(it)]?.sleepMinutes }
        val avgSleep3d = if (recentSleeps.isNotEmpty()) recentSleeps.average() else null
        if (sleep != null) {
            when {
                sleep.first < t.sleepSevere -> signals += Signal.ShortSleep(sleep.first, Severity.SEVERE)
                sleep.first < t.sleepModerate -> signals += Signal.ShortSleep(sleep.first, Severity.MODERATE)
                avgSleep3d != null && recentSleeps.size >= 2 && avgSleep3d < t.sleepDebt ->
                    signals += Signal.SleepDebt(avgSleep3d)
            }
        }

        return RecoveryStatus(
            restingHr = rhr?.first,
            restingHrBaseline = rhrBaseline,
            hrv = hrv?.first,
            hrvBaseline = hrvBaseline,
            lastSleepMinutes = sleep?.first,
            avgSleepMinutes3d = avgSleep3d,
        )
    }

    /** 各等级对应的心率储备 (HRR) 区间。 */
    private fun zoneFor(level: Level): Pair<Double, Double>? = when (level) {
        Level.INCREASE -> 0.60 to 0.80
        Level.MAINTAIN -> 0.60 to 0.70
        Level.DECREASE -> 0.50 to 0.60
        Level.REST -> null
    }

    private fun roundTo5(minutes: Double): Int = ((minutes / 5).roundToInt() * 5)

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
