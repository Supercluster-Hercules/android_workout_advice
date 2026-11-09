package com.fitcoach.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fitcoach.engine.MuscleGroup
import com.fitcoach.engine.SampleData
import com.fitcoach.engine.StrengthSession
import com.fitcoach.engine.UserProfile

data class MainActions(
    val onRequestPermissions: () -> Unit,
    val onInstallHealthConnect: () -> Unit,
    val onRefresh: () -> Unit,
    val onFullResync: () -> Unit,
    val onDemo: (SampleData.Scenario) -> Unit,
    val onEditProfile: () -> Unit,
    val onWellness: (Int) -> Unit,
    val onRpe: (Int) -> Unit,
    val onToggleNotification: (Boolean) -> Unit,
    val onAddStrength: (StrengthSession) -> Unit,
    val onDeleteStrength: (String) -> Unit,
    val onSoreness: (MuscleGroup, Int?) -> Unit,
)

private val tabs = listOf("今日", "本周", "力量", "趋势")

@Composable
fun MainScreen(
    state: UiState,
    profile: UserProfile,
    morningNotification: Boolean,
    backgroundReadSupported: Boolean,
    actions: MainActions,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
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
                text = "需要读取 Health Connect 中的步数、运动、心率、睡眠、体温等数据来分析训练负荷与恢复情况。" +
                    "Google Fit、Fitbit、三星健康等应用的数据都会同步到 Health Connect。",
                button = "授权读取",
                onClick = actions.onRequestPermissions,
            )
            is UiState.Error -> Message("读取数据出错：${state.message}", "重试", actions.onRefresh)
            is UiState.Ready -> {
                if (state.demoScenario != null) {
                    Text(
                        "演示数据：${Texts.scenario(state.demoScenario)}",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { i, title ->
                        Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(title) })
                    }
                }
                when (selectedTab) {
                    0 -> TodayTab(state, actions)
                    1 -> WeekTab(state)
                    2 -> StrengthTab(state, actions)
                    else -> TrendsTab(state)
                }
            }
        }

        SettingsCard(morningNotification, backgroundReadSupported, actions.onToggleNotification)
        DemoSection(actions.onDemo)

        Text(
            "本应用的建议仅供参考，不能替代医生或专业教练的意见。身体不适时请停止运动并就医。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCard(enabled: Boolean, backgroundReadSupported: Boolean, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("每天早上 7:30 推送建议", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (backgroundReadSupported) "会在后台同步最新数据（需要在授权页允许「在后台读取」），桌面小组件也会一起更新"
                    else "当前 Health Connect 版本不支持后台读取，推送会使用上次打开应用时同步的数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
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
