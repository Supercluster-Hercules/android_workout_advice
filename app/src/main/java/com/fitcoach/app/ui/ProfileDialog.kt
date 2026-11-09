package com.fitcoach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fitcoach.engine.Sex
import com.fitcoach.engine.UserProfile

private val AGE_RANGE = 10..100
private val MAX_HR_RANGE = 100..230

@Composable
fun ProfileDialog(
    initial: UserProfile,
    onSave: (UserProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var ageText by remember { mutableStateOf(initial.age?.toString() ?: "") }
    var sex by remember { mutableStateOf(initial.sex) }
    var maxHrText by remember { mutableStateOf(initial.maxHeartRate?.toString() ?: "") }

    val age = ageText.toIntOrNull()
    val ageValid = age != null && age in AGE_RANGE
    val maxHr = maxHrText.toIntOrNull()
    val maxHrValid = maxHrText.isBlank() || (maxHr != null && maxHr in MAX_HR_RANGE)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("个人资料") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "年龄和性别会用来估算最大心率、计算训练负荷，并调整加量幅度和恢复要求。资料只保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = ageText,
                    onValueChange = { ageText = it.filter(Char::isDigit).take(3) },
                    label = { Text("年龄") },
                    suffix = { Text("岁") },
                    isError = ageText.isNotEmpty() && !ageValid,
                    supportingText = if (ageText.isNotEmpty() && !ageValid) {
                        { Text("请输入 ${AGE_RANGE.first}–${AGE_RANGE.last}") }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("性别", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sex == Sex.MALE, onClick = { sex = Sex.MALE }, label = { Text("男") })
                    FilterChip(selected = sex == Sex.FEMALE, onClick = { sex = Sex.FEMALE }, label = { Text("女") })
                    FilterChip(selected = sex == null, onClick = { sex = null }, label = { Text("不填写") })
                }
                OutlinedTextField(
                    value = maxHrText,
                    onValueChange = { maxHrText = it.filter(Char::isDigit).take(3) },
                    label = { Text("实测最大心率（可选）") },
                    suffix = { Text("bpm") },
                    isError = !maxHrValid,
                    supportingText = {
                        Text(
                            if (maxHrValid) "不知道就留空，会按年龄和性别估算"
                            else "请输入 ${MAX_HR_RANGE.first}–${MAX_HR_RANGE.last}",
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ageValid && maxHrValid,
                onClick = { onSave(UserProfile(age = age, sex = sex, maxHeartRate = maxHr)) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
