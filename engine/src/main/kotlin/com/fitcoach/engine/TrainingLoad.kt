package com.fitcoach.engine

import kotlin.math.exp
import kotlin.math.max

/**
 * 训练负荷计算，统一使用 Banister TRIMP 作为单位：
 *   TRIMP/分钟 = HRR × 0.64 × e^(1.92 × HRR)，HRR = (运动心率 - 静息心率) / (最大心率 - 静息心率)
 *
 * 缺少心率时的降级方案：
 * - 有运动记录但没有心率：按中等强度 (HRR 0.6) 估算；
 * - 只有步数：超过日常基线的步数折算成步行分钟，按低强度 (HRR 0.35) 估算。
 */
object TrainingLoad {
    const val DEFAULT_RESTING_HR = 60.0
    const val UNKNOWN_SESSION_HRR = 0.6
    const val WALKING_HRR = 0.35
    const val STEPS_BASELINE = 3000
    const val STEPS_PER_WALKING_MINUTE = 110.0

    /**
     * Banister TRIMP 的性别系数：男性 0.64·e^(1.92x)，女性 0.86·e^(1.67x)。
     * 未填写性别时用男性系数（也是最常用的默认值）。
     */
    fun trimpPerMinute(hrr: Double, sex: Sex? = null): Double {
        val x = hrr.coerceIn(0.0, 1.0)
        return if (sex == Sex.FEMALE) x * 0.86 * exp(1.67 * x) else x * 0.64 * exp(1.92 * x)
    }

    fun heartRateReserve(hr: Double, restingHr: Double, maxHr: Double): Double =
        if (maxHr <= restingHr) UNKNOWN_SESSION_HRR else (hr - restingHr) / (maxHr - restingHr)

    fun sessionLoad(session: ExerciseSession, restingHr: Double, maxHr: Double, sex: Sex? = null): Double {
        val hrr = session.avgHeartRate?.let { heartRateReserve(it, restingHr, maxHr) }
            ?: UNKNOWN_SESSION_HRR
        return session.durationMinutes * trimpPerMinute(hrr, sex)
    }

    fun dailyLoad(day: DailyMetrics, profile: UserProfile, restingHrBaseline: Double?): Double {
        val restingHr = day.restingHeartRate ?: restingHrBaseline ?: DEFAULT_RESTING_HR
        val maxHr = profile.estimatedMaxHr()
        val sessionLoad = day.sessions.sumOf { sessionLoad(it, restingHr, maxHr, profile.sex) }
        val sessionMinutes = day.sessions.sumOf { it.durationMinutes }
        // 运动记录期间的步数也计入了总步数，扣掉运动时长避免重复计算
        val walkingMinutes = day.steps?.let {
            max(0.0, (it - STEPS_BASELINE) / STEPS_PER_WALKING_MINUTE - sessionMinutes)
        } ?: 0.0
        return sessionLoad + walkingMinutes * trimpPerMinute(WALKING_HRR, profile.sex)
    }
}
