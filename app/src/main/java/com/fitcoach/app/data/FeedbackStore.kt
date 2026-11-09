package com.fitcoach.app.data

import android.content.Context
import com.fitcoach.engine.DailyMetrics
import java.time.LocalDate

/** 用户每天的主观反馈。 */
data class Feedback(
    /** 训练的主观疲劳度，Foster CR-10 量表 0–10。 */
    val rpe: Int? = null,
    /** 早晨身体状态 1–5。 */
    val wellness: Int? = null,
)

/** 用户自评数据保存在本机（SharedPreferences，键为日期）。 */
class FeedbackStore(context: Context) {

    private val prefs = context.getSharedPreferences("feedback", Context.MODE_PRIVATE)

    fun get(date: LocalDate): Feedback = parse(prefs.getString(date.toString(), null))

    fun all(): Map<LocalDate, Feedback> = prefs.all.mapNotNull { (key, value) ->
        runCatching { LocalDate.parse(key) to parse(value as? String) }.getOrNull()
    }.toMap()

    fun setRpe(date: LocalDate, rpe: Int?) = update(date) { it.copy(rpe = rpe?.coerceIn(0, 10)) }

    fun setWellness(date: LocalDate, wellness: Int?) = update(date) { it.copy(wellness = wellness?.coerceIn(1, 5)) }

    /** 把反馈合并进历史数据；没有该日期的条目时补一条。 */
    fun mergeInto(history: List<DailyMetrics>): List<DailyMetrics> {
        val feedback = all()
        if (feedback.isEmpty()) return history
        val existing = history.map { it.date }.toSet()
        val merged = history.map { d ->
            feedback[d.date]?.let { d.copy(sessionRpe = it.rpe, wellness = it.wellness) } ?: d
        }
        val extra = feedback.filterKeys { it !in existing && history.isNotEmpty() && it > history.first().date }
            .map { (date, f) -> DailyMetrics(date, sessionRpe = f.rpe, wellness = f.wellness) }
        return (merged + extra).sortedBy { it.date }
    }

    private fun update(date: LocalDate, transform: (Feedback) -> Feedback) {
        val updated = transform(get(date))
        val editor = prefs.edit()
        if (updated.rpe == null && updated.wellness == null) editor.remove(date.toString())
        else editor.putString(date.toString(), "${updated.rpe ?: ""},${updated.wellness ?: ""}")
        // 顺便清理 180 天前的旧数据
        val cutoff = LocalDate.now().minusDays(180)
        prefs.all.keys.filter { key -> runCatching { LocalDate.parse(key) < cutoff }.getOrDefault(false) }
            .forEach(editor::remove)
        editor.apply()
    }

    private fun parse(value: String?): Feedback {
        if (value.isNullOrEmpty()) return Feedback()
        val parts = value.split(",")
        return Feedback(parts.getOrNull(0)?.toIntOrNull(), parts.getOrNull(1)?.toIntOrNull())
    }
}
