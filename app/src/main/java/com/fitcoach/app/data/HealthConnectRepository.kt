package com.fitcoach.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
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
@OptIn(ExperimentalFeatureAvailabilityApi::class)
class HealthConnectRepository(private val context: Context) : HealthDataSource {

    enum class Availability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED }

    /** 需要跟踪变更的记录类型。心率不跟踪：数据量太大，且只用于计算运动平均心率。 */
    private val trackedTypes: Map<KClass<out Record>, RecordKind> = mapOf(
        StepsRecord::class to RecordKind.STEPS,
        ExerciseSessionRecord::class to RecordKind.EXERCISE,
        RestingHeartRateRecord::class to RecordKind.RESTING_HEART_RATE,
        HeartRateVariabilityRmssdRecord::class to RecordKind.HRV,
        SleepSessionRecord::class to RecordKind.SLEEP,
        BodyTemperatureRecord::class to RecordKind.BODY_TEMPERATURE,
        SkinTemperatureRecord::class to RecordKind.SKIN_TEMPERATURE,
        MenstruationPeriodRecord::class to RecordKind.MENSTRUATION,
    )

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

    private fun featureAvailable(feature: Int): Boolean = runCatching {
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    /** 后台读取（每天早上自动同步、推送建议）需要额外的权限，部分旧版 Health Connect 不支持。 */
    fun backgroundReadSupported(): Boolean =
        featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)

    /** 要向用户申请的全部权限（按设备支持的功能动态决定）。 */
    fun requestablePermissions(): Set<String> = buildSet {
        trackedTypes.keys
            .filter { it != SkinTemperatureRecord::class || featureAvailable(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) }
            .forEach { add(HealthPermission.getReadPermission(it)) }
        add(HealthPermission.getReadPermission(HeartRateRecord::class))
        if (backgroundReadSupported()) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }

    override suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    override fun trackedPermissions(granted: Set<String>): Set<String> =
        trackedTypes.keys.map { HealthPermission.getReadPermission(it) }.filter { it in granted }.toSet()

    private fun Set<String>.has(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in this

    // ---------------- 变更记录（增量同步） ----------------

    override suspend fun getChangesToken(granted: Set<String>): String =
        client.getChangesToken(ChangesTokenRequest(recordTypes = trackedTypes.keys.filter { granted.has(it) }.toSet()))

    override suspend fun getChanges(token: String): ChangesResult {
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
            is BodyTemperatureRecord -> setOf(dateOf(record.time))
            is SkinTemperatureRecord -> setOf(dateOf(record.endTime)) // 夜间测量，归到起床那天
            is MenstruationPeriodRecord -> datesTouched(record.startTime, record.endTime)
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

    override suspend fun fetchRange(
        start: LocalDate,
        end: LocalDate,
        granted: Set<String>,
        reuse: (id: String, lastModifiedEpochMs: Long, date: LocalDate) -> CachedSession?,
    ): RangeSnapshot = coroutineScope {
        val startInstant = start.atStartOfDay(zone).toInstant()
        val endInstant = end.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(startInstant, endInstant)
        val inRange = { d: LocalDate -> !d.isBefore(start) && !d.isAfter(end) }

        suspend fun <T : Record> readIfGranted(type: KClass<T>, filter: TimeRangeFilter = range): List<T> =
            if (granted.has(type)) readAll(type, filter) else emptyList()

        // 各类数据并行读取
        val aggregates = async { fetchAggregates(start, end, granted) }
        val hrv = async { readIfGranted(HeartRateVariabilityRmssdRecord::class) }
        // 睡眠 / 皮肤温度按起床日期归属，前一晚开始的记录在区间之前，所以往前多读 18 小时
        val overnight = TimeRangeFilter.between(startInstant.minus(Duration.ofHours(18)), endInstant)
        val sleep = async { readIfGranted(SleepSessionRecord::class, overnight).filter { inRange(dateOf(it.endTime)) } }
        val skin = async { readIfGranted(SkinTemperatureRecord::class, overnight).filter { inRange(dateOf(it.endTime)) } }
        val temperature = async { readIfGranted(BodyTemperatureRecord::class) }
        // 经期可能在区间之前就开始了，往前多读 14 天
        val periods = async {
            readIfGranted(
                MenstruationPeriodRecord::class,
                TimeRangeFilter.between(startInstant.minus(Duration.ofDays(14)), endInstant),
            )
        }
        val sessions = async {
            if (!granted.has(ExerciseSessionRecord::class)) emptyList()
            else readSessions(range, granted.has(HeartRateRecord::class), reuse).filter { inRange(it.date) }
        }

        val (steps, restingHr) = aggregates.await()
        val hrvRecords = hrv.await()
        val sleepRecords = sleep.await()
        val skinRecords = skin.await()
        val temperatureRecords = temperature.await()
        val periodRecords = periods.await()

        val sleepByDay = sleepRecords.groupBy { dateOf(it.endTime) }.mapValues { (_, list) -> summarizeSleep(list) }
        val periodDates = periodRecords.associate { it.metadata.id to datesTouched(it.startTime, it.endTime) }

        RangeSnapshot(
            start = start,
            end = end,
            steps = steps,
            restingHeartRate = restingHr,
            hrv = hrvRecords.groupBy { dateOf(it.time) }
                .mapValues { (_, list) -> list.map { it.heartRateVariabilityMillis }.average() },
            sleepMinutes = sleepByDay.mapValues { it.value.first },
            sleepEfficiency = sleepByDay.mapNotNull { (d, v) -> v.second?.let { d to it } }.toMap(),
            // 一天多次测量时取最高值（用于发烧判断）
            bodyTemperatureC = temperatureRecords.groupBy { dateOf(it.time) }
                .mapValues { (_, list) -> list.maxOf { it.temperature.inCelsius } },
            skinTempDeltaC = skinRecords.groupBy { dateOf(it.endTime) }
                .mapNotNull { (d, list) ->
                    val deltas = list.flatMap { r -> r.deltas.map { it.delta.inCelsius } }
                    if (deltas.isEmpty()) null else d to deltas.average()
                }.toMap(),
            menstruationDates = periodDates.values.flatten().filter(inRange).toSet(),
            sessions = sessions.await(),
            recordDates = hrvRecords.associate { it.metadata.id to setOf(dateOf(it.time)) } +
                sleepRecords.associate { it.metadata.id to setOf(dateOf(it.endTime)) } +
                skinRecords.associate { it.metadata.id to setOf(dateOf(it.endTime)) } +
                temperatureRecords.associate { it.metadata.id to setOf(dateOf(it.time)) } +
                periodDates,
        )
    }

    /**
     * 返回 (实际睡着的分钟数, 睡眠效率)。有睡眠分期时只统计浅睡 / 深睡 / REM / 睡着阶段，
     * 效率 = 睡着时间 / 卧床时间；没有分期时用整段时长，效率为 null。
     */
    private fun summarizeSleep(records: List<SleepSessionRecord>): Pair<Double, Double?> {
        val asleepStages = setOf(
            SleepSessionRecord.STAGE_TYPE_SLEEPING,
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_REM,
        )
        var asleep = 0.0
        var inBedWithStages = 0.0
        var asleepWithStages = 0.0
        for (r in records) {
            val total = Duration.between(r.startTime, r.endTime).toMinutes().toDouble()
            if (r.stages.isEmpty()) {
                asleep += total
            } else {
                val minutes = r.stages.filter { it.stage in asleepStages }
                    .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toDouble()
                asleep += minutes
                asleepWithStages += minutes
                inBedWithStages += total
            }
        }
        val efficiency = if (inBedWithStages > 0) (asleepWithStages / inBedWithStages).coerceIn(0.0, 1.0) else null
        return asleep to efficiency
    }

    /** 步数、静息心率按天聚合（Health Connect 会自动对多个来源去重）。两个查询并行。 */
    override suspend fun fetchAggregates(
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
