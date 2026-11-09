package com.fitcoach.app.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.fitcoach.engine.ExerciseEntry
import com.fitcoach.engine.MuscleGroup
import com.fitcoach.engine.SorenessReport
import com.fitcoach.engine.StrengthSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** 力量训练记录和酸痛自评，一起存成 app 私有目录下的一个 JSON 文件。 */
data class StrengthData(
    val sessions: List<StrengthSession> = emptyList(),
    val soreness: List<SorenessReport> = emptyList(),
)

/**
 * 与 [CacheStore] 同样的做法：数据量很小，用 JSON 文件 + AtomicFile（写到一半被杀也不会损坏旧文件）。
 * 读写都很快，直接在调用线程完成；内存里保留一份，避免重复解析。
 */
class StrengthStore(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "strength_log.json"))
    private val lock = Any()
    private var cached: StrengthData? = null

    fun load(): StrengthData = synchronized(lock) {
        cached ?: read().also { cached = it }
    }

    fun addSession(session: StrengthSession) = update { it.copy(sessions = it.sessions.filter { s -> s.id != session.id } + session) }

    fun deleteSession(id: String) = update { it.copy(sessions = it.sessions.filter { s -> s.id != id }) }

    /** 记录某个部位的酸痛自评；同一天同一部位只保留最新一次。[score] 为 null 表示清除当天的自评。 */
    fun setSoreness(muscle: MuscleGroup, score: Int?, time: LocalDateTime = LocalDateTime.now()) = update { data ->
        val others = data.soreness.filterNot { it.muscle == muscle && it.time.toLocalDate() == time.toLocalDate() }
        data.copy(soreness = others + listOfNotNull(score?.let { SorenessReport(time, muscle, it.coerceIn(0, 10)) }))
    }

    /** 今天各部位的酸痛自评。 */
    fun todaySoreness(today: LocalDate = LocalDate.now()): Map<MuscleGroup, Int> =
        load().soreness.filter { it.time.toLocalDate() == today }.associate { it.muscle to it.score }

    private fun update(transform: (StrengthData) -> StrengthData) = synchronized(lock) {
        // 只保留 180 天，和健康数据缓存一致
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS)
        val updated = transform(load()).let { d ->
            d.copy(
                sessions = d.sessions.filter { it.date >= cutoff }.sortedBy { it.start },
                soreness = d.soreness.filter { it.time.toLocalDate() >= cutoff }.sortedBy { it.time },
            )
        }
        write(updated)
        cached = updated
    }

    private fun read(): StrengthData = try {
        if (!file.baseFile.exists()) StrengthData() else fromJson(JSONObject(file.readFully().toString(Charsets.UTF_8)))
    } catch (e: Exception) {
        Log.w(TAG, "力量训练记录读取失败", e)
        StrengthData()
    }

    private fun write(data: StrengthData) {
        val out = file.startWrite()
        try {
            out.write(toJson(data).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun toJson(d: StrengthData) = JSONObject().apply {
        put("version", VERSION)
        put("sessions", JSONArray().apply {
            d.sessions.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("start", s.start.toString())
                    put("minutes", s.durationMinutes)
                    put("rpe", s.sessionRpe)
                    put("exercises", JSONArray().apply {
                        s.exercises.forEach { e ->
                            put(JSONObject().apply {
                                put("id", e.exerciseId)
                                put("sets", e.sets)
                                put("reps", e.reps)
                                e.weightKg?.let { put("kg", it) }
                            })
                        }
                    })
                })
            }
        })
        put("soreness", JSONArray().apply {
            d.soreness.forEach { r ->
                put(JSONObject().apply {
                    put("time", r.time.toString())
                    put("muscle", r.muscle.name)
                    put("score", r.score)
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): StrengthData {
        val sessions = json.optJSONArray("sessions").objects().map { s ->
            StrengthSession(
                id = s.getString("id"),
                start = LocalDateTime.parse(s.getString("start")),
                durationMinutes = s.getDouble("minutes"),
                sessionRpe = s.getInt("rpe"),
                exercises = s.optJSONArray("exercises").objects().map { e ->
                    ExerciseEntry(
                        exerciseId = e.getString("id"),
                        sets = e.getInt("sets"),
                        reps = e.getInt("reps"),
                        weightKg = if (e.has("kg")) e.getDouble("kg") else null,
                    )
                },
            )
        }
        val soreness = json.optJSONArray("soreness").objects().mapNotNull { r ->
            val muscle = runCatching { MuscleGroup.valueOf(r.getString("muscle")) }.getOrNull() ?: return@mapNotNull null
            SorenessReport(LocalDateTime.parse(r.getString("time")), muscle, r.getInt("score"))
        }
        return StrengthData(sessions, soreness)
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private companion object {
        const val VERSION = 1
        const val RETENTION_DAYS = 180L
        const val TAG = "StrengthStore"
    }
}
