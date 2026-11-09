package com.fitcoach.app.data

import com.fitcoach.engine.cache.CachedSession
import com.fitcoach.engine.cache.Change
import com.fitcoach.engine.cache.MetricsCache
import com.fitcoach.engine.cache.RangeSnapshot
import java.time.LocalDate

/** 同步逻辑依赖的数据源。生产环境是 [HealthConnectRepository]，测试里用假实现。 */
interface HealthDataSource {
    suspend fun grantedPermissions(): Set<String>

    /** 已授权的、需要跟踪变更的权限（用于判断授权是否变化）。 */
    fun trackedPermissions(granted: Set<String>): Set<String>

    suspend fun getChangesToken(granted: Set<String>): String

    suspend fun getChanges(token: String): ChangesResult

    suspend fun fetchRange(
        start: LocalDate,
        end: LocalDate,
        granted: Set<String>,
        reuse: (id: String, lastModifiedEpochMs: Long, date: LocalDate) -> CachedSession?,
    ): RangeSnapshot

    suspend fun fetchAggregates(
        start: LocalDate,
        end: LocalDate,
        granted: Set<String>,
    ): Pair<Map<LocalDate, Int>, Map<LocalDate, Double>>
}

sealed interface ChangesResult {
    data class Ok(val changes: List<Change>, val nextToken: String) : ChangesResult
    /** 令牌过期（超过 30 天没同步），需要重新完整导入。 */
    data object Expired : ChangesResult
}

/** 缓存的持久化。生产环境是 [CacheStore]（JSON 文件）。 */
interface CacheStorage {
    suspend fun load(): MetricsCache
    suspend fun save(cache: MetricsCache)
    suspend fun delete()
}
