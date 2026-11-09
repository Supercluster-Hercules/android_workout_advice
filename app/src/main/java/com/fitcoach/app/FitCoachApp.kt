package com.fitcoach.app

import android.app.Application
import android.util.Log
import com.fitcoach.app.data.AppSettings
import com.fitcoach.app.data.CacheStore
import com.fitcoach.app.data.FeedbackStore
import com.fitcoach.app.data.HealthConnectRepository
import com.fitcoach.app.data.HealthSyncRepository
import com.fitcoach.app.data.ProfileRepository
import com.fitcoach.app.ui.Texts
import com.fitcoach.app.widget.AdviceWidgetProvider
import com.fitcoach.engine.DailyMetrics
import com.fitcoach.engine.Recommendation
import com.fitcoach.engine.RecommendationEngine
import com.fitcoach.engine.TrendAnalyzer
import com.fitcoach.engine.Trends
import com.fitcoach.engine.UserProfile
import com.fitcoach.engine.WeekPlan
import com.fitcoach.engine.WeeklyPlanner
import java.time.LocalDate
import java.time.LocalTime

/**
 * 应用级单例容器。界面、后台任务、桌面小组件共用同一个 [HealthSyncRepository]，
 * 保证同一时间只有一个同步在跑，内存里的缓存也只有一份。
 */
class FitCoachApp : Application() {

    val healthConnect by lazy { HealthConnectRepository(this) }
    val sync by lazy {
        HealthSyncRepository(healthConnect, CacheStore(this), log = { Log.i("HealthSync", it) })
    }
    val profiles by lazy { ProfileRepository(this) }
    val feedback by lazy { FeedbackStore(this) }
    val settings by lazy { AppSettings(this) }

    private val engine = RecommendationEngine()

    data class Advice(val recommendation: Recommendation, val week: WeekPlan, val trends: Trends)

    /** 根据历史数据算出当天建议、本周计划和长期趋势。纯计算，几毫秒。 */
    fun computeAdvice(
        history: List<DailyMetrics>,
        forDate: LocalDate,
        profile: UserProfile,
        includeFeedback: Boolean = true,
    ): Advice {
        val merged = if (includeFeedback) feedback.mergeInto(history) else history
        val recommendation = engine.recommend(merged, forDate, profile)
        val end = minOf(forDate, LocalDate.now())
        return Advice(recommendation, WeeklyPlanner.plan(recommendation), TrendAnalyzer.analyze(merged, profile, end))
    }

    /** 保存最新建议摘要并刷新桌面小组件。 */
    fun publishLatest(r: Recommendation) {
        settings.latestAdvice = AppSettings.LatestAdvice(
            dayLabel = Texts.dayLabel(r.forDate),
            level = r.level,
            title = Texts.level(r.level),
            plan = Texts.plan(r),
        )
        AdviceWidgetProvider.updateAll(this)
    }

    companion object {
        /** 计算和趋势页需要的历史天数（12 周）。 */
        const val HISTORY_DAYS = 84L

        /** 中午之前给"今天"的建议（今天还没开始练），之后给"明天"的建议。 */
        fun targetDate(now: LocalTime = LocalTime.now()): LocalDate =
            if (now.isBefore(LocalTime.NOON)) LocalDate.now() else LocalDate.now().plusDays(1)

        /** forDate 对应要读取的历史区间：恢复信号可能来自 forDate 当天，所以读到 forDate 为止。 */
        fun historyRange(forDate: LocalDate): Pair<LocalDate, LocalDate> =
            forDate.minusDays(HISTORY_DAYS) to minOf(forDate, LocalDate.now())
    }
}
