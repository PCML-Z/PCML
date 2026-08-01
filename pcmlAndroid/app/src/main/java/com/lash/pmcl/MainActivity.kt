package com.lash.pmcl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.version.VersionManager
import com.lash.pmcl.ui.MainScreen
import com.lash.pmcl.ui.theme.PmclTheme
import java.nio.file.Path

class MainActivity : ComponentActivity() {

    private lateinit var paths: PmclPaths
    private lateinit var downloadManager: DownloadManager
    private lateinit var versionManager: VersionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initCore()
        enableEdgeToEdge()
        setContent {
            PmclTheme {
                MainScreen(
                    versionManager = versionManager,
                    downloadManager = downloadManager,
                    appVersion = BuildConfig.VERSION_NAME,
                )
            }
        }
    }

    private fun initCore() {
        val rootPath: Path = filesDir.toPath().resolve("pmcl")
        paths = PmclPaths.fromRoot(rootPath)
        downloadManager = DownloadManager(workDir = paths.minecraftWorkDir)
        versionManager = VersionManager(paths = paths)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::downloadManager.isInitialized) {
            downloadManager.shutdown()
        }
    }
}
