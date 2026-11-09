package com.fitcoach.app.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.fitcoach.engine.cache.CachedDay
import com.fitcoach.engine.cache.CachedSession
import com.fitcoach.engine.cache.MetricsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * 把 [MetricsCache] 存成 app 私有目录下的一个 JSON 文件。
 * 数据量很小（几个月也就几十 KB），用 JSON 足够，不需要引入数据库。
 * 使用 AtomicFile 写入：写到一半 app 被杀掉也不会损坏旧文件。
 */
class CacheStore(context: Context) : CacheStorage {

    private val file = AtomicFile(File(context.filesDir, "health_cache.json"))

    override suspend fun load(): MetricsCache = withContext(Dispatchers.IO) {
        try {
            if (!file.baseFile.exists()) return@withContext MetricsCache()
            val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
            if (json.optInt("version") != VERSION) return@withContext MetricsCache()
            fromJson(json)
        } catch (e: Exception) {
            // 文件损坏就当作没有缓存，下次会完整导入
            Log.w(TAG, "缓存读取失败，将重新导入", e)
            MetricsCache()
        }
    }

    override suspend fun save(cache: MetricsCache): Unit = withContext(Dispatchers.IO) {
        val bytes = toJson(cache).toString().toByteArray(Charsets.UTF_8)
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    override suspend fun delete(): Unit = withContext(Dispatchers.IO) { file.delete() }

    private fun toJson(c: MetricsCache) = JSONObject().apply {
        put("version", VERSION)
        put("token", c.changesToken ?: JSONObject.NULL)
        put("tokenPermissions", JSONArray(c.tokenPermissions.toList()))
        put("lastSync", c.lastSyncEpochMs)
        put("days", JSONObject().apply {
            c.days.forEach { (date, d) ->
                put(date.toString(), JSONObject().apply {
                    d.steps?.let { put("steps", it) }
                    d.restingHeartRate?.let { put("rhr", it) }
                    d.hrvRmssd?.let { put("hrv", it) }
                    d.sleepMinutes?.let { put("sleep", it) }
                    d.sleepEfficiency?.let { put("sleepEff", it) }
                    d.bodyTemperatureC?.let { put("temp", it) }
                    d.skinTempDeltaC?.let { put("skinDelta", it) }
                    if (d.menstruation) put("period", true)
                })
            }
        })
        put("sessions", JSONArray().apply {
            c.sessions.values.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("date", s.date.toString())
                    put("minutes", s.durationMinutes)
                    s.avgHeartRate?.let { put("hr", it) }
                    s.type?.let { put("type", it) }
                    put("modified", s.lastModifiedEpochMs)
                })
            }
        })
        put("recordDates", JSONObject().apply {
            c.recordDates.forEach { (id, dates) -> put(id, JSONArray(dates.map { it.toString() })) }
        })
    }

    private fun fromJson(json: JSONObject): MetricsCache {
        val days = mutableMapOf<LocalDate, CachedDay>()
        json.getJSONObject("days").let { obj ->
            obj.keys().forEach { key ->
                val d = obj.getJSONObject(key)
                days[LocalDate.parse(key)] = CachedDay(
                    steps = d.intOrNull("steps"),
                    restingHeartRate = d.doubleOrNull("rhr"),
                    hrvRmssd = d.doubleOrNull("hrv"),
                    sleepMinutes = d.doubleOrNull("sleep"),
                    sleepEfficiency = d.doubleOrNull("sleepEff"),
                    bodyTemperatureC = d.doubleOrNull("temp"),
                    skinTempDeltaC = d.doubleOrNull("skinDelta"),
                    menstruation = d.optBoolean("period", false),
                )
            }
        }
        val sessions = mutableMapOf<String, CachedSession>()
        json.getJSONArray("sessions").let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val session = CachedSession(
                    id = s.getString("id"),
                    date = LocalDate.parse(s.getString("date")),
                    durationMinutes = s.getDouble("minutes"),
                    avgHeartRate = s.doubleOrNull("hr"),
                    type = s.optString("type").ifEmpty { null },
                    lastModifiedEpochMs = s.getLong("modified"),
                )
                sessions[session.id] = session
            }
        }
        val recordDates = mutableMapOf<String, Set<LocalDate>>()
        json.getJSONObject("recordDates").let { obj ->
            obj.keys().forEach { id ->
                val arr = obj.getJSONArray(id)
                recordDates[id] = (0 until arr.length()).map { LocalDate.parse(arr.getString(it)) }.toSet()
            }
        }
        val permissions = json.getJSONArray("tokenPermissions").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }
        return MetricsCache(
            days = days,
            sessions = sessions,
            recordDates = recordDates,
            changesToken = if (json.isNull("token")) null else json.getString("token"),
            tokenPermissions = permissions,
            lastSyncEpochMs = json.optLong("lastSync"),
        )
    }

    private fun JSONObject.intOrNull(key: String) = if (has(key)) getInt(key) else null
    private fun JSONObject.doubleOrNull(key: String) = if (has(key)) getDouble(key) else null

    private companion object {
        /** 结构变化时加 1，旧版本缓存会被丢弃并重新导入。 */
        const val VERSION = 2
        const val TAG = "CacheStore"
    }
}
