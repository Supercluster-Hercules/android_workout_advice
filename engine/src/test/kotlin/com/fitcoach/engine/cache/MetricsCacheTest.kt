package com.fitcoach.engine.cache

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsCacheTest {

    private val d1 = LocalDate.of(2026, 11, 1)
    private fun d(n: Int) = d1.plusDays((n - 1).toLong())

    private fun session(id: String, day: Int, hr: Double? = 140.0, modified: Long = 1L) =
        CachedSession(id, d(day), 45.0, hr, "跑步", modified)

    private fun snapshot(from: Int, to: Int, steps: Int = 8000, sessions: List<CachedSession> = emptyList()) =
        RangeSnapshot(
            start = d(from),
            end = d(to),
            steps = dates(d(from), d(to)).associateWith { steps },
            sleepMinutes = dates(d(from), d(to)).associateWith { 450.0 },
            sessions = sessions,
            recordDates = mapOf("sleep-${d(to)}" to setOf(d(to))),
        )

    @Test
    fun `applying the same snapshot twice does not duplicate anything`() {
        val cache = MetricsCache()
        val s = snapshot(1, 5, sessions = listOf(session("a", 2), session("b", 4)))
        cache.applySnapshot(s)
        cache.applySnapshot(s)
        assertEquals(5, cache.days.size)
        assertEquals(2, cache.sessions.size)
        val metrics = cache.toDailyMetrics(d(1), d(5))
        assertEquals(5, metrics.size)
        assertEquals(2, metrics.sumOf { it.sessions.size })
        assertEquals(8000, metrics[0].steps)
    }

    @Test
    fun `snapshot replaces only its own range`() {
        val cache = MetricsCache()
        cache.applySnapshot(snapshot(1, 10, steps = 5000, sessions = listOf(session("a", 2), session("b", 8))))
        // 第 8–10 天重新读到：步数变了，运动 b 被用户删掉，新增了运动 c
        cache.applySnapshot(snapshot(8, 10, steps = 9000, sessions = listOf(session("c", 9))))

        assertEquals(5000, cache.days[d(7)]?.steps)
        assertEquals(9000, cache.days[d(8)]?.steps)
        assertEquals(setOf("a", "c"), cache.sessions.keys)
    }

    @Test
    fun `edited session moved to another day is not counted twice`() {
        val cache = MetricsCache()
        cache.applySnapshot(snapshot(1, 5, sessions = listOf(session("a", 2))))
        cache.applySnapshot(snapshot(3, 3, sessions = listOf(session("a", 3, modified = 2L))))
        assertEquals(1, cache.sessions.size)
        assertEquals(d(3), cache.sessions["a"]?.date)
    }

    @Test
    fun `days without data are removed on refresh`() {
        val cache = MetricsCache()
        cache.applySnapshot(snapshot(1, 3))
        cache.applySnapshot(RangeSnapshot(d(2), d(2)))
        assertNull(cache.days[d(2)])
        assertNotNull(cache.days[d(1)])
    }

    @Test
    fun `plan collects dirty dates from upserts and known deletions`() {
        val cache = MetricsCache()
        cache.applySnapshot(snapshot(1, 10, sessions = listOf(session("a", 3))))
        val plan = cache.plan(
            listOf(
                Change.Upsert(RecordKind.STEPS, "s1", setOf(d(9), d(10))),
                Change.Upsert(RecordKind.STEPS, "old", setOf(d(1).minusDays(40))), // 窗口外，忽略
                Change.Delete("a"),            // 已知运动记录 → 第 3 天
                Change.Delete("sleep-${d(10)}"), // 已知睡眠记录 → 第 10 天
            ),
            windowStart = d(1),
            today = d(10),
        )
        assertEquals(setOf(d(3), d(9), d(10)), plan.dirtyDates)
        assertFalse(plan.refreshAllAggregates)
    }

    @Test
    fun `unknown deletion triggers aggregate refresh`() {
        val plan = MetricsCache().plan(listOf(Change.Delete("unknown")), d(1), d(10))
        assertTrue(plan.refreshAllAggregates)
        assertTrue(plan.dirtyDates.isEmpty())
    }

    @Test
    fun `aggregates refresh keeps HRV and sleep`() {
        val cache = MetricsCache()
        cache.applySnapshot(snapshot(1, 3))
        cache.applyAggregates(d(1), d(3), steps = mapOf(d(1) to 1234), restingHeartRate = emptyMap())
        assertEquals(1234, cache.days[d(1)]?.steps)
        assertNull(cache.days[d(2)]?.steps)
        assertEquals(450.0, cache.days[d(2)]?.sleepMinutes)
    }

    @Test
    fun `heart rate is reused only for unchanged older sessions`() {
        val cache = MetricsCache()
        cache.applySnapshot(snapshot(1, 10, sessions = listOf(session("old", 3), session("recent", 10))))
        val today = d(10)
        assertNotNull(cache.reusableSession("old", 1L, d(3), today))
        assertNull(cache.reusableSession("old", 2L, d(3), today), "记录被修改过")
        assertNull(cache.reusableSession("recent", 1L, d(10), today), "最近两天的心率可能还在同步")
        assertNull(cache.reusableSession("new", 1L, d(5), today))
    }

    @Test
    fun `prune drops old data`() {
        val cache = MetricsCache()
        cache.applySnapshot(snapshot(1, 10, sessions = listOf(session("a", 2), session("b", 8))))
        cache.prune(d(5))
        assertEquals(6, cache.days.size)
        assertEquals(setOf("b"), cache.sessions.keys)
    }

    @Test
    fun `multi-day record deletion marks every day it covers`() {
        val cache = MetricsCache()
        cache.applySnapshot(
            snapshot(1, 10).copy(
                menstruationDates = setOf(d(4), d(5), d(6)),
                recordDates = mapOf("period" to setOf(d(4), d(5), d(6))),
            ),
        )
        assertTrue(cache.days[d(5)]!!.menstruation)
        val plan = cache.plan(listOf(Change.Delete("period")), d(1), d(10))
        assertEquals(setOf(d(4), d(5), d(6)), plan.dirtyDates)
        // 只刷新第 6 天时，第 4、5 天的记录关联仍然保留
        cache.applySnapshot(RangeSnapshot(d(6), d(6)))
        assertEquals(setOf(d(4), d(5)), cache.recordDates["period"])
    }

    @Test
    fun `contiguous ranges merge adjacent dates`() {
        val ranges = contiguousRanges(setOf(d(7), d(1), d(2), d(3), d(10), d(9)))
        assertEquals(listOf(d(1) to d(3), d(7) to d(7), d(9) to d(10)), ranges)
        assertTrue(contiguousRanges(emptySet()).isEmpty())
    }
}
