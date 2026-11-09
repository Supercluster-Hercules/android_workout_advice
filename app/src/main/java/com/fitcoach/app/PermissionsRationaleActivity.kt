package com.fitcoach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Health Connect 在权限页点击"隐私政策"时打开的页面。
 * 上架 Google Play 前需要换成正式的隐私政策（Play 要求健康数据类应用提交声明）。
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("我们如何使用你的健康数据", style = MaterialTheme.typography.headlineSmall)
                        Text("本应用只读取最近 28 天的步数、运动记录、心率、静息心率、心率变异性和睡眠数据。")
                        Text("这些数据只在你的手机上计算训练负荷与恢复情况，用来生成运动建议，不会上传到任何服务器，也不会分享给第三方。")
                        Text("你可以随时在 Health Connect 设置中撤销授权。")
                    }
                }
            }
        }
    }
}
