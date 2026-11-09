package com.fitcoach.engine

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random

/** 生成模拟数据，用于演示模式和测试（模拟器里通常没有真实健康数据）。 */
object SampleData {

    enum class Scenario {
        /** 规律训练、恢复良好。 */
        BALANCED,
        /** 最近 10 天几乎没练，负荷下降。 */
        DETRAINING,
        /** 最近一周突然大幅加量。 */
        OVERREACHING,
        /** 负荷正常，但最近几天静息心率升高、HRV 下降、睡眠不足。 */
        FATIGUED,
        /** 只有手机：只有步数和不带心率的运动记录。 */
        PHONE_ONLY,
    }

    private data class Plan(val minutes: Double, val hr: Double, val type: String)

    private val weeklyPlan = mapOf(
        DayOfWeek.MONDAY to Plan(40.0, 150.0, "跑步"),
        DayOfWeek.WEDNESDAY to Plan(50.0, 145.0, "跑步"),
        DayOfWeek.FRIDAY to Plan(30.0, 160.0, "间歇跑"),
        DayOfWeek.SATURDAY to Plan(75.0, 140.0, "长距离跑"),
    )

    /** 生成截至 [endDate]（含）的 [days] 天数据。 */
    fun generate(
        scenario: Scenario,
        endDate: LocalDate,
        days: Int = 35,
        seed: Long = 42,
    ): List<DailyMetrics> {
        val rnd = Random(seed)
        fun jitter(base: Double, pct: Double) = base * (1 + (rnd.nextDouble() * 2 - 1) * pct)

        return (days - 1 downTo 0).map { offset ->
            val date = endDate.minusDays(offset.toLong())
            val daysFromEnd = offset // 0 = 最后一天

            var plan = weeklyPlan[date.dayOfWeek]
            when (scenario) {
                Scenario.DETRAINING -> if (daysFromEnd < 10) {
                    plan = if (daysFromEnd % 5 == 0) Plan(20.0, 135.0, "慢跑") else null
                }
                Scenario.OVERREACHING -> if (daysFromEnd < 7) {
                    plan = Plan(70.0, 162.0, "跑步") // 每天都练，时长和强度都上去了
                }
                else -> Unit
            }

            val session = plan?.let {
                ExerciseSession(
                    durationMinutes = jitter(it.minutes, 0.1),
                    avgHeartRate = jitter(it.hr, 0.03),
                    type = it.type,
                )
            }
            val steps = jitter(7000.0, 0.15) + (session?.durationMinutes ?: 0.0) * 150

            var rhr = jitter(55.0, 0.03)
            var hrv = jitter(62.0, 0.2) // 真实用户每天 HRV 波动大约 10% 以上
            var sleep = jitter(450.0, 0.06)
            if (scenario == Scenario.FATIGUED && daysFromEnd < 3) {
                rhr += 7.0
                hrv *= 0.72
                sleep = jitter(330.0, 0.05)
            }

            if (scenario == Scenario.PHONE_ONLY) {
                DailyMetrics(
                    date = date,
                    steps = steps.toInt(),
                    sessions = listOfNotNull(session?.copy(avgHeartRate = null)),
                )
            } else {
                DailyMetrics(
                    date = date,
                    steps = steps.toInt(),
                    sessions = listOfNotNull(session),
                    restingHeartRate = rhr,
                    hrvRmssd = hrv,
                    sleepMinutes = sleep,
                )
            }
        }
    }
}
