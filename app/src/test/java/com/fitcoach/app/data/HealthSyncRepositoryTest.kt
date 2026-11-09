package com.fitcoach.app.data

import com.fitcoach.engine.cache.CachedSession
import com.fitcoach.engine.cache.Change
import com.fitcoach.engine.cache.MetricsCache
import com.fitcoach.engine.cache.RangeSnapshot
import com.fitcoach.engine.cache.RecordKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HealthSyncRepositoryTest {

    private val today = LocalDate.of(2026, 11, 10)

    /** 假的 Health Connect：按日期返回固定步数，记录每次读取的区间。 */
    private class FakeSource(val today: LocalDate) : HealthDataSource {
        var granted = setOf("steps", "exercise")
        var steps = mutableMapOf<LocalDate, Int>()
        var sessions = mutableListOf<CachedSession>()
        var pendingChanges: ChangesResult = ChangesResult.Ok(emptyList(), "token-1")
        val fetchedRanges = mutableListOf<Pair<LocalDate, LocalDate>>()
        var tokensIssued = 0
        var heartRateQueries = 0

        override suspend fun grantedPermissions() = granted
        override fun trackedPermissions(granted: Set<String>) = granted
        override suspend fun getChangesToken(granted: Set<String>) = "token-${++tokensIssued}"
        override suspend fun getChanges(token: String) = pendingChanges.also {
            pendingChanges = ChangesResult.Ok(emptyList(), "token-next")
        }

        override suspend fun fetchRange(
            start: LocalDate,
            end: LocalDate,
            granted: Set<String>,
            reuse: (String, Long, LocalDate) -> CachedSession?,
        ): RangeSnapshot {
            fetchedRanges += start to end
            val inRange = sessions.filter { it.date in start..end }.map { s ->
                reuse(s.id, s.lastModifiedEpochMs, s.date) ?: s.also { heartRateQueries++ }
            }
            return RangeSnapshot(
                start = start,
                end = end,
                steps = steps.filterKeys { it in start..end },
                sessions = inRange,
            )
        }

        override suspend fun fetchAggregates(start: LocalDate, end: LocalDate, granted: Set<String>) =
            steps.filterKeys { it in start..end } to emptyMap<LocalDate, Double>()
    }

    private class MemoryStorage : CacheStorage {
        var saved: MetricsCache? = null
        var saves = 0
        override suspend fun load() = saved ?: MetricsCache()
        override suspend fun save(cache: MetricsCache) {
            saved = cache
            saves++
        }
        override suspend fun delete() {
            saved = null
        }
    }

    private fun setup(): Triple<FakeSource, MemoryStorage, HealthSyncRepository> {
        val source = FakeSource(today)
        (0L..30L).forEach { source.steps[today.minusDays(it)] = 8000 }
        source.sessions += CachedSession("run-1", today.minusDays(10), 40.0, 150.0, "跑步", 1L)
        source.sessions += CachedSession("run-2", today.minusDays(3), 30.0, 155.0, "跑步", 1L)
        val storage = MemoryStorage()
        return Triple(source, storage, HealthSyncRepository(source, storage, today = { today }))
    }

    @Test
    fun `first sync imports everything and saves a token`() = runTest {
        val (source, storage, repo) = setup()
        assertNull(repo.cachedHistory(today.minusDays(7), today))

        val result = repo.sync(today.minusDays(28), today)

        assertEquals(HealthSyncRepository.Mode.FULL, result.mode)
        assertEquals(listOf(today.minusDays(30) to today), source.fetchedRanges)
        assertEquals("token-1", storage.saved?.changesToken)
        assertEquals(29, result.history.size)
        assertEquals(2, result.history.sumOf { it.sessions.size })
        assertNotNull(repo.cachedHistory(today.minusDays(7), today))
    }

    @Test
    fun `second sync without changes only refreshes the last two days`() = runTest {
        val (source, _, repo) = setup()
        repo.sync(today.minusDays(28), today)
        source.fetchedRanges.clear()

        val result = repo.sync(today.minusDays(28), today)

        assertEquals(HealthSyncRepository.Mode.UP_TO_DATE, result.mode)
        assertEquals(listOf(today.minusDays(1) to today), source.fetchedRanges)
    }

    @Test
    fun `changes only refetch affected days and never duplicate`() = runTest {
        val (source, storage, repo) = setup()
        repo.sync(today.minusDays(28), today)
        source.fetchedRanges.clear()

        // 第 10 天前的跑步被修改（时长变了），第 5 天前新增了步数
        source.sessions.removeAll { it.id == "run-1" }
        source.sessions += CachedSession("run-1", today.minusDays(10), 60.0, 150.0, "跑步", 2L)
        source.steps[today.minusDays(5)] = 12000
        source.pendingChanges = ChangesResult.Ok(
            listOf(
                Change.Upsert(RecordKind.EXERCISE, "run-1", setOf(today.minusDays(10))),
                Change.Upsert(RecordKind.STEPS, "s", setOf(today.minusDays(5))),
            ),
            "token-2",
        )

        val result = repo.sync(today.minusDays(28), today)

        assertEquals(HealthSyncRepository.Mode.INCREMENTAL, result.mode)
        assertEquals(2, result.changedDays)
        assertEquals(
            listOf(today.minusDays(10) to today.minusDays(10), today.minusDays(5) to today.minusDays(5), today.minusDays(1) to today),
            source.fetchedRanges,
        )
        val sessions = result.history.flatMap { it.sessions }
        assertEquals(2, sessions.size)
        assertEquals(60.0, result.history.single { it.date == today.minusDays(10) }.sessions.single().durationMinutes, 0.0)
        assertEquals(12000, result.history.single { it.date == today.minusDays(5) }.steps)
        assertEquals("token-2", storage.saved?.changesToken)
    }

    @Test
    fun `deleted session disappears`() = runTest {
        val (source, _, repo) = setup()
        repo.sync(today.minusDays(28), today)
        source.sessions.removeAll { it.id == "run-1" }
        source.pendingChanges = ChangesResult.Ok(listOf(Change.Delete("run-1")), "token-2")

        val result = repo.sync(today.minusDays(28), today)

        assertEquals(1, result.history.sumOf { it.sessions.size })
    }

    @Test
    fun `expired token falls back to a full import`() = runTest {
        val (source, _, repo) = setup()
        repo.sync(today.minusDays(28), today)
        source.pendingChanges = ChangesResult.Expired

        val result = repo.sync(today.minusDays(28), today)

        assertEquals(HealthSyncRepository.Mode.FULL, result.mode)
        assertEquals(2, source.tokensIssued)
    }

    @Test
    fun `permission change falls back to a full import`() = runTest {
        val (source, _, repo) = setup()
        repo.sync(today.minusDays(28), today)
        source.granted = source.granted + "sleep"

        assertEquals(HealthSyncRepository.Mode.FULL, repo.sync(today.minusDays(28), today).mode)
    }

    @Test
    fun `force full re-import reuses heart rate of unchanged old sessions`() = runTest {
        val (source, _, repo) = setup()
        repo.sync(today.minusDays(28), today)
        assertEquals(2, source.heartRateQueries)

        repo.sync(today.minusDays(28), today, forceFull = true)

        // 两条记录都没修改，且都不在最近两天内 → 不再重复查询心率
        assertEquals(2, source.heartRateQueries)
    }

    @Test
    fun `full re-import keeps history older than the sync window`() = runTest {
        val (source, storage, repo) = setup()
        repo.sync(today.minusDays(28), today)
        // 模拟 60 天前的历史（来自更早的同步）
        val old = today.minusDays(60)
        storage.saved!!.applySnapshot(RangeSnapshot(old, old, steps = mapOf(old to 5000)))

        repo.sync(today.minusDays(90), today, forceFull = true)

        assertEquals(5000, repo.cachedHistory(old, old)!!.single().steps)
        assertTrue(source.fetchedRanges.all { it.first >= today.minusDays(30) })
    }
}
