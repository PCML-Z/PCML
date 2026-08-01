package com.lash.pmcl

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.ui.MainScreen
import com.lash.pmcl.ui.screens.AgreementGateScreen
import com.lash.pmcl.ui.screens.ServersScreenBridge
import com.lash.pmcl.ui.screens.WelcomeScreen
import com.lash.pmcl.ui.theme.PmclTheme
import java.nio.file.Path

class MainActivity : ComponentActivity() {

    private lateinit var core: LauncherCore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initCore()
        enableEdgeToEdge()
        setContent {
            PmclTheme(
                darkTheme = core.preferences.isUseDarkTheme(),
                dynamicColor = core.preferences.isDynamicColor(),
            ) {
                // 首启流程: 0=协议门控 1=欢迎页 2=主界面
                var bootStage by remember { mutableIntStateOf(getInitialBootStage()) }

                when (bootStage) {
                    0 -> AgreementGateScreen(onAgreed = {
                        core.preferences.setAgreementAccepted(true)
                        bootStage = 1
                    })
                    1 -> WelcomeScreen(onContinue = {
                        core.preferences.setFirstLaunchCompleted(true)
                        bootStage = 2
                    })
                    else -> MainScreen(
                        core = core,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                }
            }
        }
    }

    private fun getInitialBootStage(): Int {
        return when {
            !core.preferences.isAgreementAccepted() -> 0
            !core.preferences.isFirstLaunchCompleted() -> 1
            else -> 2
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
        // 注入 paths 与 preferences，供 ServersScreen 在不变签名时做持久化与直连设置
        ServersScreenBridge.init(core.paths, core.preferences)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::core.isInitialized) {
            core.shutdown()
        }
    }
}
