package com.fitcoach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement

/** 柱状图。[highlightLast] 根柱子用强调色；[reference] 画一条虚线（例如平均值）。 */
@Composable
fun BarChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    highlightLast: Int = 0,
    reference: Double? = null,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val highlightColor = MaterialTheme.colorScheme.tertiary
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val maxValue = maxOf(values.max(), reference ?: 0.0, 1.0) * 1.1
        val slot = size.width / values.size
        val barWidth = slot * 0.7f
        values.forEachIndexed { i, v ->
            val h = (v / maxValue * size.height).toFloat()
            drawRect(
                color = if (i >= values.size - highlightLast) highlightColor else barColor,
                topLeft = Offset(i * slot + (slot - barWidth) / 2, size.height - h),
                size = Size(barWidth, h),
            )
        }
        reference?.let {
            val y = (size.height - it / maxValue * size.height).toFloat()
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }
    }
}

/**
 * 折线图。[band] 用浅色画出一个参考区间（例如 ACWR 的安全区 0.8–1.3）。
 * 下方标出数值范围，方便阅读。
 */
@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    band: ClosedFloatingPointRange<Double>? = null,
    format: (Double) -> String = { Texts.f1(it) },
) {
    val bandColor = MaterialTheme.colorScheme.secondaryContainer
    if (values.size < 2) {
        Text("数据还不够画出趋势", style = MaterialTheme.typography.bodySmall)
        return
    }
    val minValue = minOf(values.min(), band?.start ?: Double.MAX_VALUE)
    val maxValue = maxOf(values.max(), band?.endInclusive ?: -Double.MAX_VALUE)
    val padding = (maxValue - minValue).coerceAtLeast(1e-6) * 0.1
    val lo = minValue - padding
    val hi = maxValue + padding
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
            band?.let {
                drawRect(bandColor, topLeft = Offset(0f, y(it.endInclusive)), size = Size(size.width, y(it.start) - y(it.endInclusive)))
            }
            val step = size.width / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * step, y(v))
            }
            drawPath(path, color, style = Stroke(width = 4f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最低 ${format(values.min())}", style = MaterialTheme.typography.bodySmall)
            Text("最新 ${format(values.last())}", style = MaterialTheme.typography.bodySmall)
            Text("最高 ${format(values.max())}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
