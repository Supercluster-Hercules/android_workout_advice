package com.fitcoach.app.ui

import com.fitcoach.app.data.HealthSyncRepository
import com.fitcoach.engine.Adjustment
import com.fitcoach.engine.Confidence
import com.fitcoach.engine.DayKind
import com.fitcoach.engine.Level
import com.fitcoach.engine.Recommendation
import com.fitcoach.engine.SampleData
import com.fitcoach.engine.Sex
import com.fitcoach.engine.Signal
import com.fitcoach.engine.UserProfile
import com.fitcoach.engine.WeekNote
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** 把引擎输出翻译成中文文案。如果以后要做多语言，可以迁移到 strings.xml。 */
object Texts {

    fun level(level: Level) = when (level) {
        Level.INCREASE -> "适当加量"
        Level.MAINTAIN -> "保持当前节奏"
        Level.DECREASE -> "适当减量"
        Level.REST -> "休息恢复"
    }

    fun dayLabel(date: LocalDate): String = when (date) {
        LocalDate.now() -> "今天"
        LocalDate.now().plusDays(1) -> "明天"
        else -> date.format(DateTimeFormatter.ofPattern("M月d日"))
    }

    fun plan(r: Recommendation): String {
        if (r.possibleIllness) return "身体可能有生病的迹象，建议完全休息，多喝水、早点睡，留意是否有发烧或其他不适"
        val hr = r.heartRateRange?.let { "，心率 ${it.low}–${it.high} bpm" } ?: ""
        return when (r.level) {
            Level.INCREASE -> "约 ${r.suggestedMinutes} 分钟有氧$hr，可以加入一小段节奏跑"
            Level.MAINTAIN -> "约 ${r.suggestedMinutes} 分钟轻松有氧$hr"
            Level.DECREASE -> "约 ${r.suggestedMinutes} 分钟低强度活动$hr，例如慢跑、骑车或游泳"
            Level.REST -> "不安排训练，可以散步 20–30 分钟或做拉伸 / 泡沫轴放松"
        }
    }

    fun confidence(c: Confidence) = when (c) {
        Confidence.HIGH -> "可信度高"
        Confidence.MEDIUM -> "可信度中（缺少心率类数据）"
        Confidence.LOW -> "可信度低（历史数据不足）"
    }

    fun signal(s: Signal): String = when (s) {
        is Signal.InsufficientData ->
            "近 28 天只有 ${s.daysWithData} 天有数据（至少需要 ${s.required} 天），暂按维持处理"
        is Signal.AcwrLow -> "急慢性负荷比 ${f2(s.acwr)}，近一周练得比平时少，可以逐步加回来"
        is Signal.AcwrOptimal -> "急慢性负荷比 ${f2(s.acwr)}，处在 0.8–1.3 的安全区间"
        is Signal.AcwrElevated -> "急慢性负荷比 ${f2(s.acwr)}，近一周加量偏快"
        is Signal.AcwrHigh -> "急慢性负荷比 ${f2(s.acwr)}，超过 1.5，受伤风险明显升高"
        is Signal.RestingHrElevated ->
            "静息心率比平时高 ${s.delta.roundToInt()} bpm（你的日常波动下，超过 ${f1(s.threshold)} bpm 就算偏高）"
        is Signal.HrvSuppressed ->
            if (s.acute) "今天的 HRV 骤降到平时的 ${(s.ratio * 100).roundToInt()}%，比平时低了 ${f1(abs(s.zScore))} 个标准差"
            else "近 7 天 HRV 平均只有基线的 ${(s.ratio * 100).roundToInt()}%，已跌出你的正常波动范围"
        is Signal.ShortSleep -> "昨晚只睡了 ${hours(s.minutes)}"
        is Signal.SleepDebt -> "最近几晚平均只睡 ${hours(s.avgMinutes)}"
        is Signal.LowSleepEfficiency -> "睡眠效率 ${(s.efficiency * 100).roundToInt()}%，夜里醒的时间偏多（85% 以上较好）"
        is Signal.ConsecutiveTrainingDays -> "已经连续训练 ${s.days} 天，需要安排休息"
        is Signal.HighMonotony -> "最近一周每天强度都差不多，单调度偏高，建议拉开强弱"
        is Signal.HardSessionYesterday -> "上一次训练量是平时的 ${f1(s.ratioToTypical)} 倍"
        is Signal.VeryHardSessionReported -> "你自评上次训练非常累（${s.rpe}/10）"
        is Signal.PoorWellness -> "你自评今天早上状态${if (s.score == 1) "很差" else "不太好"}"
        is Signal.PossibleIllness -> buildString {
            append("可能是生病前兆：")
            append(
                listOfNotNull(
                    s.temperatureC?.takeIf { s.fever }?.let { "体温 ${f1(it)}℃" },
                    s.skinTempDeltaC?.takeIf { s.fever }?.let { "夜间皮肤温度比平时高 ${f1(it)}℃" },
                    if (s.restingHrDays >= 2) "静息心率已连续 ${s.restingHrDays} 天偏高且 HRV 偏低" else null,
                ).joinToString("，"),
            )
        }
        is Signal.MenstrualCycle ->
            if (s.onPeriod) "经期第 ${s.dayOfCycle} 天：如果感觉疲劳或不适，可以按感受降低强度（研究结论不一，仅供参考）"
            else "生理周期第 ${s.dayOfCycle} 天（仅供参考，不影响建议）"
        Signal.RecoveryGood -> "恢复指标正常"
    }

