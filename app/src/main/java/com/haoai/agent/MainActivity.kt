package com.haoai.agent

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.haoai.agent.platform.KeepAliveService
import com.haoai.agent.ui.ChatViewModel
import com.haoai.agent.ui.SettingsViewModel
import com.haoai.agent.ui.chat.ChatScreen
import com.haoai.agent.ui.settings.SettingsScreen
import com.haoai.agent.ui.theme.HaoTheme

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingRuntimeCb: ((Map<String, Boolean>) -> Unit)? = null
    private var pendingResultCb: ((android.content.Intent?) -> Unit)? = null

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pendingRuntimeCb?.invoke(result)
            pendingRuntimeCb = null
        }

    private val activityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            pendingResultCb?.invoke(if (r.resultCode == RESULT_OK) r.data else null)
            pendingResultCb = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationIfNeeded()
        com.haoai.agent.platform.PermissionBridge.requestRuntime = { perms, cb ->
            runOnUiThread {
                pendingRuntimeCb = cb
                runtimePermissionLauncher.launch(perms.toTypedArray())
            }
        }
        com.haoai.agent.platform.PermissionBridge.startForResult = { intent, cb ->
            runOnUiThread {
                pendingResultCb = cb
                activityLauncher.launch(intent)
            }
        }
        setContent {
            val app = applicationContext as HaoApplication
            val settings by app.container.settingsFlow.collectAsState()
            val dark = when (settings.themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            HaoTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RootApp()
                }
            }
        }
    }

    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun RootApp() {
    val app = LocalContext.current.applicationContext as HaoApplication
    val container = app.container
    val context = LocalContext.current

    val chatVm: ChatViewModel = viewModel(factory = viewModelFactory {
        initializer { ChatViewModel(container) }
    })
    val settingsVm: SettingsViewModel = viewModel(factory = viewModelFactory {
        initializer { SettingsViewModel(container) }
    })

    LaunchedEffect(Unit) {
        if (container.settingsFlow.value.keepAlive) {
            KeepAliveService.start(context)
        }
        com.haoai.agent.platform.DreamTriggerMonitor.init(context)
    }

    // 全局共享：壁纸背景 + 液态玻璃采样层（所有页面同一块玻璃语言）
    val wallpaper = androidx.compose.runtime.remember {
        com.haoai.agent.platform.WallpaperStore.loadBitmap(context)
    }
    val backdrop = com.haoai.agent.ui.common.rememberAppBackdrop(wallpaper)

    var screen by rememberSaveable { mutableIntStateOf(0) }
    val settings by container.settingsFlow.collectAsState()

    if (!settings.onboarded) {
        OnboardingDialog(
            onSave = { name, soul -> chatVm.completeOnboarding(name, soul) }
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.35f))
            )
        }
        when (screen) {
            1 -> SettingsScreen(
                vm = settingsVm,
                backdrop = backdrop,
                onBack = { screen = 0 },
                onOpenMemories = { screen = 2 },
                onOpenSchedules = { screen = 3 },
                onOpenSkills = { screen = 5 }
            )
            2 -> com.haoai.agent.ui.manage.MemoryScreen(backdrop = backdrop, onBack = { screen = 1 })
            3 -> com.haoai.agent.ui.manage.ScheduleScreen(backdrop = backdrop, onBack = { screen = 1 })
            5 -> com.haoai.agent.ui.manage.SkillsScreen(backdrop = backdrop, onBack = { screen = 1 })
            else -> ChatScreen(vm = chatVm, backdrop = backdrop, onOpenSettings = { screen = 1 })
        }
    }
}

@Composable
private fun OnboardingDialog(onSave: (String, String) -> Unit) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var soul by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var step by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { },
        title = {
            androidx.compose.material3.Text(
                if (step == 0) "见面礼：给我起个名字"
                else "你想让我是什么性格？"
            )
        },
        text = {
            androidx.compose.foundation.layout.Column {
                if (step == 0) {
                    androidx.compose.material3.Text("我是你的手机智能助理。你想叫我什么？（由你来定，我不给自己起名）")
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { androidx.compose.material3.Text("名字") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    androidx.compose.material3.Text("用一句话形容你希望我的做事风格（可跳过）。之后可在设置里修改。")
                    androidx.compose.material3.OutlinedTextField(
                        value = soul,
                        onValueChange = { soul = it },
                        label = { androidx.compose.material3.Text("性格 / 风格，如：简洁高效，少废话") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    if (step == 0) {
                        if (name.isNotBlank()) step = 1
                    } else {
                        onSave(name, soul)
                    }
                },
                enabled = step == 1 || name.isNotBlank()
            ) {
                androidx.compose.material3.Text(if (step == 0) "下一步" else "开始使用")
            }
        },
        dismissButton = if (step == 1) {
            {
                androidx.compose.material3.TextButton(onClick = { onSave(name, "") }) {
                    androidx.compose.material3.Text("跳过")
                }
            }
        } else null
    )
}
