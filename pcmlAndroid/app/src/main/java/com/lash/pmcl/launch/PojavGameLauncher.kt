package com.lash.pmcl.launch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.lash.pmcl.core.launch.GameLauncher
import com.lash.pmcl.core.launch.GameProcess
import com.lash.pmcl.core.launch.LaunchProfile
import java.io.IOException

/**
 * PojavLauncher Intent 启动器。
 *
 * 将启动参数通过 Intent 传递给 PojavLauncher/Amethyst，
 * 由 PojavLauncher 自行读取版本 JSON 并启动 JVM。
 *
 * 包名：net.kdt.pojavlaunch / net.kdt.pojavlaunch.debug
 */
class PojavGameLauncher(private val context: Context) : GameLauncher {

    companion object {
        private val POJAV_PACKAGES = arrayOf(
            "net.kdt.pojavlaunch",
            "net.kdt.pojavlaunch.debug",
        )
    }

    override fun checkAvailability(): String? {
        val pm = context.packageManager
        for (pkg in POJAV_PACKAGES) {
            try { pm.getPackageInfo(pkg, 0); return null }
            catch (_: PackageManager.NameNotFoundException) {}
        }
        return "未安装 PojavLauncher，请先安装"
    }

    @Throws(IOException::class)
    override fun launch(
        profile: LaunchProfile,
        javaExecutable: String,
        onLog: java.util.function.Consumer<String>?
    ): GameProcess {
        val pm = context.packageManager
        val pojaPackage = POJAV_PACKAGES.firstOrNull {
            try { pm.getPackageInfo(it, 0); true } catch (_: Exception) { false }
        } ?: throw IOException("PojavLauncher 未安装")

        onLog?.accept("[PMCL] 通过 PojavLauncher 启动 ${profile.versionId}")

        // 注意: 不拆分命令行。PojavLauncher 自行从版本 JSON 构建 JVM 参数。
        // 我们只需要告诉它版本、目录和账号。
        val account = profile.account

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(pojaPackage, "$pojaPackage.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                     or Intent.FLAG_ACTIVITY_CLEAR_TOP)

            // 版本
            putExtra("version", profile.versionId)
            // 游戏目录（包含 versions/ libraries/ assets/）
            putExtra("gameDir", profile.gameDir.toAbsolutePath().toString())

            // 账号
            if (account != null) {
                putExtra("userName", account.username ?: "Player")
                putExtra("uuid", account.uuid ?: "00000000-0000-0000-0000-000000000000")
                when (account.type?.name) {
                    "MICROSOFT" -> {
                        putExtra("userType", "msa")
                        putExtra("accessToken", account.accessToken ?: "")
                    }
                    else -> {
                        putExtra("userType", "mojang")
                        putExtra("session", account.accessToken ?: "-")
                    }
                }
            } else {
                putExtra("userName", "Player")
                putExtra("uuid", "00000000-0000-0000-0000-000000000000")
                putExtra("userType", "mojang")
                putExtra("session", "-")
            }
        }

        onLog?.accept("[PMCL] 正在唤起 PojavLauncher...")
        try { context.startActivity(intent) }
        catch (e: Exception) {
            throw IOException("无法启动 PojavLauncher: ${e.message}", e)
        }

        return PojavGameProcess(context, profile.versionId, onLog)
    }
}
