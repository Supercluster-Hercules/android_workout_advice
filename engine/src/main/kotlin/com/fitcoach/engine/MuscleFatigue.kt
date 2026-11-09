package com.fitcoach.engine

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * 肌肉疲劳估算 —— 启发式模型，不是测量值。
 *
 * 目前没有经过验证的可穿戴设备指标能测出单个肌群的疲劳，所以这里用一个简单、可解释的模型，
 * 再用用户的酸痛自评来校准。界面上所有结果都要标注"估算"。
 *
 * 1. 每次训练给每个肌群一个"剂量"，单位是"相当于多少个力竭组"：
 *    - 力量：组数 × 肌群权重 × 相对强度（由整次 RPE 换算）；
 *    - 有氧：小时数 × (HRR / 0.7) × 肌群权重 × 6。
 * 2. 疲劳 = Σ 剂量 × e^(−经过小时 / τ)，τ 为每个肌群的时间常数（可被酸痛自评校准）。
 * 3. 恢复度 = 1 − min(1, 疲劳 / 10)。
 */

/** 某个部位的酸痛自评，0 = 完全不酸，10 = 非常酸痛。 */
data class SorenessReport(val time: LocalDateTime, val muscle: MuscleGroup, val score: Int)

/** Health Connect 中能映射到肌群的有氧运动类型。 */
enum class CardioKind { RUN, CYCLE, SWIM, ROW, ELLIPTICAL, HIKE, WALK }

object CardioMuscleMap {

    val weights: Map<CardioKind, Map<MuscleGroup, Double>> = mapOf(
        CardioKind.RUN to mapOf(
            MuscleGroup.QUADS to 1.0, MuscleGroup.CALVES to 1.0, MuscleGroup.HAMSTRINGS to 0.6,
            MuscleGroup.GLUTES to 0.6, MuscleGroup.CORE to 0.3,
        ),
        CardioKind.CYCLE to mapOf(
            MuscleGroup.QUADS to 1.0, MuscleGroup.GLUTES to 0.6, MuscleGroup.HAMSTRINGS to 0.4, MuscleGroup.CALVES to 0.4,
        ),
        CardioKind.SWIM to mapOf(
            MuscleGroup.BACK to 1.0, MuscleGroup.SHOULDERS to 1.0, MuscleGroup.CHEST to 0.5,
            MuscleGroup.TRICEPS to 0.5, MuscleGroup.CORE to 0.4, MuscleGroup.QUADS to 0.2,
        ),
        CardioKind.ROW to mapOf(
            MuscleGroup.BACK to 1.0, MuscleGroup.QUADS to 0.6, MuscleGroup.BICEPS to 0.5,
            MuscleGroup.HAMSTRINGS to 0.4, MuscleGroup.GLUTES to 0.4, MuscleGroup.CORE to 0.4,
        ),
        CardioKind.ELLIPTICAL to mapOf(
            MuscleGroup.QUADS to 0.7, MuscleGroup.GLUTES to 0.5, MuscleGroup.CALVES to 0.4,
            MuscleGroup.HAMSTRINGS to 0.4, MuscleGroup.SHOULDERS to 0.2,
        ),
        CardioKind.HIKE to mapOf(MuscleGroup.QUADS to 0.8, MuscleGroup.GLUTES to 0.8, MuscleGroup.CALVES to 0.6, MuscleGroup.HAMSTRINGS to 0.4),
        CardioKind.WALK to mapOf(MuscleGroup.QUADS to 0.3, MuscleGroup.CALVES to 0.4, MuscleGroup.GLUTES to 0.3),
    )

    // 顺序有意义："跑步机"要先于"步行"匹配，"徒步"要先于"步行"匹配
    private val keywords: List<Pair<CardioKind, List<String>>> = listOf(
        CardioKind.RUN to listOf("跑", "run", "jog"),
        CardioKind.CYCLE to listOf("骑", "自行车", "单车", "动感", "bik", "cycl", "spin"),
        CardioKind.SWIM to listOf("游泳", "泳", "swim"),
        CardioKind.ROW to listOf("划船", "划艇", "row"),
        CardioKind.ELLIPTICAL to listOf("椭圆", "elliptical"),
        CardioKind.HIKE to listOf("徒步", "登山", "爬山", "爬楼", "楼梯", "hik", "stair"),
        CardioKind.WALK to listOf("步行", "走", "walk"),
    )

    fun kindOf(type: String?): CardioKind? {
        val t = type?.lowercase() ?: return null
        if (StrengthLoad.isStrengthType(t)) return null
        return keywords.firstOrNull { (_, words) -> words.any { it in t } }?.first
    }

