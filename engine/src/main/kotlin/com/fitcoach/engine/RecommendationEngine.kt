package com.fitcoach.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class EngineConfig(
    val acuteDays: Int = 7,
    val chronicDays: Int = 28,
    /** 读取多少天的历史来计算 EWMA（越长慢性负荷越稳定）。 */
    val historyDays: Int = 56,
    /** 计算 ACWR 时，最近 28 天里至少需要的有数据天数。 */
    val minDaysForAcwr: Int = 14,
    val minChronicLoad: Double = 5.0,
    val acwrLow: Double = 0.8,
    val acwrUpper: Double = 1.3,
    val acwrHigh: Double = 1.5,
    /** 日负荷达到该值才算"训练日"。 */
    val trainingDayLoad: Double = 40.0,
    val minBaselineSamples: Int = 5,
    /** 用个人波动设定阈值所需的最少样本数，不够时退回固定阈值。 */
    val minPersonalSamples: Int = 10,
    val increaseFactor: Double = 1.10,
    val decreaseFactor: Double = 0.60,
    /** 睡眠效率低于该值视为睡眠质量差（临床上常用 85%）。 */
    val minSleepEfficiency: Double = 0.85,
    val feverC: Double = 37.5,
    /** 皮肤温度比个人基线高这么多视为体温升高。 */
    val skinTempRiseC: Double = 1.0,
)

