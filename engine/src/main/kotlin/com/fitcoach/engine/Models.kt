package com.fitcoach.engine

import java.time.LocalDate

/** 单次运动记录（来自 Health Connect 的 ExerciseSessionRecord）。 */
data class ExerciseSession(
    val durationMinutes: Double,
    /** 本次运动的平均心率；手机端没有心率时为 null。 */
    val avgHeartRate: Double? = null,
    val type: String? = null,
)

/**
 * 某一天的汇总数据。所有字段都可为空，以兼容"只有手机"和"有手表/手环"两种用户。
 *
 * 约定：[sleepMinutes] / [sleepEfficiency] 指"在这一天早上结束的那段睡眠"，即前一晚的睡眠；
 * [restingHeartRate] / [hrvRmssd] 通常是当天早晨测得的值。
 */
data class DailyMetrics(
    val date: LocalDate,
    val steps: Int? = null,
    val sessions: List<ExerciseSession> = emptyList(),
    val restingHeartRate: Double? = null,
    val hrvRmssd: Double? = null,
    val sleepMinutes: Double? = null,
    /** 睡眠效率 = 实际睡着的时间 / 卧床时间，0–1；有睡眠分期数据时才有。 */
    val sleepEfficiency: Double? = null,
    /** 体温（℃）。 */
    val bodyTemperatureC: Double? = null,
    /** 夜间皮肤温度相对个人基线的偏差（℃），Pixel Watch / Fitbit 等设备提供。 */
    val skinTempDeltaC: Double? = null,
    /** 当天是否处于经期。 */
    val menstruation: Boolean = false,
    /** 用户自评：当天训练的主观疲劳度（Foster CR-10 量表，0–10）。 */
    val sessionRpe: Int? = null,
    /** 用户自评：早晨的身体状态（1 很差 – 5 很好）。 */
    val wellness: Int? = null,
) {
    val hasAnyData: Boolean
        get() = steps != null || sessions.isNotEmpty() || restingHeartRate != null ||
            hrvRmssd != null || sleepMinutes != null || bodyTemperatureC != null || skinTempDeltaC != null ||
            sessionRpe != null || wellness != null
}

enum class Sex { MALE, FEMALE }

data class UserProfile(
    val age: Int? = null,
    /** null 表示用户未填写，按通用公式计算。 */
    val sex: Sex? = null,
    /** 实测最大心率（可选），优先于公式估算。 */
    val maxHeartRate: Int? = null,
) {
    /**
     * 最大心率：实测值优先；否则女性用 Gulati 公式 (206 - 0.88 × 年龄)，
     * 男性 / 未填写用 Tanaka 公式 (208 - 0.7 × 年龄)；年龄也没有时取 190。
     */
    fun estimatedMaxHr(): Double = maxHeartRate?.toDouble() ?: age?.let {
        if (sex == Sex.FEMALE) 206.0 - 0.88 * it else 208.0 - 0.7 * it
    } ?: 190.0
}

/** 根据年龄、性别对算法做的个性化调整，UI 会逐条展示给用户。 */
enum class Adjustment {
    MEASURED_MAX_HR,
    ESTIMATED_MAX_HR,
    DEFAULT_MAX_HR,
    FEMALE_LOAD_COEFFICIENT,
    /** 50 岁以上：加量幅度从 10% 降到 5%，连续训练 4 天就提醒休息，每周至少 2 天休息。 */
    OLDER_CONSERVATIVE,
    /** 65 岁以上：急慢性负荷比的安全上限从 1.3 降到 1.2。 */
    SENIOR_LOWER_ACWR,
    /** 18 岁以下：睡眠需求更高（8–10 小时），睡眠阈值相应提高。 */
    YOUTH_SLEEP_NEED,
}

enum class Level { INCREASE, MAINTAIN, DECREASE, REST }

enum class Confidence { LOW, MEDIUM, HIGH }

enum class Severity(val penalty: Int) { INFO(0), MODERATE(1), SEVERE(2) }

/** 影响建议的各个信号。UI 层负责把它们翻译成文字。 */
sealed interface Signal {
    val severity: Severity

    data class InsufficientData(val daysWithData: Int, val required: Int) : Signal {
        override val severity = Severity.INFO
    }
    data class AcwrLow(val acwr: Double) : Signal { override val severity = Severity.INFO }
    data class AcwrOptimal(val acwr: Double) : Signal { override val severity = Severity.INFO }
    data class AcwrElevated(val acwr: Double) : Signal { override val severity = Severity.INFO }
    data class AcwrHigh(val acwr: Double) : Signal { override val severity = Severity.INFO }

