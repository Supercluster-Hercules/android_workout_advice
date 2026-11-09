package com.fitcoach.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.MainActivity
import com.fitcoach.app.R
import com.fitcoach.app.ui.Texts
import com.fitcoach.engine.Recommendation
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 每天早上运行：后台同步 Health Connect（需要后台读取权限；没有时退回用缓存），
 * 算出今天的建议，推送通知并刷新桌面小组件。
 */
class DailyAdviceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FitCoachApp
        val forDate = FitCoachApp.targetDate()
        val (start, end) = FitCoachApp.historyRange(forDate)

        val history = try {
            app.sync.sync(start, end).history
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 没有后台读取权限或 Health Connect 暂时不可用：用上次缓存的数据
            Log.w(TAG, "后台同步失败，使用缓存", e)
            app.sync.cachedHistory(start, end)
        } ?: return Result.success() // 还没导入过数据

        val advice = app.computeAdvice(history, forDate, app.profiles.load())
        app.publishLatest(advice.recommendation)
        if (app.settings.morningNotification) showNotification(applicationContext, advice.recommendation)
        return Result.success()
    }

    companion object {
        private const val TAG = "DailyAdviceWorker"
        private const val WORK_NAME = "daily_advice"
        private const val CHANNEL_ID = "daily_advice"
        private const val NOTIFICATION_ID = 1
        private val RUN_AT: LocalTime = LocalTime.of(7, 30)

        /** 安排每天早上 7:30 左右运行（系统可能为省电稍有延后）。 */
        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(RUN_AT)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<DailyAdviceWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun showNotification(context: Context, r: Recommendation) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "每日运动建议", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${Texts.dayLabel(r.forDate)}建议：${Texts.level(r.level)}")
                .setContentText(Texts.plan(r))
                .setStyle(NotificationCompat.BigTextStyle().bigText(Texts.plan(r)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