/**
 * 根据历史数据给出某一天 ([forDate]) 的训练建议。
 *
 * - 负荷只统计 forDate 之前的数据（forDate 当天的运动还没发生）。
 * - 恢复信号（静息心率、HRV、睡眠、体温、早晨自评）优先取 forDate 当天的值，没有则取前一天。
 *
 * 决策流程：
 * 1. 用 EWMA 计算急性 (7 天) / 慢性 (28 天) 负荷，由 ACWR 得出基础等级：
 *    <0.8 加量，0.8~1.3 维持，1.3~1.5 减量，>1.5 减量（高风险）；
 * 2. 恢复信号累计扣分（中度 1 分、重度 2 分）：1~2 分降一档，3~4 分降两档，≥5 分直接休息；
 *    疑似生病时无论分数都直接休息；
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
        val historyDates = (config.historyDays downTo 1).map { forDate.minusDays(it.toLong()) }
        val windowDates = historyDates.takeLast(config.chronicDays)
        val windowDays = windowDates.map { byDate[it] }
        val daysWithData = windowDays.count { it?.hasAnyData == true }

        // ---- 负荷（EWMA） ----
        val rhrBaselineAll = median(windowDays.mapNotNull { it?.restingHeartRate })
        val allLoads = historyDates.map { date ->
            byDate[date]?.let { TrainingLoad.dailyLoad(it, profile, rhrBaselineAll) } ?: 0.0
        }
        // 从第一天有数据开始算，避免更早的"空白"被当成 0 负荷拉低慢性负荷
        val firstDataIndex = historyDates.indexOfFirst { byDate[it]?.hasAnyData == true }
        val effectiveLoads = if (firstDataIndex < 0) emptyList() else allLoads.drop(firstDataIndex)
        val acute = LoadModel.ewma(effectiveLoads, config.acuteDays)
        val chronic = LoadModel.ewma(effectiveLoads, config.chronicDays)
        val acwr = if (daysWithData >= config.minDaysForAcwr && chronic >= config.minChronicLoad) {
            acute / chronic
        } else null

        val loads = allLoads.takeLast(config.chronicDays)
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
        val recovery = analyzeRecovery(byDate, forDate, historyDates, windowDates, signals, t)

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
        byDate[forDate.minusDays(1)]?.sessionRpe?.let { rpe ->
            if (rpe >= 9) signals += Signal.VeryHardSessionReported(rpe)
        }

        menstrualSignal(byDate, forDate, profile)?.let { signals += it }

        val penalty = signals.sumOf { it.severity.penalty }
        val hasRecoveryData = recovery.restingHr != null || recovery.hrv != null ||
            recovery.lastSleepMinutes != null || recovery.wellness != null
        if (penalty == 0 && hasRecoveryData) signals += Signal.RecoveryGood

        val downgrade = when {
            penalty == 0 -> 0
            penalty <= 2 -> 1
            penalty <= 4 -> 2
            else -> 3
        }
        val level = if (signals.any { it is Signal.PossibleIllness }) Level.REST
        else Level.entries[(baseLevel.ordinal + downgrade).coerceAtMost(Level.REST.ordinal)]

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
            target = target.coerceAtMost(
                LoadModel.maxLoadForAcwr(acute, chronic, t.acwrUpper, config.acuteDays, config.chronicDays),
            )
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
            dailyLoads = windowDates.zip(loads) { d, l ->
                val aerobic = byDate[d]?.sessions.orEmpty()
                    .filterNot { StrengthLoad.isStrengthType(it.type) }.sumOf { it.durationMinutes }
                DailyLoad(d, l, aerobic)
            },
            typicalTrainingLoad = typicalTrainingLoad,
            profile = profile,
            maxHeartRate = profile.estimatedMaxHr().roundToInt(),
            adjustments = adjustments,
        )
    }

    /** 随年龄、性别变化的阈值。 */
    internal data class Thresholds(
        val increaseFactor: Double,
        val acwrUpper: Double,
        val consecutiveModerate: Int,
        val consecutiveSevere: Int,
        val sleepModerate: Double,
        val sleepSevere: Double,
        val sleepDebt: Double,
        val minRestDaysPerWeek: Int,
    )

    internal fun thresholdsFor(profile: UserProfile): Pair<Thresholds, List<Adjustment>> {
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
            minRestDaysPerWeek = 1,
        )
        val age = profile.age
        if (age != null) {
            // 年长者恢复更慢：加量更保守，更早提醒休息
            if (age >= 50) {
                t = t.copy(increaseFactor = 1.05, consecutiveModerate = 4, consecutiveSevere = 6, minRestDaysPerWeek = 2)
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
        historyDates: List<LocalDate>,
        windowDates: List<LocalDate>,
        signals: MutableList<Signal>,
        t: Thresholds,
    ): RecoveryStatus {
        val candidates = listOf(forDate, forDate.minusDays(1))

        fun latest(selector: (DailyMetrics) -> Double?): Pair<Double, LocalDate>? =
            candidates.firstNotNullOfOrNull { d -> byDate[d]?.let(selector)?.let { it to d } }

        // ---- 静息心率：阈值按个人日常波动设定 ----
        val rhr = latest { it.restingHeartRate }
        var rhrBaseline: Double? = null
        var rhrThreshold: Double? = null
        var rhrSevereThreshold = 0.0
        var rhrElevatedDays = 0
        if (rhr != null) {
            val values = windowDates.filter { it != rhr.second }.mapNotNull { byDate[it]?.restingHeartRate }
            if (values.size >= config.minBaselineSamples) {
                val baseline = median(values)!!
                rhrBaseline = baseline
                // 样本足够时用稳健标准差 (1.4826 × MAD)，阈值 = 2 个标准差，限制在 3–6 bpm；否则固定 5 bpm
                val moderate = if (values.size >= config.minPersonalSamples) {
                    (2 * robustSd(values, baseline)).coerceIn(3.0, 6.0)
                } else 5.0
                rhrThreshold = moderate
                rhrSevereThreshold = moderate + 3
                val delta = rhr.first - baseline
                when {
                    delta >= rhrSevereThreshold -> signals += Signal.RestingHrElevated(delta, moderate, Severity.SEVERE)
                    delta >= moderate -> signals += Signal.RestingHrElevated(delta, moderate, Severity.MODERATE)
                }
                // 连续几天偏高（用于判断生病）
                var day = rhr.second
                while ((byDate[day]?.restingHeartRate ?: Double.NEGATIVE_INFINITY) - baseline >= moderate) {
                    rhrElevatedDays++
                    day = day.minusDays(1)
                }
            }
        }

        // ---- HRV：近 7 天 lnRMSSD 均值对比个人基线（Plews et al. 的做法） ----
        val hrv = latest { it.hrvRmssd }
        var hrv7: Double? = null
        var hrvBaseline: Double? = null
        var hrvNormalLow: Double? = null
        var hrvSuppressed = false
        val recentStart = forDate.minusDays(6)
        val recentLn = dates(recentStart, forDate).mapNotNull { byDate[it]?.hrvRmssd?.takeIf { v -> v > 0 }?.let(::ln) }.toList()
        val baselineLn = historyDates.filter { it < recentStart }
            .mapNotNull { byDate[it]?.hrvRmssd?.takeIf { v -> v > 0 }?.let(::ln) }
        if (recentLn.size >= 3 && baselineLn.size >= config.minPersonalSamples) {
            val mu = baselineLn.average()
            // 下限 0.08（约 8% 的日波动）：读数异常稳定时避免阈值过窄、频繁误报
            val sigma = sampleSd(baselineLn).coerceAtLeast(0.08)
            val roll = recentLn.average()
            val z = (roll - mu) / sigma
            hrv7 = exp(roll)
            hrvBaseline = exp(mu)
            hrvNormalLow = exp(mu - sigma)
            val todayLn = hrv?.first?.takeIf { it > 0 }?.let(::ln)
            val todayZ = todayLn?.let { (it - mu) / sigma }
            when {
                z < -2 -> signals += Signal.HrvSuppressed(exp(roll - mu), z, acute = false, severity = Severity.SEVERE)
                z < -1 -> signals += Signal.HrvSuppressed(exp(roll - mu), z, acute = false, severity = Severity.MODERATE)
                todayZ != null && todayZ < -2 ->
                    signals += Signal.HrvSuppressed(exp(todayLn - mu), todayZ, acute = true, severity = Severity.MODERATE)
            }
            hrvSuppressed = z < -1 || (todayZ != null && todayZ < -2)
        }

        // ---- 睡眠时长与质量 ----
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
        val efficiency = latest { it.sleepEfficiency }
        if (efficiency != null && efficiency.first < config.minSleepEfficiency) {
            signals += Signal.LowSleepEfficiency(efficiency.first)
        }

        // ---- 早晨自评（只看 forDate 当天） ----
        val wellness = byDate[forDate]?.wellness
        when (wellness) {
            1 -> signals += Signal.PoorWellness(1, Severity.SEVERE)
            2 -> signals += Signal.PoorWellness(2, Severity.MODERATE)
        }

        // ---- 生病预警 ----
        val temperature = latest { it.bodyTemperatureC }
        val skinDelta = latest { it.skinTempDeltaC }
        val fever = (temperature != null && temperature.first >= config.feverC) ||
            (skinDelta != null && skinDelta.first >= config.skinTempRiseC)
        if (fever || (rhrElevatedDays >= 2 && hrvSuppressed)) {
            signals += Signal.PossibleIllness(fever, temperature?.first, skinDelta?.first, rhrElevatedDays)
        }

        return RecoveryStatus(
            restingHr = rhr?.first,
            restingHrBaseline = rhrBaseline,
            restingHrThreshold = rhrThreshold,
            hrv = hrv?.first,
            hrv7DayAvg = hrv7,
            hrvBaseline = hrvBaseline,
            hrvNormalLow = hrvNormalLow,
            lastSleepMinutes = sleep?.first,
            avgSleepMinutes3d = avgSleep3d,
            sleepEfficiency = efficiency?.first,
            bodyTemperatureC = temperature?.first,
            skinTempDeltaC = skinDelta?.first,
            wellness = wellness,
        )
    }

    /** 女性用户：从最近一次经期开始算周期第几天（只作参考信息）。 */
    private fun menstrualSignal(byDate: Map<LocalDate, DailyMetrics>, forDate: LocalDate, profile: UserProfile): Signal? {
        if (profile.sex != Sex.FEMALE) return null
        // 往回找最近一个经期日，再往前找到这段经期的第一天
        val lastPeriodDay = (0L until 40L).map { forDate.minusDays(it) }
            .firstOrNull { byDate[it]?.menstruation == true } ?: return null
        var start = lastPeriodDay
        while (byDate[start.minusDays(1)]?.menstruation == true) start = start.minusDays(1)
        val dayOfCycle = ChronoUnit.DAYS.between(start, forDate).toInt() + 1
        if (dayOfCycle > 35) return null
        val onPeriod = byDate[forDate]?.menstruation == true ||
            (byDate[forDate] == null && byDate[forDate.minusDays(1)]?.menstruation == true)
        return Signal.MenstrualCycle(dayOfCycle, onPeriod)
    }

    /** 各等级对应的心率储备 (HRR) 区间。 */
    private fun zoneFor(level: Level): Pair<Double, Double>? = when (level) {
        Level.INCREASE -> 0.60 to 0.80
        Level.MAINTAIN -> 0.60 to 0.70
        Level.DECREASE -> 0.50 to 0.60
        Level.REST -> null
    }

    private fun roundTo5(minutes: Double): Int = ((minutes / 5).roundToInt() * 5)

    companion object {
        internal fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }

        internal fun robustSd(values: List<Double>, median: Double): Double =
            1.4826 * (median(values.map { abs(it - median) }) ?: 0.0)

        internal fun sampleSd(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        }

        internal fun dates(start: LocalDate, end: LocalDate): Sequence<LocalDate> =
            generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
    }
}
