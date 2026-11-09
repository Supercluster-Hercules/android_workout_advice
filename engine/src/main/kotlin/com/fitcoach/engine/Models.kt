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
 * 约定：[sleepMinutes] 指"在这一天早上结束的那段睡眠"，即前一晚的睡眠；
 * [restingHeartRate] / [hrvRmssd] 通常是当天早晨测得的值。
 */
data class DailyMetrics(
    val date: LocalDate,
    val steps: Int? = null,
    val sessions: List<ExerciseSession> = emptyList(),
    val restingHeartRate: Double? = null,
    val hrvRmssd: Double? = null,
    val sleepMinutes: Double? = null,
) {
    val hasAnyData: Boolean
        get() = steps != null || sessions.isNotEmpty() || restingHeartRate != null ||
            hrvRmssd != null || sleepMinutes != null
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
    /** 用户填写了实测最大心率。 */
    MEASURED_MAX_HR,
    /** 按年龄（和性别）估算最大心率。 */
    ESTIMATED_MAX_HR,
    /** 未填写年龄，最大心率按 190 估算。 */
    DEFAULT_MAX_HR,
    /** 女性使用 Banister TRIMP 的女性系数 (0.86, 1.67)。 */
    FEMALE_LOAD_COEFFICIENT,
    /** 50 岁以上：加量幅度从 10% 降到 5%，连续训练 4 天就提醒休息。 */
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

    data class RestingHrElevated(val delta: Double, override val severity: Severity) : Signal
    data class HrvSuppressed(val ratio: Double, override val severity: Severity) : Signal
    data class ShortSleep(val minutes: Double, override val severity: Severity) : Signal
    data class SleepDebt(val avgMinutes: Double) : Signal { override val severity = Severity.MODERATE }
    data class ConsecutiveTrainingDays(val days: Int, override val severity: Severity) : Signal
    data class HighMonotony(val monotony: Double) : Signal { override val severity = Severity.MODERATE }
    data class HardSessionYesterday(val ratioToTypical: Double) : Signal {
        override val severity = Severity.MODERATE
    }
    /** 有恢复数据且全部正常。 */
    data object RecoveryGood : Signal { override val severity = Severity.INFO }
}

data class HeartRateRange(val low: Int, val high: Int)

data class RecoveryStatus(
    val restingHr: Double? = null,
    val restingHrBaseline: Double? = null,
    val hrv: Double? = null,
    val hrvBaseline: Double? = null,
    val lastSleepMinutes: Double? = null,
    val avgSleepMinutes3d: Double? = null,
)

data class DailyLoad(val date: LocalDate, val load: Double)

data class Recommendation(
    val forDate: LocalDate,
    val level: Level,
    /** 仅由急慢性负荷比得出的等级（恢复信号修正之前）。 */
    val baseLevel: Level,
    val confidence: Confidence,
    val acuteLoad: Double,
    val chronicLoad: Double,
    val acwr: Double?,
    val targetLoad: Double,
    val suggestedMinutes: Int,
    val heartRateRange: HeartRateRange?,
    val signals: List<Signal>,
    val recovery: RecoveryStatus,
    /** 最近 28 天的每日负荷，最早的在前，用于画图。 */
    val dailyLoads: List<DailyLoad>,
    val profile: UserProfile,
    val maxHeartRate: Int,
    val adjustments: List<Adjustment>,
)
