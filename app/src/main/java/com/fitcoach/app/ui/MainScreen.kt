package com.fitcoach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fitcoach.engine.Level
import com.fitcoach.engine.Recommendation
import com.fitcoach.engine.SampleData
import com.fitcoach.engine.Severity
import com.fitcoach.engine.UserProfile
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class MainActions(
    val onRequestPermissions: () -> Unit,
    val onInstallHealthConnect: () -> Unit,
    val onRefresh: () -> Unit,
    val onDemo: (SampleData.Scenario) -> Unit,
    val onEditProfile: () -> Unit,
    val onFullResync: () -> Unit,
)

@Composable
fun MainScreen(state: UiState, profile: UserProfile, actions: MainActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("运动建议", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Texts.profileSummary(profile),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = actions.onEditProfile) { Text("修改资料") }
        }

        when (state) {
            is UiState.Loading -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            is UiState.HealthConnectMissing -> Message(
                text = if (state.updateRequired) "需要更新 Health Connect 才能读取运动数据。"
                else "这台设备上没有 Health Connect。Android 14 及以上系统自带，Android 13 及以下需要从应用商店安装。",
                button = if (state.updateRequired) "去更新" else "去安装",
                onClick = actions.onInstallHealthConnect,
            )
            UiState.NeedsPermission -> Message(
                text = "需要读取 Health Connect 中的步数、运动、心率和睡眠数据来分析训练负荷与恢复情况。" +
                    "Google Fit、Fitbit、三星健康等应用的数据都会同步到 Health Connect。",
                button = "授权读取",
                onClick = actions.onRequestPermissions,
            )
            is UiState.Error -> Message("读取数据出错：${state.message}", "重试", actions.onRefresh)
            is UiState.Ready -> RecommendationContent(state, actions)
        }

        DemoSection(actions.onDemo)

        Text(
            "本应用的建议仅供参考，不能替代医生或专业教练的意见。身体不适时请停止运动并就医。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { content() }
}

@Composable
private fun Message(text: String, button: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text)
            Button(onClick = onClick) { Text(button) }
        }
    }
}

private fun levelColor(level: Level) = when (level) {
    Level.INCREASE -> Color(0xFF2E7D32)
    Level.MAINTAIN -> Color(0xFF1565C0)
    Level.DECREASE -> Color(0xFFEF6C00)
    Level.REST -> Color(0xFF6A1B9A)
}

@Composable
private fun RecommendationContent(state: UiState.Ready, actions: MainActions) {
    val r = state.recommendation
    val dayLabel = when (r.forDate) {
        LocalDate.now() -> "今天"
        LocalDate.now().plusDays(1) -> "明天"
        else -> r.forDate.format(DateTimeFormatter.ofPattern("M月d日"))
    }

    if (state.demoScenario != null) {
        Text(
            "演示数据：${Texts.scenario(state.demoScenario)}",
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelLarge,
        )
    }

    // 主建议卡片
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = levelColor(r.level), contentColor = Color.White),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${dayLabel}建议", style = MaterialTheme.typography.labelLarge)
            Text(Texts.level(r.level), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(Texts.plan(r), style = MaterialTheme.typography.bodyLarge)
            Text(Texts.confidence(r.confidence), style = MaterialTheme.typography.bodySmall)
        }
    }

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

    // 负荷指标 + 图
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("训练负荷（近 28 天）", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("急慢性负荷比", r.acwr?.let { Texts.f2(it) } ?: "—")
                Metric("近 7 天日均", r.acuteLoad.roundToInt().toString())
                Metric("近 28 天日均", r.chronicLoad.roundToInt().toString())
            }
            LoadChart(r)
            Text(
                "柱子是每日负荷 (TRIMP)，虚线是 28 天日均。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                        "HRV (ms)",
                        rec.hrv?.let { "${it.roundToInt()}" } ?: "—",
                        rec.hrvBaseline?.let { "平时 ${it.roundToInt()}" },
                    )
                    Metric(
                        "睡眠",
                        rec.lastSleepMinutes?.let { Texts.f1(it / 60) + "h" } ?: "—",
                        rec.avgSleepMinutes3d?.let { "3 晚均 ${Texts.f1(it / 60)}h" },
                    )
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

@Composable
private fun Metric(label: String, value: String, sub: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadChart(r: Recommendation) {
    val barColor = MaterialTheme.colorScheme.primary
    val recentColor = MaterialTheme.colorScheme.tertiary
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val loads = r.dailyLoads
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        if (loads.isEmpty()) return@Canvas
        val maxLoad = maxOf(loads.maxOf { it.load }, r.chronicLoad, 1.0) * 1.1
        val slot = size.width / loads.size
        val barWidth = slot * 0.7f
        loads.forEachIndexed { i, d ->
            val h = (d.load / maxLoad * size.height).toFloat()
            drawRect(
                color = if (i >= loads.size - 7) recentColor else barColor,
                topLeft = Offset(i * slot + (slot - barWidth) / 2, size.height - h),
                size = Size(barWidth, h),
            )
        }
        val y = (size.height - r.chronicLoad / maxLoad * size.height).toFloat()
        drawLine(
            color = lineColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoSection(onDemo: (SampleData.Scenario) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text("没有数据？试试演示场景：", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SampleData.Scenario.entries.forEach { s ->
            OutlinedButton(onClick = { onDemo(s) }) { Text(Texts.scenario(s)) }
        }
    }
}
