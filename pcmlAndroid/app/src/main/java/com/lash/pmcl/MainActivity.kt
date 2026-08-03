package com.lash.pmcl

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.ui.MainScreen
import com.lash.pmcl.ui.page.LockscreenLaunchPage
import com.lash.pmcl.ui.page.QuickLaunchPage
import com.lash.pmcl.ui.theme.LocalThemeState
import com.lash.pmcl.ui.theme.PmclTheme
import com.lash.pmcl.ui.theme.ThemeState
import com.lash.pmcl.ui.screens.ServersScreenBridge
import com.lash.pmcl.ui.screens.AgreementGateScreen
import java.nio.file.Path

class MainActivity : ComponentActivity() {

    lateinit var core: LauncherCore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initCore()
        enableEdgeToEdge()
        setContent {
            val themeState = remember {
                ThemeState(initialDark = core.preferences.isUseDarkTheme())
            }

            LaunchedEffect(Unit) {
                themeState.applyThemePreset(core.preferences.getThemePreset())
                themeState.applyColorMode(core.preferences.getColorMode())
                themeState.applyUiScale(core.preferences.getUiScale())
                themeState.applyGlassTheme(core.preferences.isGlassTheme())
                themeState.applyLockscreenLaunchTheme(core.preferences.isLockscreenLaunchTheme())
            }

            PmclTheme(
                darkTheme = themeState.useDark,
                themeState = themeState,
            ) {
                // 全局系统栏 insets — 防止内容被状态栏/导航栏遮挡
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars)
                ) {
                    CompositionLocalProvider(LocalThemeState provides themeState) {
                        AppContent()
                    }
                }
            }
        }
    }

    @Composable
    private fun AppContent() {
        var agreementAccepted by remember { mutableStateOf(core.preferences.isAgreementAccepted()) }
        var showTestDialog by remember { mutableStateOf(BuildConfig.DEBUG) }
        var enteredMain by remember { mutableStateOf(false) }

        if (!agreementAccepted) {
            AgreementGateScreen(
                onAccept = {
                    core.preferences.setAgreementAccepted(true)
                    agreementAccepted = true
                },
                onDecline = { finish() }
            )
        } else if (showTestDialog) {
            val ctx = LocalContext.current
            AlertDialog(
                onDismissRequest = { showTestDialog = false },
                title = { Text("PMCL 移动端测试版 (${BuildConfig.VERSION_NAME})") },
                text = {
                    Column {
                        Text("这是 PMCL Minecraft 启动器的 Android 移动端测试版本。\n\n构建版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n功能持续完善中，可能存在不稳定的情况。")
                        Spacer(Modifier.height(8.dp))
                        Text("您的错误报告可能被上传至 lash.org.cn 用于改进。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/PMCL-Z"))
                            ctx.startActivity(i)
                        }) {
                            Text("前往 GitHub @PMCL-Z 团队获取支持",
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTestDialog = false }) {
                        Text("我知道了")
                    }
                }
            )
        } else if (enteredMain) {
            MainScreen(core = core, appVersion = BuildConfig.VERSION_NAME)
        } else {
            if (LocalThemeState.current.lockscreenLaunchTheme) {
                LockscreenLaunchPage(core = core, onEnterMain = { enteredMain = true })
            } else {
                QuickLaunchPage(core = core, onEnterMain = { enteredMain = true })
            }
        }
    }

    private fun initCore() {
        val rootPath: Path = filesDir.toPath().resolve("pmcl")
        val paths = PmclPaths.fromRoot(rootPath)
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        core = LauncherCore(
            paths = paths,
            androidId = androidId,
            appDataDir = filesDir.toPath(),
            appVersion = BuildConfig.VERSION_NAME,
        )
        core.applyLanguage(core.preferences.getLanguage())
        ServersScreenBridge.init(core.paths, core.preferences)
        core.launchManager.gameLauncher = com.lash.pmcl.launch.PojavGameLauncher(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::core.isInitialized) core.shutdown()
    }
}
