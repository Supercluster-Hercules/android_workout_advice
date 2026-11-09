package com.fitcoach.engine

/**
 * 指数加权移动平均 (EWMA)。相比简单滑动平均，近期数据权重更高，
 * 对负荷变化更敏感（Williams et al., 2017, Br J Sports Med 建议用 EWMA 计算 ACWR）。
 *
 * λ = 2 / (N + 1)：急性 N = 7 → λ ≈ 0.25；慢性 N = 28 → λ ≈ 0.069。
 */
object LoadModel {

    fun lambda(span: Int): Double = 2.0 / (span + 1)

    /** 返回与 [loads] 等长的 EWMA 序列。用前 [seedDays] 天的均值作为初始值。 */
    fun ewmaSeries(loads: List<Double>, span: Int, seedDays: Int = 7): List<Double> {
        if (loads.isEmpty()) return emptyList()
        val l = lambda(span)
        var value = loads.take(seedDays).average()
        return loads.map { load ->
            value = l * load + (1 - l) * value
            value
        }
    }

    fun ewma(loads: List<Double>, span: Int, seedDays: Int = 7): Double =
        ewmaSeries(loads, span, seedDays).lastOrNull() ?: 0.0

    /**
     * 明天再练 x 负荷后，新的 ACWR 不超过 [upper] 时 x 的最大值。
     * 由 λa·x + (1−λa)·A ≤ upper · (λc·x + (1−λc)·C) 解出。
     */
    fun maxLoadForAcwr(acute: Double, chronic: Double, upper: Double, acuteSpan: Int, chronicSpan: Int): Double {
        val la = lambda(acuteSpan)
        val lc = lambda(chronicSpan)
        val denominator = la - upper * lc
        if (denominator <= 0) return Double.POSITIVE_INFINITY
        return (upper * (1 - lc) * chronic - (1 - la) * acute) / denominator
    }
}
