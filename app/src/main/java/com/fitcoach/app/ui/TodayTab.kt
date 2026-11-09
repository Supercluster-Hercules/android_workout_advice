package com.fitcoach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fitcoach.engine.Level
import com.fitcoach.engine.Severity
import kotlin.math.roundToInt

fun levelColor(level: Level) = when (level) {
    Level.INCREASE -> Color(0xFF2E7D32)
    Level.MAINTAIN -> Color(0xFF1565C0)
    Level.DECREASE -> Color(0xFFEF6C00)
    Level.REST -> Color(0xFF6A1B9A)
}

@Composable
fun TodayTab(state: UiState.Ready, actions: MainActions) {
    val r = state.advice.recommendation

    // 主建议卡片
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = levelColor(r.level), contentColor = Color.White),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${Texts.dayLabel(r.forDate)}建议", style = MaterialTheme.typography.labelLarge)
            Text(Texts.level(r.level), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(Texts.plan(r), style = MaterialTheme.typography.bodyLarge)
            Text(Texts.confidence(r.confidence), style = MaterialTheme.typography.bodySmall)
        }
    }

    state.feedback?.let { FeedbackCard(it, actions) }

    // 理由
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("判断依据", style = MaterialTheme.typography.titleMedium)
            r.signals.forEach { s ->
                val marker = when (s.severity) {
                    Severity.SEVERE -> "●●"
                    Severity.MODERATE -> "●"
                    Severity.INFO -> "·"
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(marker, color = if (s.severity == Severity.INFO) Color.Gray else Color(0xFFD84315))
                    Text(Texts.signal(s), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (r.baseLevel != r.level) {
                Text(
                    "单看训练量应当「${Texts.level(r.baseLevel)}」，结合恢复情况调整为「${Texts.level(r.level)}」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 恢复指标
    val rec = r.recovery
    if (rec.restingHr != null || rec.hrv != null || rec.lastSleepMinutes != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("恢复情况", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric(
                        "静息心率",
                        rec.restingHr?.let { "${it.roundToInt()}" } ?: "—",
                        rec.restingHrBaseline?.let { "平时 ${it.roundToInt()}" },
                    )
                    Metric(
                        "HRV 7 天均值",
                        rec.hrv7DayAvg?.let { "${it.roundToInt()}" } ?: rec.hrv?.let { "${it.roundToInt()}" } ?: "—",
                        rec.hrvBaseline?.let { "基线 ${it.roundToInt()} ms" },
                    )
                    Metric(
                        "睡眠",
                        rec.lastSleepMinutes?.let { Texts.f1(it / 60) + "h" } ?: "—",
                        rec.sleepEfficiency?.let { "效率 ${(it * 100).roundToInt()}%" }
                            ?: rec.avgSleepMinutes3d?.let { "3 晚均 ${Texts.f1(it / 60)}h" },
                    )
                }
                val extras = listOfNotNull(
                    rec.restingHrThreshold?.let { "静息心率偏高阈值：+${Texts.f1(it)} bpm（按你的日常波动计算）" },
                    rec.hrvNormalLow?.let { "HRV 正常波动下限：${it.roundToInt()} ms" },
                    rec.bodyTemperatureC?.let { "体温：${Texts.f1(it)}℃" },
                    rec.skinTempDeltaC?.let { "夜间皮肤温度：比平时 ${if (it >= 0) "高" else "低"} ${Texts.f1(kotlin.math.abs(it))}℃" },
                )
                extras.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // 负荷指标 + 图
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("训练负荷（近 28 天）", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("急慢性负荷比", r.acwr?.let { Texts.f2(it) } ?: "—")
                Metric("急性负荷", r.acuteLoad.roundToInt().toString())
                Metric("慢性负荷", r.chronicLoad.roundToInt().toString())
            }
            BarChart(r.dailyLoads.map { it.load }, highlightLast = 7, reference = r.chronicLoad)
            Text(
                "柱子是每日负荷 (TRIMP)，最近 7 天用强调色；虚线是慢性负荷。急性 / 慢性负荷用指数加权平均计算，近期权重更高。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 个性化调整
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("个性化调整", style = MaterialTheme.typography.titleMedium)
            r.adjustments.forEach { a ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("·", color = Color.Gray)
                    Text(Texts.adjustment(a, r), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (state.demoScenario == null) {
        state.sync?.let { sync ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sync == SyncStatus.Syncing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    Texts.syncStatus(sync),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sync is SyncStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row {
            TextButton(onClick = actions.onRefresh, enabled = state.sync != SyncStatus.Syncing) { Text("刷新数据") }
            TextButton(onClick = actions.onFullResync, enabled = state.sync != SyncStatus.Syncing) {
                Text("完整重新导入")
            }
        }
    } else {
        TextButton(onClick = actions.onRefresh) { Text("返回我的真实数据") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackCard(prompt: FeedbackPrompt, actions: MainActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("自我感觉", style = MaterialTheme.typography.titleMedium)
            Text(
                "主观感受是最可靠的恢复信号之一，也能弥补心率算不准力量训练负荷的问题。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (prompt.wellnessDate != null) {
                Text("今天早上感觉如何？", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { v ->
                        FilterChip(
                            selected = prompt.wellness == v,
                            onClick = { actions.onWellness(v) },
                            label = { Text(Texts.wellnessLabel(v)) },
                        )
                    }
                }
            }
            val whichDay = if (prompt.rpeDate == java.time.LocalDate.now()) "今天" else "昨天"
            Text(
                "${whichDay}的训练有多累？" + (prompt.rpe?.let { "（${it}：${Texts.rpeLabel(it)}）" } ?: ""),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0..10).forEach { v ->
                    FilterChip(selected = prompt.rpe == v, onClick = { actions.onRpe(v) }, label = { Text("$v") })
                }
            }
            Text(
                "0 = 没练，5 = 累，10 = 到极限（Foster CR-10 量表）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun Metric(label: String, value: String, sub: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
