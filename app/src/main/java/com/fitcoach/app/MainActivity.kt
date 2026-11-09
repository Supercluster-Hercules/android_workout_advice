package com.fitcoach.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitcoach.app.ui.MainActions
import com.fitcoach.app.ui.MainScreen
import com.fitcoach.app.ui.MainViewModel
import com.fitcoach.app.ui.ProfileDialog

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val profile by viewModel.profile.collectAsStateWithLifecycle()
            val showProfileDialog by viewModel.showProfileDialog.collectAsStateWithLifecycle()
            val morningNotification by viewModel.morningNotification.collectAsStateWithLifecycle()
            val backgroundReadSupported = remember { viewModel.healthConnect.backgroundReadSupported() }

            val permissionLauncher = rememberLauncherForActivityResult(
                PermissionController.createRequestPermissionResultContract(),
            ) { viewModel.refresh() } // 权限变化时同步逻辑会自动改为完整导入

            val notificationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> viewModel.setMorningNotification(granted) }

            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    MainScreen(
                        state = state,
                        profile = profile,
                        morningNotification = morningNotification,
                        backgroundReadSupported = backgroundReadSupported,
                        actions = MainActions(
                            onRequestPermissions = {
                                permissionLauncher.launch(viewModel.healthConnect.requestablePermissions())
                            },
                            onInstallHealthConnect = ::openHealthConnectInStore,
                            onRefresh = { viewModel.refresh() },
                            onFullResync = { viewModel.refresh(forceFull = true) },
                            onDemo = viewModel::loadDemo,
                            onEditProfile = viewModel::openProfileDialog,
                            onWellness = viewModel::setWellness,
                            onRpe = viewModel::setRpe,
                            onToggleNotification = { enabled ->
                                if (enabled && needsNotificationPermission()) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.setMorningNotification(enabled)
                                }
                            },
                        ),
                    )
                    if (showProfileDialog) {
                        ProfileDialog(
                            initial = profile,
                            onSave = viewModel::saveProfile,
                            onDismiss = viewModel::dismissProfileDialog,
                        )
                    }
                }
            }
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun openHealthConnectInStore() {
        val uri = Uri.parse(
            "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding",
        )
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
        }
    }
}
