package com.fitcoach.engine

import java.time.LocalDate
import java.time.LocalDateTime

enum class BodyRegion { UPPER, LOWER, CORE }

/**
 * 肌群。[recoveryTauHours] 是疲劳指数衰减的时间常数：大肌群约 30 小时，小肌群约 20 小时
 * （即一次较大剂量训练后，大肌群约 48–72 小时、小肌群约 24–48 小时基本恢复）。
 * [major] 的肌群用来判断"今天该练上肢还是下肢"。
 */
enum class MuscleGroup(val region: BodyRegion, val recoveryTauHours: Double, val major: Boolean) {
    CHEST(BodyRegion.UPPER, 30.0, true),
    BACK(BodyRegion.UPPER, 30.0, true),
    SHOULDERS(BodyRegion.UPPER, 24.0, true),
    BICEPS(BodyRegion.UPPER, 20.0, false),
    TRICEPS(BodyRegion.UPPER, 20.0, false),
    CORE(BodyRegion.CORE, 18.0, false),
    GLUTES(BodyRegion.LOWER, 30.0, true),
    QUADS(BodyRegion.LOWER, 32.0, true),
    HAMSTRINGS(BodyRegion.LOWER, 32.0, true),
    CALVES(BodyRegion.LOWER, 20.0, false),
}

/** 动作及其肌群权重：主要发力 1.0、协同 0.5、辅助 0.25。 */
data class Exercise(val id: String, val muscles: Map<MuscleGroup, Double>)

object ExerciseCatalog {
    private const val P = 1.0
    private const val S = 0.5
    private const val A = 0.25

    val all: List<Exercise> = listOf(
        Exercise("squat", mapOf(MuscleGroup.QUADS to P, MuscleGroup.GLUTES to P, MuscleGroup.CORE to S, MuscleGroup.HAMSTRINGS to A)),
        Exercise("deadlift", mapOf(MuscleGroup.HAMSTRINGS to P, MuscleGroup.GLUTES to P, MuscleGroup.BACK to P, MuscleGroup.QUADS to S, MuscleGroup.CORE to S)),
        Exercise("romanian_deadlift", mapOf(MuscleGroup.HAMSTRINGS to P, MuscleGroup.GLUTES to P, MuscleGroup.BACK to S)),
        Exercise("leg_press", mapOf(MuscleGroup.QUADS to P, MuscleGroup.GLUTES to S)),
        Exercise("lunge", mapOf(MuscleGroup.QUADS to P, MuscleGroup.GLUTES to P, MuscleGroup.HAMSTRINGS to A)),
        Exercise("hip_thrust", mapOf(MuscleGroup.GLUTES to P, MuscleGroup.HAMSTRINGS to S)),
        Exercise("leg_curl", mapOf(MuscleGroup.HAMSTRINGS to P)),
        Exercise("leg_extension", mapOf(MuscleGroup.QUADS to P)),
        Exercise("calf_raise", mapOf(MuscleGroup.CALVES to P)),
        Exercise("bench_press", mapOf(MuscleGroup.CHEST to P, MuscleGroup.TRICEPS to S, MuscleGroup.SHOULDERS to S)),
        Exercise("push_up", mapOf(MuscleGroup.CHEST to P, MuscleGroup.TRICEPS to S, MuscleGroup.SHOULDERS to S, MuscleGroup.CORE to A)),
        Exercise("dip", mapOf(MuscleGroup.TRICEPS to P, MuscleGroup.CHEST to S, MuscleGroup.SHOULDERS to S)),
        Exercise("overhead_press", mapOf(MuscleGroup.SHOULDERS to P, MuscleGroup.TRICEPS to S, MuscleGroup.CORE to A)),
        Exercise("lateral_raise", mapOf(MuscleGroup.SHOULDERS to P)),
        Exercise("pull_up", mapOf(MuscleGroup.BACK to P, MuscleGroup.BICEPS to S)),
        Exercise("lat_pulldown", mapOf(MuscleGroup.BACK to P, MuscleGroup.BICEPS to S)),
        Exercise("row", mapOf(MuscleGroup.BACK to P, MuscleGroup.BICEPS to S, MuscleGroup.SHOULDERS to A)),
        Exercise("biceps_curl", mapOf(MuscleGroup.BICEPS to P)),
        Exercise("triceps_extension", mapOf(MuscleGroup.TRICEPS to P)),
        Exercise("plank", mapOf(MuscleGroup.CORE to P)),
        Exercise("crunch", mapOf(MuscleGroup.CORE to P)),
    )

    private val index = all.associateBy { it.id }

    fun byId(id: String): Exercise? = index[id]
}

/** 一个动作的记录：[sets] 组 × [reps] 次，[weightKg] 为空表示自重。 */
data class ExerciseEntry(
    val exerciseId: String,
    val sets: Int,
    val reps: Int,
    val weightKg: Double? = null,
)