    fun profileSummary(p: UserProfile): String {
        if (p.age == null && p.sex == null && p.maxHeartRate == null) return "未设置年龄和性别"
        return listOfNotNull(
            p.age?.let { "$it 岁" },
            when (p.sex) {
                Sex.MALE -> "男"
                Sex.FEMALE -> "女"
                null -> null
            },
            "最大心率 ${p.estimatedMaxHr().roundToInt()}" + if (p.maxHeartRate != null) "（实测）" else "（估算）",
        ).joinToString(" · ")
    }

    fun adjustment(a: Adjustment, r: Recommendation): String = when (a) {
        Adjustment.MEASURED_MAX_HR -> "使用你填写的最大心率 ${r.maxHeartRate} bpm 计算强度和心率区间"
        Adjustment.ESTIMATED_MAX_HR ->
            if (r.profile.sex == Sex.FEMALE) "按女性公式 206 − 0.88 × 年龄，估算最大心率约 ${r.maxHeartRate} bpm"
            else "按公式 208 − 0.7 × 年龄，估算最大心率约 ${r.maxHeartRate} bpm"
        Adjustment.DEFAULT_MAX_HR -> "还没填写年龄，最大心率暂按 190 bpm 估算，建议点「修改资料」填写"
        Adjustment.FEMALE_LOAD_COEFFICIENT -> "按女性的心率-负荷系数计算训练负荷"
        Adjustment.OLDER_CONSERVATIVE -> "50 岁以上恢复较慢：加量幅度控制在 5%，连续训练 4 天就提醒休息，每周至少休息 2 天"
        Adjustment.SENIOR_LOWER_ACWR -> "65 岁以上：急慢性负荷比的安全上限从 1.3 收紧到 1.2"
        Adjustment.YOUTH_SLEEP_NEED -> "18 岁以下需要 8–10 小时睡眠，判断睡眠不足的标准相应提高"
    }

    fun dayKind(k: DayKind) = when (k) {
        DayKind.REST -> "休息"
        DayKind.EASY -> "轻松"
        DayKind.MODERATE -> "中等"
        DayKind.HARD -> "强度"
    }

    fun weekday(d: DayOfWeek) = when (d) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }

    fun weekNote(n: WeekNote): String? = when (n) {
        WeekNote.NONE -> null
        WeekNote.TARGET_REACHED -> "本周目标已基本完成，剩下几天以轻松活动和休息为主"
        WeekNote.ILLNESS -> "身体可能不适，本周剩余时间以恢复为主，感觉好转后再逐步恢复训练"
    }

    fun wellnessLabel(v: Int) = when (v) {
        1 -> "很差"
        2 -> "较差"
        3 -> "一般"
        4 -> "不错"
        else -> "很好"
    }

    /** Foster CR-10 量表的描述。 */
    fun rpeLabel(v: Int) = when (v) {
        0 -> "完全没练"
        1 -> "非常轻松"
        2 -> "轻松"
        3 -> "中等"
        4 -> "有点累"
        5, 6 -> "累"
        7, 8 -> "很累"
        9 -> "非常累"
        else -> "到极限了"
    }

    fun syncStatus(s: SyncStatus): String = when (s) {
        SyncStatus.Syncing -> "正在同步新数据，先显示上次的结果…"
        is SyncStatus.Done -> {
            val seconds = "%.1f".format(s.durationMs / 1000.0)
            when (s.mode) {
                HealthSyncRepository.Mode.FULL -> "已完整导入最近 30 天的数据，用时 $seconds 秒"
                HealthSyncRepository.Mode.INCREMENTAL -> "已增量更新 ${s.changedDays} 天的数据，用时 $seconds 秒"
                HealthSyncRepository.Mode.UP_TO_DATE -> "数据已是最新，用时 $seconds 秒"
            }
        }
        is SyncStatus.Failed -> "同步失败，显示的是上次的数据（${s.message}）"
    }

    fun scenario(s: SampleData.Scenario) = when (s) {
        SampleData.Scenario.BALANCED -> "规律训练"
        SampleData.Scenario.DETRAINING -> "最近偷懒"
        SampleData.Scenario.OVERREACHING -> "突然加量"
        SampleData.Scenario.FATIGUED -> "疲劳未恢复"
        SampleData.Scenario.PHONE_ONLY -> "只有手机"
    }

    fun hours(minutes: Double): String {
        val m = minutes.roundToInt()
        return "${m / 60} 小时 ${m % 60} 分"
    }

    fun f1(v: Double) = "%.1f".format(v)
    fun f2(v: Double) = "%.2f".format(v)
}
