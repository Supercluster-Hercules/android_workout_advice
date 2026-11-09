package com.fitcoach.app.ui

import com.fitcoach.app.data.HealthSyncRepository
import com.fitcoach.engine.Adjustment
import com.fitcoach.engine.Confidence
import com.fitcoach.engine.Sex
import com.fitcoach.engine.UserProfile
import com.fitcoach.engine.Level
import com.fitcoach.engine.Recommendation
import com.fitcoach.engine.SampleData
import com.fitcoach.engine.Signal
import kotlin.math.roundToInt

/** 把引擎输出翻译成中文文案。如果以后要做多语言，可以迁移到 strings.xml。 */
object Texts {

    fun level(level: Level) = when (level) {
        Level.INCREASE -> "适当加量"
        Level.MAINTAIN -> "保持当前节奏"
        Level.DECREASE -> "适当减量"
        Level.REST -> "休息恢复"
    }

    fun plan(r: Recommendation): String {
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
        is Signal.RestingHrElevated -> "静息心率比平时高 ${s.delta.roundToInt()} bpm，身体可能还没恢复"
        is Signal.HrvSuppressed -> "心率变异性 (HRV) 只有平时的 ${(s.ratio * 100).roundToInt()}%"
        is Signal.ShortSleep -> "昨晚只睡了 ${hours(s.minutes)}"
        is Signal.SleepDebt -> "最近几晚平均只睡 ${hours(s.avgMinutes)}"
        is Signal.ConsecutiveTrainingDays -> "已经连续训练 ${s.days} 天，需要安排休息"
        is Signal.HighMonotony -> "最近一周每天强度都差不多，单调度偏高，建议拉开强弱"
        is Signal.HardSessionYesterday -> "上一次训练量是平时的 ${f1(s.ratioToTypical)} 倍"
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
        Adjustment.OLDER_CONSERVATIVE -> "50 岁以上恢复较慢：加量幅度控制在 5%，连续训练 4 天就提醒休息"
        Adjustment.SENIOR_LOWER_ACWR -> "65 岁以上：急慢性负荷比的安全上限从 1.3 收紧到 1.2"
        Adjustment.YOUTH_SLEEP_NEED -> "18 岁以下需要 8–10 小时睡眠，判断睡眠不足的标准相应提高"
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
