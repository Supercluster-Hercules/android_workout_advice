package com.fitcoach.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.fitcoach.app.MainActivity
import com.fitcoach.app.R
import com.fitcoach.app.data.AppSettings
import com.fitcoach.engine.Level

/** 桌面小组件：显示最新一次建议，点击打开应用。用系统自带的 RemoteViews，不需要额外依赖。 */
class AdviceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AdviceWidgetProvider::class.java))
            if (ids.isNotEmpty()) ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val advice = AppSettings(context).latestAdvice
            val views = RemoteViews(context.packageName, R.layout.widget_advice)
            if (advice == null) {
                views.setTextViewText(R.id.widget_day, "运动建议")
                views.setTextViewText(R.id.widget_level, "打开应用")
                views.setTextViewText(R.id.widget_plan, "授权读取健康数据后，这里会显示每天的建议")
                views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_maintain)
            } else {
                views.setTextViewText(R.id.widget_day, "${advice.dayLabel}建议")
                views.setTextViewText(R.id.widget_level, advice.title)
                views.setTextViewText(R.id.widget_plan, advice.plan)
                views.setInt(
                    R.id.widget_root,
                    "setBackgroundResource",
                    when (advice.level) {
                        Level.INCREASE -> R.drawable.widget_bg_increase
                        Level.MAINTAIN -> R.drawable.widget_bg_maintain
                        Level.DECREASE -> R.drawable.widget_bg_decrease
                        Level.REST -> R.drawable.widget_bg_rest
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }
    }
}
