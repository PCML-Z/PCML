package com.lash.pmcl

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lash.pmcl.core.auth.AuthService
import com.lash.pmcl.core.auth.TokenEncryptor
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.launch.LaunchManager
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.preferences.Preferences
import com.lash.pmcl.core.version.VersionManager
import com.lash.pmcl.ui.MainScreen
import com.lash.pmcl.ui.theme.PmclTheme
import java.nio.file.Path

class MainActivity : ComponentActivity() {

    private lateinit var paths: PmclPaths
    private lateinit var preferences: Preferences
    private lateinit var downloadManager: DownloadManager
    private lateinit var versionManager: VersionManager
    private lateinit var authService: AuthService
    private lateinit var launchManager: LaunchManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initCore()
        enableEdgeToEdge()
        setContent {
            PmclTheme(
                darkTheme = preferences.isUseDarkTheme(),
                dynamicColor = preferences.isDynamicColor(),
            ) {
                MainScreen(
                    versionManager = versionManager,
                    downloadManager = downloadManager,
                    authService = authService,
                    launchManager = launchManager,
                    preferences = preferences,
                    appVersion = BuildConfig.VERSION_NAME,
                )
            }
        }
    }

    private fun initCore() {
        val rootPath: Path = filesDir.toPath().resolve("pmcl")
        paths = PmclPaths.fromRoot(rootPath)
        preferences = Preferences(paths)
        downloadManager = DownloadManager(workDir = paths.minecraftWorkDir)
        versionManager = VersionManager(paths = paths)
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val tokenEncryptor = TokenEncryptor(paths, androidId, filesDir.toPath())
        authService = AuthService(paths, tokenEncryptor)
        launchManager = LaunchManager(paths, preferences)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::downloadManager.isInitialized) {
            downloadManager.shutdown()
        }
        if (::authService.isInitialized) {
            authService.shutdown()
        }
        if (::launchManager.isInitialized) {
            launchManager.shutdownAll()
        }
        if (::preferences.isInitialized) {
            preferences.shutdown()
        }
    }
}