/** 一次力量训练。[sessionRpe] 是练完后对整次训练的主观疲劳度（CR-10）。 */
data class StrengthSession(
    val id: String,
    val start: LocalDateTime,
    val durationMinutes: Double,
    val sessionRpe: Int,
    val exercises: List<ExerciseEntry>,
) {
    val date: LocalDate get() = start.toLocalDate()
    val end: LocalDateTime get() = start.plusMinutes(durationMinutes.toLong())

    /** 这次训练练到了哪些部位（权重 ≥ 0.5 的上肢 / 下肢肌群）。 */
    fun regions(): Set<BodyRegion> = exercises.flatMap { e ->
        ExerciseCatalog.byId(e.exerciseId)?.muscles.orEmpty()
            .filter { (m, w) -> w >= 0.5 && m.region != BodyRegion.CORE }.keys.map { it.region }
    }.toSet()
}

/**
 * 力量训练负荷：
 * - Foster session-RPE 负荷 = RPE × 分钟（任意单位 AU）；
 * - 为了和有氧训练放进同一个 ACWR 模型，用 [TrainingLoad.hrrFromRpe] 把 RPE 换算成心率储备，
 *   再按 TRIMP 公式算出等效负荷：分钟 × TRIMP/分钟(HRR)。
 */
object StrengthLoad {
    /** 力量训练记录转换成运动记录时使用的类型名。 */
    const val SESSION_TYPE = "力量训练"
    /** 有效组数的常用区间（每肌群每周）。 */
    const val EFFECTIVE_SETS_LOW = 10.0
    const val EFFECTIVE_SETS_HIGH = 20.0
    /** 整次 RPE 不高于这个值视为热身 / 技术练习，不计入有效组数。 */
    const val MIN_HARD_RPE = 3

    private val strengthKeywords = listOf("力量", "举重", "器械", "自重", "strength", "weight", "calisthenics")

    fun fosterLoad(s: StrengthSession): Double = s.sessionRpe.coerceIn(0, 10) * s.durationMinutes

    fun trimp(s: StrengthSession, sex: Sex? = null): Double =
        s.durationMinutes * TrainingLoad.trimpPerMinute(TrainingLoad.hrrFromRpe(s.sessionRpe), sex)

    fun toExerciseSession(s: StrengthSession) =
        ExerciseSession(durationMinutes = s.durationMinutes, avgHeartRate = null, type = SESSION_TYPE, rpe = s.sessionRpe)

    fun isStrengthType(type: String?): Boolean {
        val t = type?.lowercase() ?: return false
        return strengthKeywords.any { it in t }
    }

    /** 相对强度，由整次 RPE 换算：RPE 8 = 1.0，RPE 5 ≈ 0.78，RPE 10 = 1.15。 */
    fun relativeIntensity(rpe: Int): Double = 0.4 + 0.075 * rpe.coerceIn(0, 10)

    /**
     * 把力量训练记录并入每日数据。某天有记录时，Health Connect 中同一天的力量类运动会被替换掉，
     * 避免同一次训练算两遍。没有该日期条目时补一条（只补历史开始之后的日期）。
     */
    fun mergeInto(history: List<DailyMetrics>, sessions: List<StrengthSession>): List<DailyMetrics> {
        if (sessions.isEmpty()) return history
        val logged = sessions.groupBy { it.date }
        val existing = history.map { it.date }.toSet()
        val first = history.minOfOrNull { it.date }
        val merged = history.map { d ->
            val day = logged[d.date] ?: return@map d
            d.copy(sessions = d.sessions.filterNot { isStrengthType(it.type) } + day.map(::toExerciseSession))
        }
        val extra = logged.filterKeys { it !in existing && (first == null || it > first) }
            .map { (date, day) -> DailyMetrics(date, sessions = day.map(::toExerciseSession)) }
        return (merged + extra).sortedBy { it.date }
    }

    /**
     * [from]..[to]（含）之间每个肌群的有效组数：主要肌群（权重 1.0）算 1 组，协同肌群（0.5）算 0.5 组，
     * 辅助肌群不计。所有肌群都会出现在结果里（没练过为 0）。
     */
    fun hardSets(sessions: List<StrengthSession>, from: LocalDate, to: LocalDate): Map<MuscleGroup, Double> {
        val result = MuscleGroup.entries.associateWith { 0.0 }.toMutableMap()
        sessions.filter { it.date in from..to && it.sessionRpe > MIN_HARD_RPE }.forEach { s ->
            s.exercises.forEach { e ->
                ExerciseCatalog.byId(e.exerciseId)?.muscles?.forEach { (m, w) ->
                    if (w >= 0.5) result[m] = result.getValue(m) + e.sets * w
                }
            }
        }
        return result
    }
}
