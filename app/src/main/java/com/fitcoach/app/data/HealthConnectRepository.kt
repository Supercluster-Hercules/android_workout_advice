package com.fitcoach.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.fitcoach.engine.cache.CachedSession
import com.fitcoach.engine.cache.Change
import com.fitcoach.engine.cache.RangeSnapshot
import com.fitcoach.engine.cache.RecordKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/** 封装所有 Health Connect 调用。缓存与同步策略见 [HealthSyncRepository]。 */
class HealthConnectRepository(private val context: Context) {

    enum class Availability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED }

    sealed interface ChangesResult {
        data class Ok(val changes: List<Change>, val nextToken: String) : ChangesResult
        /** 令牌过期（超过 30 天没同步），需要重新完整导入。 */
        data object Expired : ChangesResult
    }

    /** 需要跟踪变更的记录类型。心率不跟踪：数据量太大，且只用于计算运动平均心率。 */
    private val trackedTypes: Map<KClass<out Record>, RecordKind> = mapOf(
        StepsRecord::class to RecordKind.STEPS,
        ExerciseSessionRecord::class to RecordKind.EXERCISE,
        RestingHeartRateRecord::class to RecordKind.RESTING_HEART_RATE,
        HeartRateVariabilityRmssdRecord::class to RecordKind.HRV,
        SleepSessionRecord::class to RecordKind.SLEEP,
    )

    val permissions: Set<String> =
        (trackedTypes.keys + HeartRateRecord::class).map { HealthPermission.getReadPermission(it) }.toSet()

    /** 至少要有步数权限才能计算负荷；其余权限缺失时自动降级。 */
    val minimumPermissions: Set<String> = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun availability(): Availability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE_REQUIRED
            else -> Availability.NOT_INSTALLED
        }

    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    /** 已授权的、需要跟踪变更的权限（用于判断权限是否变化）。 */
    fun trackedPermissions(granted: Set<String>): Set<String> =
        trackedTypes.keys.map { HealthPermission.getReadPermission(it) }.filter { it in granted }.toSet()

    private fun Set<String>.has(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in this

    // ---------------- 变更记录（增量同步） ----------------

    suspend fun getChangesToken(granted: Set<String>): String =
        client.getChangesToken(ChangesTokenRequest(recordTypes = trackedTypes.keys.filter { granted.has(it) }.toSet()))

    /** 读取自 [token] 以来的所有变更（自动翻页）。 */
    suspend fun getChanges(token: String): ChangesResult {
        val result = mutableListOf<Change>()
        var current = token
        while (true) {
            val response = client.getChanges(current)
            if (response.changesTokenExpired) return ChangesResult.Expired
            for (change in response.changes) {
                when (change) {
                    is UpsertionChange -> toUpsert(change.record)?.let(result::add)
                    is DeletionChange -> result += Change.Delete(change.recordId)
                }
            }
            current = response.nextChangesToken
            if (!response.hasMore) return ChangesResult.Ok(result, current)
        }
    }

    private fun toUpsert(record: Record): Change.Upsert? {
        val kind = trackedTypes[record::class] ?: return null
        val dates = when (record) {
            is StepsRecord -> datesTouched(record.startTime, record.endTime)
            is ExerciseSessionRecord -> setOf(dateOf(record.startTime))
            is RestingHeartRateRecord -> setOf(dateOf(record.time))
            is HeartRateVariabilityRmssdRecord -> setOf(dateOf(record.time))
            is SleepSessionRecord -> setOf(dateOf(record.endTime)) // 睡眠归到起床那天
            else -> return null
        }
        return Change.Upsert(kind, record.metadata.id, dates)
    }

    private fun dateOf(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    private fun datesTouched(start: Instant, end: Instant): Set<LocalDate> {
        val first = dateOf(start)
        val last = dateOf(maxOf(start, end.minusMillis(1)))
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toSet()
    }

    // ---------------- 按日期区间读取 ----------------

    /**
     * 读取 [start]..[end]（含）的完整数据。各类数据并行读取。
     * [reuse] 返回非 null 时直接复用缓存的运动记录（含平均心率），省掉一次心率聚合查询。
     */
    suspend fun fetchRange(
        start: LocalDate,
        end: LocalDate,
        granted: Set<String>,
        reuse: (id: String, lastModifiedEpochMs: Long, date: LocalDate) -> CachedSession?,
    ): RangeSnapshot = coroutineScope {
        val startInstant = start.atStartOfDay(zone).toInstant()
        val endInstant = end.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(startInstant, endInstant)
        val inRange = { d: LocalDate -> !d.isBefore(start) && !d.isAfter(end) }

        val aggregates = async { fetchAggregates(start, end, granted) }

        val hrv = async {
            if (!granted.has(HeartRateVariabilityRmssdRecord::class)) emptyList()
            else readAll(HeartRateVariabilityRmssdRecord::class, range)
        }

        // 睡眠按起床日期归属，前一晚入睡的记录开始时间在区间之前，所以往前多读 18 小时
        val sleep = async {
            if (!granted.has(SleepSessionRecord::class)) emptyList()
            else readAll(
                SleepSessionRecord::class,
                TimeRangeFilter.between(startInstant.minus(Duration.ofHours(18)), endInstant),
            ).filter { inRange(dateOf(it.endTime)) }
        }

        val sessions = async {
            if (!granted.has(ExerciseSessionRecord::class)) emptyList()
            else readSessions(range, granted.has(HeartRateRecord::class), reuse).filter { inRange(it.date) }
        }

        val (steps, restingHr) = aggregates.await()
        val hrvRecords = hrv.await()
        val sleepRecords = sleep.await()

        RangeSnapshot(
            start = start,
            end = end,
            steps = steps,
            restingHeartRate = restingHr,
            hrv = hrvRecords.groupBy { dateOf(it.time) }
                .mapValues { (_, list) -> list.map { it.heartRateVariabilityMillis }.average() },
            sleepMinutes = sleepRecords.groupBy { dateOf(it.endTime) }
                .mapValues { (_, list) ->
                    list.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toDouble()
                },
            sessions = sessions.await(),
            recordDates = hrvRecords.associate { it.metadata.id to dateOf(it.time) } +
                sleepRecords.associate { it.metadata.id to dateOf(it.endTime) },
        )
    }

    /** 步数、静息心率按天聚合（Health Connect 会自动对多个来源去重）。两个查询并行。 */
    suspend fun fetchAggregates(
        start: LocalDate,
        end: LocalDate,
        granted: Set<String>,
    ): Pair<Map<LocalDate, Int>, Map<LocalDate, Double>> = coroutineScope {
        val range = TimeRangeFilter.between(start.atStartOfDay(), end.plusDays(1).atStartOfDay())
        val steps = async {
            if (!granted.has(StepsRecord::class)) emptyMap()
            else client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), range, Period.ofDays(1)),
            ).mapNotNull { g -> g.result[StepsRecord.COUNT_TOTAL]?.let { g.startTime.toLocalDate() to it.toInt() } }
                .toMap()
        }
        val restingHr = async {
            if (!granted.has(RestingHeartRateRecord::class)) emptyMap()
            else client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(setOf(RestingHeartRateRecord.BPM_AVG), range, Period.ofDays(1)),
            ).mapNotNull { g ->
                g.result[RestingHeartRateRecord.BPM_AVG]?.let { g.startTime.toLocalDate() to it.toDouble() }
            }.toMap()
        }
        steps.await() to restingHr.await()
    }

    private suspend fun readSessions(
        range: TimeRangeFilter,
        readHeartRate: Boolean,
        reuse: (String, Long, LocalDate) -> CachedSession?,
    ): List<CachedSession> = coroutineScope {
        // 同时最多 4 个心率查询，避免触发 Health Connect 的频率限制
        val limit = Semaphore(4)
        readAll(ExerciseSessionRecord::class, range)
            .filter { Duration.between(it.startTime, it.endTime).toMinutes() >= 5 }
            .map { record ->
                async {
                    val id = record.metadata.id
                    val modified = record.metadata.lastModifiedTime.toEpochMilli()
                    val date = dateOf(record.startTime)
                    reuse(id, modified, date) ?: CachedSession(
                        id = id,
                        date = date,
                        durationMinutes = Duration.between(record.startTime, record.endTime).toMinutes().toDouble(),
                        avgHeartRate = if (readHeartRate) limit.withPermit {
                            client.aggregate(
                                AggregateRequest(
                                    metrics = setOf(HeartRateRecord.BPM_AVG),
                                    timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime),
                                ),
                            )[HeartRateRecord.BPM_AVG]?.toDouble()
                        } else null,
                        type = record.title ?: "运动类型 ${record.exerciseType}",
                        lastModifiedEpochMs = modified,
                    )
                }
            }.awaitAll()
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val result = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(recordType = type, timeRangeFilter = range, pageToken = pageToken),
            )
            result += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return result
    }
}
