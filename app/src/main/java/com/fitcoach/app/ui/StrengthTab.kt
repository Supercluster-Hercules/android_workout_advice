package com.fitcoach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fitcoach.engine.ExerciseCatalog
import com.fitcoach.engine.ExerciseEntry
import com.fitcoach.engine.MuscleGroup
import com.fitcoach.engine.MuscleReport
import com.fitcoach.engine.StrengthLoad
import com.fitcoach.engine.StrengthSession
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

private val SETS_RANGE = 1..20
private val REPS_RANGE = 1..100
private val MINUTES_RANGE = 5..240

@Composable
fun StrengthTab(state: UiState.Ready, actions: MainActions) {
    val strength = state.strength
    if (strength.readOnly) {
        Text(
            "演示模式：显示的是演示用的力量训练记录（每周二上肢、周四下肢），不能修改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    } else {
        LogCard(actions.onAddStrength)
    }
    WeeklySetsCard(state.advice.muscles)
    SorenessCard(strength.todaySoreness, enabled = !strength.readOnly, onSoreness = actions.onSoreness)
    RecentCard(strength.recent, canDelete = !strength.readOnly, onDelete = actions.onDeleteStrength)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogCard(onSave: (StrengthSession) -> Unit) {
    val entries = remember { mutableStateListOf<ExerciseEntry>() }
    var exerciseId by rememberSaveable { mutableStateOf(ExerciseCatalog.all.first().id) }
    var setsText by rememberSaveable { mutableStateOf("3") }
    var repsText by rememberSaveable { mutableStateOf("10") }
    var weightText by rememberSaveable { mutableStateOf("") }
    var minutesText by rememberSaveable { mutableStateOf("45") }
    var rpe by rememberSaveable { mutableStateOf<Int?>(null) }
    var yesterday by rememberSaveable { mutableStateOf(false) }

    val sets = setsText.toIntOrNull()?.takeIf { it in SETS_RANGE }
    val reps = repsText.toIntOrNull()?.takeIf { it in REPS_RANGE }
    val weight = weightText.replace(',', '.').toDoubleOrNull()
    val weightValid = weightText.isBlank() || (weight != null && weight in 0.0..500.0)
    val minutes = minutesText.toIntOrNull()?.takeIf { it in MINUTES_RANGE }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("记录力量训练", style = MaterialTheme.typography.titleMedium)
            Text(
                "心率会低估力量训练的负荷，所以按「整次训练的 RPE × 分钟」计算负荷，并计入急慢性负荷比。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !yesterday, onClick = { yesterday = false }, label = { Text("今天") })
                FilterChip(selected = yesterday, onClick = { yesterday = true }, label = { Text("昨天") })
            }

            entries.forEachIndexed { i, e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(entryLabel(e), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { entries.removeAt(i) }) { Text("移除") }
                }
            }

            ExercisePicker(exerciseId) { exerciseId = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(setsText, { setsText = it }, "组数", sets == null, Modifier.weight(1f))
                NumberField(repsText, { repsText = it }, "次数", reps == null, Modifier.weight(1f))
                NumberField(
                    weightText, { weightText = it }, "重量 kg", !weightValid, Modifier.weight(1.2f),
                    decimal = true,
                )
            }
            Text(
                "重量留空表示自重。每组次数不同时填平均值即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                enabled = sets != null && reps != null && weightValid,
                onClick = {
                    entries += ExerciseEntry(exerciseId, sets!!, reps!!, weight?.takeIf { weightText.isNotBlank() })
                },
            ) { Text("添加这个动作") }

            HorizontalDivider()
            NumberField(minutesText, { minutesText = it }, "整次训练时长（分钟）", minutes == null, Modifier.fillMaxWidth())
            Text(
                "整次训练有多累？" + (rpe?.let { "（$it：${Texts.rpeLabel(it)}）" } ?: ""),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..10).forEach { v ->
                    FilterChip(selected = rpe == v, onClick = { rpe = v }, label = { Text("$v") })
                }
            }
            Text(
                "练完 15–30 分钟后回想整次训练的感受（Foster session-RPE），不是最后一组的感受。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = entries.isNotEmpty() && minutes != null && rpe != null,
                onClick = {
                    val now = LocalDateTime.now()
                    val end = if (yesterday) now.toLocalDate().minusDays(1).atTime(19, 0) else now
                    onSave(
                        StrengthSession(
                            id = UUID.randomUUID().toString(),
                            start = end.minusMinutes(minutes!!.toLong()),
                            durationMinutes = minutes.toDouble(),
                            sessionRpe = rpe!!,
                            exercises = entries.toList(),
                        ),
                    )
                    entries.clear()
                    rpe = null
                },
            ) { Text("保存本次训练") }
        }
    }
}

