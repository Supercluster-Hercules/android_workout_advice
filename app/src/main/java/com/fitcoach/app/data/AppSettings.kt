package com.fitcoach.app.data

import android.content.Context
import com.fitcoach.engine.Level

/** 应用设置，以及供桌面小组件和通知读取的"最新一次建议"摘要。 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var morningNotification: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY, value).apply()

    data class LatestAdvice(val dayLabel: String, val level: Level, val title: String, val plan: String)

    var latestAdvice: LatestAdvice?
        get() {
            val level = prefs.getString(KEY_LEVEL, null)?.let { runCatching { Level.valueOf(it) }.getOrNull() }
                ?: return null
            return LatestAdvice(
                dayLabel = prefs.getString(KEY_DAY, "") ?: "",
                level = level,
                title = prefs.getString(KEY_TITLE, "") ?: "",
                plan = prefs.getString(KEY_PLAN, "") ?: "",
            )
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_LEVEL)
            } else {
                editor.putString(KEY_DAY, value.dayLabel)
                    .putString(KEY_LEVEL, value.level.name)
                    .putString(KEY_TITLE, value.title)
                    .putString(KEY_PLAN, value.plan)
            }
            editor.apply()
        }

    private companion object {
        const val KEY_NOTIFY = "morning_notification"
        const val KEY_DAY = "advice_day"
        const val KEY_LEVEL = "advice_level"
        const val KEY_TITLE = "advice_title"
        const val KEY_PLAN = "advice_plan"
    }
}
