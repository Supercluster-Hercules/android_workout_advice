package com.fitcoach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fitcoach.engine.DayKind
import com.fitcoach.engine.Goal
import com.fitcoach.engine.SessionFocus
import com.fitcoach.engine.PlannedDay
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun kindColor(kind: DayKind) = when (kind) {
    DayKind.REST -> Color(0xFF9E9E9E)
    DayKind.EASY -> Color(0xFF43A047)
    DayKind.MODERATE -> Color(0xFF1E88E5)
    DayKind.HARD -> Color(0xFFE53935)
}

@Composable
fun WeekTab(state: UiState.Ready) {
    val week = state.advice.week
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本周进度", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { week.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(
                "已完成 ${week.completedLoad.roundToInt()} / 目标 ${week.targetLoad.roundToInt()}（${(week.progress * 100).roundToInt()}%）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Texts.weekNote(week.note)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            if (week.goal == Goal.MAINTAIN) {
                Text(Texts.whoMinutes(week), style = MaterialTheme.typography.bodyMedium)
            }
            week.goal?.let {
                Text(
                    "目标：${Texts.goal(it)} —— ${Texts.goalDescription(it)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "本周目标根据你的慢性负荷和急慢性负荷比确定；安排遵循强弱交替、强度日之后不接强度日、每周至少休息一天。" +
                    if (week.goal == Goal.MUSCLE_GAIN) "力量训练按上下肢分化，同一部位至少间隔 48 小时。"
                    else if (week.goal != null) "两次力量训练至少间隔 48 小时，尽量不放在强度日。"
                    else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            week.days.forEach { DayRow(it, isToday = it.date == state.advice.recommendation.forDate, showFocus = week.goal != null) }
        }
    }
}

@Composable
private fun DayRow(day: PlannedDay, isToday: Boolean, showFocus: Boolean) {
    val background = if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(background).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(56.dp)) {
            Text(Texts.weekday(day.date.dayOfWeek), fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
            Text(day.date.format(DateTimeFormatter.ofPattern("M/d")), style = MaterialTheme.typography.bodySmall)
        }
        Box(
            Modifier.background(kindColor(day.kind), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(Texts.dayKind(day.kind), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        val focus = if (showFocus && day.focus != SessionFocus.REST) Texts.focus(day.focus) + " · " else ""
        val detail = when {
            day.isPast -> (if (day.isStrength) focus else "") + "实际负荷 ${day.actualLoad!!.roundToInt()}"
            day.kind == DayKind.REST -> "休息或散步拉伸"
            else -> "${focus}约 ${day.minutes} 分钟 · 负荷 ${day.targetLoad.roundToInt()}"
        }
        Text(
            (if (isToday) "今天 · " else "") + detail,
            style = MaterialTheme.typography.bodyMedium,
            color = if (day.isPast) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun TrendsTab(state: UiState.Ready) {
    val trends = state.advice.trends
    if (trends.weeks.isEmpty()) {
        Text("数据还不够，使用几周后这里会显示长期趋势。")
        return
    }

    TrendCard("每周训练负荷", "最近 ${trends.weeks.size} 周，强调色是本周（还没过完）") {
        BarChart(trends.weeks.map { it.totalLoad }, highlightLast = 1)
        val first = trends.weeks.first().weekStart
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(first.format(DateTimeFormatter.ofPattern("M/d")), style = MaterialTheme.typography.bodySmall)
            Text("本周", style = MaterialTheme.typography.bodySmall)
        }
    }

    TrendCard("急慢性负荷比", "阴影是 0.8–1.3 的安全区间") {
        LineChart(trends.acwr.map { it.value }, band = 0.8..1.3, format = { Texts.f2(it) })
    }

    if (trends.hrv7.isNotEmpty()) {
        TrendCard("HRV（7 天滚动均值）", "持续上升通常说明体能和恢复在变好；明显下滑要注意休息") {
            LineChart(trends.hrv7.map { it.value }, color = Color(0xFF00897B), format = { "${it.roundToInt()} ms" })
        }
    }

    if (trends.restingHr.isNotEmpty()) {
        TrendCard("静息心率", "长期缓慢下降通常是有氧能力提升的表现") {
            LineChart(trends.restingHr.map { it.value }, color = Color(0xFFD81B60), format = { "${it.roundToInt()}" })
        }
    }

    TrendCard("每周汇总", null) {
        Row(Modifier.fillMaxWidth()) {
            listOf("周", "训练天数", "静息心率", "HRV", "睡眠").forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            }
        }
        trends.weeks.reversed().forEach { w ->
            Row(Modifier.fillMaxWidth()) {
                Text(w.weekStart.format(DateTimeFormatter.ofPattern("M/d")), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("${w.trainingDays}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(w.avgRestingHr?.roundToInt()?.toString() ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(w.avgHrv?.roundToInt()?.toString() ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(w.avgSleepMinutes?.let { Texts.f1(it / 60) + "h" } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TrendCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}