    /** [threshold] 是按个人日常波动算出的"偏高"阈值。 */
    data class RestingHrElevated(
        val delta: Double,
        val threshold: Double,
        override val severity: Severity,
    ) : Signal

    /**
     * HRV 偏低。[ratio] = 近 7 天 lnRMSSD 均值相对基线的比例（换算回毫秒），
     * [zScore] = 偏离个人基线多少个标准差；[acute] 表示只是今天单日骤降。
     */
    data class HrvSuppressed(
        val ratio: Double,
        val zScore: Double,
        val acute: Boolean,
        override val severity: Severity,
    ) : Signal

    data class ShortSleep(val minutes: Double, override val severity: Severity) : Signal
    data class SleepDebt(val avgMinutes: Double) : Signal { override val severity = Severity.MODERATE }
    data class LowSleepEfficiency(val efficiency: Double) : Signal { override val severity = Severity.MODERATE }
    data class ConsecutiveTrainingDays(val days: Int, override val severity: Severity) : Signal
    data class HighMonotony(val monotony: Double) : Signal { override val severity = Severity.MODERATE }
    data class HardSessionYesterday(val ratioToTypical: Double) : Signal {
        override val severity = Severity.MODERATE
    }
    /** 用户自评昨天的训练非常累 (RPE ≥ 9)。 */
    data class VeryHardSessionReported(val rpe: Int) : Signal { override val severity = Severity.MODERATE }
    /** 用户自评今天早上状态差。 */
    data class PoorWellness(val score: Int, override val severity: Severity) : Signal

    /** 可能生病的前兆：发烧，或静息心率连续升高且 HRV 偏低。出现时直接建议休息。 */
    data class PossibleIllness(
        val fever: Boolean,
        val temperatureC: Double?,
        val skinTempDeltaC: Double?,
        val restingHrDays: Int,
    ) : Signal { override val severity = Severity.SEVERE }

    /** 女性生理周期第几天，仅供参考，不影响建议等级。 */
    data class MenstrualCycle(val dayOfCycle: Int, val onPeriod: Boolean) : Signal {
        override val severity = Severity.INFO
    }

    /** 有恢复数据且全部正常。 */
    data object RecoveryGood : Signal { override val severity = Severity.INFO }
}

data class HeartRateRange(val low: Int, val high: Int)

data class RecoveryStatus(
    val restingHr: Double? = null,
    val restingHrBaseline: Double? = null,
    /** 个人化的静息心率"偏高"阈值（比基线高多少 bpm）。 */
    val restingHrThreshold: Double? = null,
    val hrv: Double? = null,
    /** 近 7 天 HRV 均值（毫秒，由 lnRMSSD 均值换算）。 */
    val hrv7DayAvg: Double? = null,
    val hrvBaseline: Double? = null,
    /** 个人正常波动区间的下限（基线 − 1 个标准差，毫秒）。 */
    val hrvNormalLow: Double? = null,
    val lastSleepMinutes: Double? = null,
    val avgSleepMinutes3d: Double? = null,
    val sleepEfficiency: Double? = null,
    val bodyTemperatureC: Double? = null,
    val skinTempDeltaC: Double? = null,
    val wellness: Int? = null,
)

data class DailyLoad(val date: LocalDate, val load: Double)

data class Recommendation(
    val forDate: LocalDate,
    val level: Level,
    /** 仅由急慢性负荷比得出的等级（恢复信号修正之前）。 */
    val baseLevel: Level,
    val confidence: Confidence,
    /** 急性负荷（7 天指数加权平均）。 */
    val acuteLoad: Double,
    /** 慢性负荷（28 天指数加权平均）。 */
    val chronicLoad: Double,
    val acwr: Double?,
    val targetLoad: Double,
    val suggestedMinutes: Int,
    val heartRateRange: HeartRateRange?,
    val signals: List<Signal>,
    val recovery: RecoveryStatus,
    /** 最近 28 天的每日负荷，最早的在前，用于画图和周计划。 */
    val dailyLoads: List<DailyLoad>,
    /** 最近 28 天训练日的平均负荷；没有训练记录时为 null。 */
    val typicalTrainingLoad: Double?,
    val profile: UserProfile,
    val maxHeartRate: Int,
    val adjustments: List<Adjustment>,
) {
    val possibleIllness: Boolean get() = signals.any { it is Signal.PossibleIllness }
}
