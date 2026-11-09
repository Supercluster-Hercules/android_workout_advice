package com.fitcoach.app.data

import android.os.SystemClock
import android.util.Log
import com.fitcoach.engine.DailyMetrics
import com.fitcoach.engine.cache.MetricsCache
import com.fitcoach.engine.cache.contiguousRanges
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * 同步策略：
 * 1. 第一次（或令牌过期、权限变化、用户要求时）：完整导入最近 [SYNC_DAYS] 天，存入本地缓存，并记下变更令牌；
 * 2. 之后每次：用变更令牌向 Health Connect 询问"上次之后哪些记录新增 / 修改 / 删除了"，
 *    只重新读取受影响的日期，整段替换缓存里这些日期的数据（所以不会重复）；
 * 3. 最近两天总是重新读取一次：手表的数据经常延迟同步。
 *
 * 用 [Mutex] 保证同一时间只有一个同步在跑（连点刷新也不会并发写缓存）。
 */
class HealthSyncRepository(
    private val hc: HealthConnectRepository,
    private val store: CacheStore,
) {
    enum class Mode { FULL, INCREMENTAL, UP_TO_DATE }

    data class Result(
        val history: List<DailyMetrics>,
        val mode: Mode,
        /** 因变更而重新读取的天数（不含每次都刷新的最近两天）。 */
        val changedDays: Int,
        val durationMs: Long,
    )

    private val mutex = Mutex()
    private var cache: MetricsCache? = null

    private suspend fun cache(): MetricsCache = cache ?: store.load().also { cache = it }

    /** 只读本地缓存，不访问 Health Connect，几毫秒就能返回。没有缓存时返回 null。 */
    suspend fun cachedHistory(start: LocalDate, end: LocalDate): List<DailyMetrics>? = mutex.withLock {
        cache().takeIf { it.hasCompletedFullSync }?.toDailyMetrics(start, end)
    }

    suspend fun sync(start: LocalDate, end: LocalDate, forceFull: Boolean = false): Result = mutex.withLock {
        val startedAt = SystemClock.elapsedRealtime()
        val c = cache()
        val today = LocalDate.now()
        val windowStart = today.minusDays(SYNC_DAYS)
        val granted = hc.grantedPermissions()
        val tracked = hc.trackedPermissions(granted)

        var mode = Mode.FULL
        var changedDays = 0

        val token = c.changesToken
        val incremental = if (forceFull || token == null || c.tokenPermissions != tracked) null
        else hc.getChanges(token) as? HealthConnectRepository.ChangesResult.Ok // 过期时为 null

        if (incremental == null) {
            // 先拿令牌再读数据：读取期间发生的变更下次还会收到，重复处理也只是整段替换，不会出错
            val newToken = hc.getChangesToken(granted)
            val snapshot = hc.fetchRange(windowStart, today, granted) { id, modified, date ->
                c.reusableSession(id, modified, date, today)
            }
            c.clear()
            c.applySnapshot(snapshot)
            c.changesToken = newToken
            c.tokenPermissions = tracked
            changedDays = SYNC_DAYS.toInt() + 1
        } else {
            val plan = c.plan(incremental.changes, windowStart, today)
            mode = if (plan.isEmpty) Mode.UP_TO_DATE else Mode.INCREMENTAL
            changedDays = plan.dirtyDates.size

            if (plan.refreshAllAggregates) {
                val (steps, restingHr) = hc.fetchAggregates(windowStart, today, granted)
                c.applyAggregates(windowStart, today, steps, restingHr)
            }
            val dirty = plan.dirtyDates + today.minusDays(1) + today
            for ((from, to) in contiguousRanges(dirty)) {
                c.applySnapshot(
                    hc.fetchRange(from, to, granted) { id, modified, date -> c.reusableSession(id, modified, date, today) },
                )
            }
            c.changesToken = incremental.nextToken
        }

        c.prune(today.minusDays(RETENTION_DAYS))
        c.lastSyncEpochMs = System.currentTimeMillis()
        store.save(c)

        val duration = SystemClock.elapsedRealtime() - startedAt
        Log.i(TAG, "sync mode=$mode changedDays=$changedDays took=${duration}ms")
        Result(c.toDailyMetrics(start, end), mode, changedDays, duration)
    }

    /** 清空缓存，下次同步会完整导入。 */
    suspend fun clear() = mutex.withLock {
        cache().clear()
        store.delete()
    }

    companion object {
        /** 完整导入的天数（Health Connect 默认只允许读取授权前 30 天的数据）。 */
        const val SYNC_DAYS = 30L
        /** 本地最多保留的天数。缓存会随时间积累超过 30 天的历史，方便以后做长期趋势。 */
        const val RETENTION_DAYS = 180L
        private const val TAG = "HealthSync"
    }
}