    /** 没有心率时各类有氧的默认强度（心率储备）。 */
    fun defaultHrr(kind: CardioKind): Double = when (kind) {
        CardioKind.WALK -> TrainingLoad.WALKING_HRR
        CardioKind.HIKE -> 0.5
        else -> TrainingLoad.UNKNOWN_SESSION_HRR
    }
}

/** 某次训练给某个肌群的剂量（等效力竭组数）。 */
data class MuscleDose(val time: LocalDateTime, val muscle: MuscleGroup, val sets: Double)

data class MuscleStatus(
    val muscle: MuscleGroup,
    /** 估算的恢复度 0–1（已融合最近的酸痛自评）。 */
    val recovery: Double,
    /** 只看训练记录的模型恢复度。 */
    val modelRecovery: Double,
    /** 预计还要多少小时恢复到 90%；已恢复时为 0。 */
    val hoursToRecovered: Int,
    /** 最近 7 天的有效组数。 */
    val weeklyHardSets: Double,
    /** 参与融合的最近一次酸痛自评；没有为 null。 */
    val sorenessScore: Int?,
    /** 酸痛自评校准出的恢复时间系数（1.0 = 未校准）。 */
    val tauScale: Double,
) {
    val recovered: Boolean get() = recovery >= MuscleFatigueModel.AVOID_BELOW
}

/** 今天建议练哪里（只根据主要肌群的恢复情况）。 */
enum class TrainingFocus { ANY, UPPER_BODY, LOWER_BODY, LIGHT_ONLY }

data class MuscleReport(
    val at: LocalDateTime,
    /** 按 [MuscleGroup] 的顺序。 */
    val statuses: List<MuscleStatus>,
    val focus: TrainingFocus,
    /** 恢复不足 70% 的肌群，按恢复度从低到高。 */
    val unrecovered: List<MuscleStatus>,
    /** 至少有一个肌群用酸痛自评校准过恢复时间。 */
    val calibrated: Boolean,
    /** 最近 7 天 Health Connect 里有力量训练、但没有动作明细（无法分配到肌群）的天数。 */
    val unloggedStrengthDays: Int,
    /** 最近 7 天是否有任何能分配到肌群的训练。 */
    val hasData: Boolean,
) {
    fun status(m: MuscleGroup): MuscleStatus = statuses.first { it.muscle == m }

    /** 某部位主要肌群中最低的恢复度。 */
    fun regionRecovery(region: BodyRegion): Double =
        statuses.filter { it.muscle.region == region && it.muscle.major }.minOfOrNull { it.recovery } ?: 1.0

    fun regionReady(region: BodyRegion): Boolean = regionRecovery(region) >= MuscleFatigueModel.AVOID_BELOW
}

object MuscleFatigueModel {
    /** 剂量达到这么多"力竭组"视为完全疲劳（恢复度 0）。 */
    const val FULL_FATIGUE_SETS = 10.0
    /** 恢复度低于这个值建议避开该肌群。 */
    const val AVOID_BELOW = 0.7
    /** 恢复度达到这个值视为已恢复（用于"还需多少小时"）。 */
    const val RECOVERED = 0.9
    /** 1 小时 HRR 0.7 的有氧，权重 1.0 的肌群约相当于 6 个力竭组。 */
    const val CARDIO_SETS_PER_HOUR = 6.0
    const val REFERENCE_HRR = 0.7
    /** Health Connect 缓存里只有日期，没有时间：假设运动在当天 18:00 结束。 */
    val ASSUMED_SESSION_END: LocalTime = LocalTime.of(18, 0)

    /** 读多久以前的训练：当前疲劳只受最近几天影响，更早的用于酸痛自评校准。 */
    private const val LOOKBACK_DAYS = 60L
    /** 酸痛自评的融合权重（刚填写时），随时间以 24 小时为时间常数衰减。 */
    private const val SORENESS_WEIGHT = 0.6
    private const val SORENESS_DECAY_HOURS = 24.0
    private const val SORENESS_MAX_AGE_HOURS = 36L
    /** 训练后这么多小时内的自评不用于拟合（延迟性酸痛还没出现）。 */
    private const val CALIBRATION_MIN_HOURS_AFTER = 12L
    private const val CALIBRATION_MIN_DOSE = 0.5
    private val SCALE_GRID = (0..20).map { 0.6 + it * 0.05 }