@Composable
private fun ExercisePicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("动作：${Texts.exercise(selected)} ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ExerciseCatalog.all.forEach { e ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(Texts.exercise(e.id))
                            Text(
                                e.muscles.filterValues { it >= 1.0 }.keys.joinToString("、") { Texts.muscle(it) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSelect(e.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    modifier: Modifier,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }.take(6)) },
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

private fun entryLabel(e: ExerciseEntry): String =
    "${Texts.exercise(e.exerciseId)}  ${e.sets} × ${e.reps}" + (e.weightKg?.let { " @ ${Texts.f1(it)} kg" } ?: "（自重）")

@Composable
private fun WeeklySetsCard(muscles: MuscleReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("最近 7 天每个肌群的有效组数", style = MaterialTheme.typography.titleMedium)
            Text(
                "主要发力肌群算 1 组、协同肌群算 0.5 组。增肌一般建议每个肌群每周约 10–20 组。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            muscles.statuses.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Texts.muscle(s.muscle), Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { (s.weeklyHardSets / StrengthLoad.EFFECTIVE_SETS_HIGH).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${Texts.f1(s.weeklyHardSets)} 组 · ${Texts.hardSetsRange(s.weeklyHardSets)}",
                        Modifier.width(112.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SorenessCard(today: Map<MuscleGroup, Int>, enabled: Boolean, onSoreness: (MuscleGroup, Int?) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("今天的酸痛感（可选）", style = MaterialTheme.typography.titleMedium)
            Text(
                "0 = 完全不酸，10 = 非常酸痛。用来校准肌肉恢复估算：最近的自评会直接影响今天的估算，" +
                    "积累几次后还会调整你个人的恢复速度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MuscleGroup.entries.forEach { m ->
                SorenessRow(m, today[m], enabled) { onSoreness(m, it) }
            }
        }
    }
}

@Composable
private fun SorenessRow(muscle: MuscleGroup, value: Int?, enabled: Boolean, onChange: (Int?) -> Unit) {
    var slider by remember(value) { mutableFloatStateOf((value ?: 0).toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(84.dp)) {
            Text(Texts.muscle(muscle), style = MaterialTheme.typography.bodyMedium)
            Texts.muscleHint(muscle)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Slider(
            value = slider,
            onValueChange = { slider = it },
            onValueChangeFinished = { onChange(slider.roundToInt()) },
            valueRange = 0f..10f,
            steps = 9,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (value == null) "未填" else "$value",
            Modifier.width(32.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (value != null && enabled) {
            TextButton(onClick = { onChange(null) }) { Text("清除") }
        }
    }
}

@Composable
private fun RecentCard(recent: List<StrengthSession>, canDelete: Boolean, onDelete: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近 30 天的力量训练", style = MaterialTheme.typography.titleMedium)
            if (recent.isEmpty()) {
                Text("还没有记录。", style = MaterialTheme.typography.bodyMedium)
            }
            recent.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${dateLabel(s.date)} · ${s.durationMinutes.roundToInt()} 分钟 · RPE ${s.sessionRpe}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            "负荷 ${StrengthLoad.fosterLoad(s).roundToInt()} AU（RPE × 分钟）≈ TRIMP ${StrengthLoad.trimp(s).roundToInt()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        s.exercises.forEach { Text(entryLabel(it), style = MaterialTheme.typography.bodySmall) }
                    }
                    if (canDelete) TextButton(onClick = { onDelete(s.id) }) { Text("删除") }
                }
            }
        }
    }
}

private fun dateLabel(date: LocalDate): String = when (date) {
    LocalDate.now() -> "今天"
    LocalDate.now().minusDays(1) -> "昨天"
    else -> date.format(DateTimeFormatter.ofPattern("M月d日")) + " " + Texts.weekday(date.dayOfWeek)
}
