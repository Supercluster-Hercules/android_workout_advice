package com.fitcoach.engine

import kotlin.math.roundToInt

enum class Modality { AEROBIC, STRENGTH, RECOVERY }

/**
 * 按训练目标给出的"今天练什么、怎么练"。等级（加量 / 维持 / 减量 / 休息）和目标负荷来自
 * [RecommendationEngine]，这里不会改变它们，只决定内容：
 *
 * | | 减脂 | 增肌 | 保持健康 |
 * |---|---|---|---|
 * | 方式 | 有氧为主 + 每周 2 次全身力量 | 力量为主 | 有氧 + 每周 2 次全身力量 |
 * | 低强度占比 | 80% | 90%（有氧日） | 70% |
 * | 加量 | 有氧时长 + 每天多走约 2000 步 | 每个动作 +1 组，或次数做满后 +2.5–5% 重量 | 有氧多 5–10 分钟 |
 */
data class Prescription(
    val goal: Goal?,
    val level: Level,
    val modality: Modality,
    /** 今天的训练内容（来自一周计划）。 */
    val focus: SessionFocus,
    /** 按肌肉恢复估算，今天适合练哪里。 */
    val muscleFocus: TrainingFocus,
    val aerobicMinutes: Int,
    val strengthMinutes: Int,
    /** 力量训练建议的整次 RPE。 */
    val strengthRpe: Int,
    /** 有氧中低强度（Zone 2）应占的比例。 */
    val easyShare: Double,
    /** 减脂：今天的步数目标；其他目标为 null。 */
    val stepTarget: Int?,
    /** 增肌：每个动作相比上次增减几组。 */
    val setsDeltaPerExercise: Int,
    /** 增肌：次数做满后可以加的重量比例（例如 0.025 = 2.5%）。 */
    val weightIncreasePct: Double,
    /** 本周有效组数低于 10 组、且已恢复的主要肌群（增肌时优先练）。 */
    val undertrained: List<MuscleGroup>,
    /** 本周有效组数超过 20 组的肌群。 */
    val overtrained: List<MuscleGroup>,
)

object GoalAdvisor {

    private const val STEP_INCREASE = 2000
    private const val MIN_STEP_TARGET = 6000

    /**
     * @param today 一周计划里今天那一项（决定今天是有氧还是力量、练哪个部位）
     * @param recentAvgSteps 最近 7 天平均步数；没有步数数据为 null
     */
    fun prescribe(
        r: Recommendation,
        today: PlannedDay,
        muscles: MuscleReport?,
        recentAvgSteps: Double?,
    ): Prescription {
        val goal = r.profile.goal
        val focus = if (r.level == Level.REST) SessionFocus.REST else today.focus
        val modality = when {
            focus == SessionFocus.REST -> Modality.RECOVERY
            WeeklyPlanner.isStrength(focus) -> Modality.STRENGTH
            else -> Modality.AEROBIC
        }

        val easyShare = when (goal) {
            Goal.FAT_LOSS -> 0.8
            Goal.MUSCLE_GAIN -> 0.9
            Goal.MAINTAIN -> 0.7
            null -> 0.8
        }

        val stepTarget = if (goal == Goal.FAT_LOSS && recentAvgSteps != null) {
            val base = when (r.level) {
                Level.INCREASE -> recentAvgSteps + STEP_INCREASE
                else -> recentAvgSteps
            }
            ((base / 500).roundToInt() * 500).coerceAtLeast(MIN_STEP_TARGET)
        } else null

        val setsDelta = if (goal != Goal.MUSCLE_GAIN) 0 else when (r.level) {
            Level.INCREASE -> 1
            Level.MAINTAIN, Level.REST -> 0
            Level.DECREASE -> -1
        }
        val weightPct = if (goal == Goal.MUSCLE_GAIN && r.level == Level.INCREASE) 0.025 else 0.0

        val undertrained = muscles?.statuses.orEmpty()
            .filter { it.muscle.major && it.recovered && it.weeklyHardSets < StrengthLoad.EFFECTIVE_SETS_LOW }
            .map { it.muscle }
        val overtrained = muscles?.statuses.orEmpty()
            .filter { it.weeklyHardSets > StrengthLoad.EFFECTIVE_SETS_HIGH }
            .map { it.muscle }

        val strengthRpe = when (r.level) {
            Level.DECREASE -> 6
            Level.INCREASE -> 8
            else -> 7
        }

        return Prescription(
            goal = goal,
            level = r.level,
            modality = modality,
            focus = focus,
            muscleFocus = muscles?.focus ?: TrainingFocus.ANY,
            aerobicMinutes = if (modality == Modality.AEROBIC) today.minutes else 0,
            strengthMinutes = if (modality == Modality.STRENGTH) today.minutes else 0,
            strengthRpe = strengthRpe,
            easyShare = easyShare,
            stepTarget = stepTarget,
            setsDeltaPerExercise = setsDelta,
            weightIncreasePct = weightPct,
            undertrained = undertrained,
            overtrained = overtrained,
        )
    }

    /** 最近 7 天（forDate 之前）的平均步数。 */
    fun recentAvgSteps(history: List<DailyMetrics>, r: Recommendation): Double? {
        val from = r.forDate.minusDays(7)
        val steps = history.filter { it.date >= from && it.date < r.forDate }.mapNotNull { it.steps }
        return if (steps.isEmpty()) null else steps.average()
    }
}