    fun estimate(
        history: List<DailyMetrics>,
        strength: List<StrengthSession>,
        soreness: List<SorenessReport>,
        profile: UserProfile,
        at: LocalDateTime,
    ): MuscleReport {
        val doses = doses(history, strength, profile, at)
        val byMuscle = doses.groupBy { it.muscle }
        val reportsByMuscle = soreness.filter { !it.time.isAfter(at) }.groupBy { it.muscle }
        val scales = calibrate(byMuscle, reportsByMuscle)
        val hardSets = StrengthLoad.hardSets(strength, at.toLocalDate().minusDays(6), at.toLocalDate())

        val statuses = MuscleGroup.entries.map { m ->
            val muscleDoses = byMuscle[m].orEmpty()
            val scale = scales[m] ?: 1.0
            val tau = m.recoveryTauHours * scale
            val model = recoveryFrom(fatigueAt(muscleDoses, at, tau))
            val lastDose = muscleDoses.filter { !it.time.isAfter(at) }.maxOfOrNull { it.time }
            // 只用训练之后、36 小时内的自评；之后又练过的话，旧自评已经过时
            val report = reportsByMuscle[m].orEmpty()
                .filter { Duration.between(it.time, at).toHours() <= SORENESS_MAX_AGE_HOURS }
                .filter { lastDose == null || !it.time.isBefore(lastDose) }
                .maxByOrNull { it.time }
            val recovery = if (report == null) model else {
                val w = SORENESS_WEIGHT * exp(-hoursBetween(report.time, at) / SORENESS_DECAY_HOURS)
                (1 - w) * model + w * impliedRecovery(report.score)
            }
            MuscleStatus(
                muscle = m,
                recovery = recovery,
                modelRecovery = model,
                hoursToRecovered = hoursToRecovered(recovery, tau),
                weeklyHardSets = hardSets.getValue(m),
                sorenessScore = report?.score,
                tauScale = scale,
            )
        }

        val upperReady = statuses.regionMin(BodyRegion.UPPER) >= AVOID_BELOW
        val lowerReady = statuses.regionMin(BodyRegion.LOWER) >= AVOID_BELOW
        val focus = when {
            upperReady && lowerReady -> TrainingFocus.ANY
            upperReady -> TrainingFocus.UPPER_BODY
            lowerReady -> TrainingFocus.LOWER_BODY
            else -> TrainingFocus.LIGHT_ONLY
        }

        val since = at.toLocalDate().minusDays(6)
        val loggedDates = strength.map { it.date }.toSet()
        val unlogged = history.count { d ->
            d.date >= since && !d.date.isAfter(at.toLocalDate()) && d.date !in loggedDates &&
                d.sessions.any { StrengthLoad.isStrengthType(it.type) }
        }

        return MuscleReport(
            at = at,
            statuses = statuses,
            focus = focus,
            unrecovered = statuses.filter { !it.recovered }.sortedBy { it.recovery },
            calibrated = scales.values.any { it != 1.0 },
            unloggedStrengthDays = unlogged,
            hasData = doses.any { Duration.between(it.time, at).toDays() < 7 },
        )
    }

    /** 最近 [LOOKBACK_DAYS] 天所有训练对各肌群的剂量。 */
    fun doses(
        history: List<DailyMetrics>,
        strength: List<StrengthSession>,
        profile: UserProfile,
        at: LocalDateTime,
    ): List<MuscleDose> {
        val since = at.minusDays(LOOKBACK_DAYS)
        val result = mutableListOf<MuscleDose>()

        strength.filter { it.end.isAfter(since) && !it.start.isAfter(at) }.forEach { s ->
            val intensity = StrengthLoad.relativeIntensity(s.sessionRpe)
            val time = minOf(s.end, at)
            s.exercises.forEach { e ->
                ExerciseCatalog.byId(e.exerciseId)?.muscles?.forEach { (m, w) ->
                    result += MuscleDose(time, m, e.sets.coerceAtLeast(0) * w * intensity)
                }
            }
        }

        val restingBaseline = RecommendationEngine.median(history.mapNotNull { it.restingHeartRate })
        val maxHr = profile.estimatedMaxHr()
        history.filter { !it.date.isBefore(since.toLocalDate()) && !it.date.isAfter(at.toLocalDate()) }.forEach { day ->
            val time = minOf(day.date.atTime(ASSUMED_SESSION_END), at)
            val restingHr = day.restingHeartRate ?: restingBaseline ?: TrainingLoad.DEFAULT_RESTING_HR
            day.sessions.forEach { session ->
                val kind = CardioMuscleMap.kindOf(session.type) ?: return@forEach
                val hrr = session.rpe?.let(TrainingLoad::hrrFromRpe)
                    ?: session.avgHeartRate?.let { TrainingLoad.heartRateReserve(it, restingHr, maxHr) }
                    ?: day.sessionRpe?.let(TrainingLoad::hrrFromRpe)
                    ?: CardioMuscleMap.defaultHrr(kind)
                val intensity = hrr.coerceIn(0.0, 1.0) / REFERENCE_HRR
                val hours = session.durationMinutes / 60
                CardioMuscleMap.weights.getValue(kind).forEach { (m, w) ->
                    result += MuscleDose(time, m, hours * intensity * w * CARDIO_SETS_PER_HOUR)
                }
            }
        }
        return result
    }

