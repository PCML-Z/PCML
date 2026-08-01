package com.lash.pmcl

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.ui.MainScreen
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
                MainScreen(
                    core = core,
                    appVersion = BuildConfig.VERSION_NAME,
                )
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
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::core.isInitialized) {
            core.shutdown()
        }
    }
}