    /** [at] 时刻的残余疲劳（等效力竭组数）。 */
    fun fatigueAt(doses: List<MuscleDose>, at: LocalDateTime, tauHours: Double): Double =
        doses.filter { !it.time.isAfter(at) }.sumOf { it.sets * exp(-hoursBetween(it.time, at) / tauHours) }

    fun recoveryFrom(fatigueSets: Double): Double = 1 - min(1.0, fatigueSets / FULL_FATIGUE_SETS)

    /** 酸痛评分换算成恢复度：0 分 = 100%，10 分 = 0%。 */
    fun impliedRecovery(score: Int): Double = 1 - score.coerceIn(0, 10) / 10.0

    /** 从当前恢复度按指数衰减推算到 [RECOVERED] 还需多少小时。 */
    fun hoursToRecovered(recovery: Double, tauHours: Double): Int {
        if (recovery >= RECOVERED) return 0
        val fatigue = (1 - recovery) * FULL_FATIGUE_SETS
        val target = (1 - RECOVERED) * FULL_FATIGUE_SETS
        return (tauHours * ln(fatigue / target)).roundToInt()
    }

    /**
     * 用历史酸痛自评为每个肌群拟合恢复时间系数 k（τ × k，k ∈ [0.6, 1.6]），使模型恢复度与
     * "自评换算的恢复度"的平方误差最小。只用训练后 12 小时以上、且之前确实有训练剂量的自评。
     * 某肌群样本 ≥ 2 次时单独拟合；否则所有肌群样本合计 ≥ 3 次时共用一个系数；都不够则为 1。
     * 拟合结果按样本数向 1 收缩：k = 1 + (k_fit − 1) × n / (n + 2)，避免几次自评就大幅改变模型。
     */
    internal fun calibrate(
        dosesByMuscle: Map<MuscleGroup, List<MuscleDose>>,
        reportsByMuscle: Map<MuscleGroup, List<SorenessReport>>,
    ): Map<MuscleGroup, Double> {
        val samples = MuscleGroup.entries.associateWith { m ->
            val doses = dosesByMuscle[m].orEmpty()
            reportsByMuscle[m].orEmpty().filter { r ->
                val last = doses.filter { !it.time.isAfter(r.time) }.maxOfOrNull { it.time } ?: return@filter false
                Duration.between(last, r.time).toHours() >= CALIBRATION_MIN_HOURS_AFTER &&
                    fatigueAt(doses, r.time, m.recoveryTauHours * SCALE_GRID.last()) >= CALIBRATION_MIN_DOSE
            }
        }

        fun error(m: MuscleGroup, reports: List<SorenessReport>, k: Double): Double {
            val doses = dosesByMuscle[m].orEmpty()
            return reports.sumOf { r ->
                val diff = recoveryFrom(fatigueAt(doses, r.time, m.recoveryTauHours * k)) - impliedRecovery(r.score)
                diff * diff
            }
        }

        fun shrink(k: Double, n: Int) = 1 + (k - 1) * n / (n + 2.0)

        val total = samples.values.sumOf { it.size }
        val global = if (total >= 3) {
            val k = SCALE_GRID.minBy { k -> samples.entries.sumOf { (m, r) -> error(m, r, k) } }
            shrink(k, total)
        } else 1.0

        return MuscleGroup.entries.associateWith { m ->
            val reports = samples.getValue(m)
            if (reports.size >= 2) shrink(SCALE_GRID.minBy { k -> error(m, reports, k) }, reports.size) else global
        }
    }

    private fun List<MuscleStatus>.regionMin(region: BodyRegion): Double =
        filter { it.muscle.region == region && it.muscle.major }.minOfOrNull { it.recovery } ?: 1.0

    private fun hoursBetween(from: LocalDateTime, to: LocalDateTime): Double =
        max(0.0, Duration.between(from, to).toMinutes() / 60.0)
}
