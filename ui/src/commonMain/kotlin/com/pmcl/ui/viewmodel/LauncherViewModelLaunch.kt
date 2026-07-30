package com.pmcl.ui.viewmodel

import com.pmcl.core.i18n.I18n
import com.pmcl.core.instance.InstanceInfo
import com.pmcl.core.launch.GameLogger
import com.pmcl.core.launch.ExternalLauncherDetector
import com.pmcl.core.launch.JavaRuntimeFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update

/**
 * M29 拆分：启动游戏 / 预判预热 / 多实例日志域。
 *
 * 状态字段保留在 LauncherViewModel（@PublishedApi internal），
 * UI 调用签名不变（需 import 扩展函数）。
 */

// ============ 启动游戏 ============

// ===== 预判启动：进入启动页时预测最可能的版本并后台预热资源 =====

/**
 * 触发预判启动：用 LaunchPredictor 预测最可能的版本，若置信度达标则后台预热资源。
 *
 * 预热内容（不启动 MC 进程，避免窗口提前弹出）：
 * 1. 构建完整 LaunchProfile（含 verifyLibraries 的全量文件校验，这是启动时最耗时的 IO）
 * 2. 解析 Java 可执行文件路径
 * 3. 启动 `java -version` 子进程预热 JVM 页缓存（OS 会缓存 java 可执行文件和依赖库）
 *
 * 调用时机：用户切换到 LaunchPage 时（见 LaunchPage.LaunchedEffect）。
 *
 * 安全保证：
 * - 未开启 predictiveLaunch 偏好时不执行
 * - 已有运行中实例时不执行
 * - 已有预热 profile 时不重复预热
 * - 预热失败不影响正常启动（用户点启动时走原 launch 流程）
 */
fun LauncherViewModel.predictAndPreheat() {
    if (!preferences.isPredictiveLaunch()) return
    if (_gameRunning.value || _runningInstances.value.isNotEmpty()) return
    if (preheatedProfile != null) return  // 已有预热
    if (_account.value == null) return     // 无账号无法构建 profile
    if (preheatJob?.isActive == true) return

    val gen = preheatGeneration.incrementAndGet()
    preheatJob = scope.launch {
        try {
            // 1. 收集本地已安装版本 ID 作为候选集
            val installedIds = _localVersionInfos.value.mapNotNull { it.getId() }.toSet()
            if (installedIds.isEmpty()) return@launch

            // 2. 预测
            val predictor = com.pmcl.core.launch.LaunchPredictor(
                core.playTimeTracker(), preferences
            )
            val result = withContext(Dispatchers.IO) { predictor.predict(installedIds) }
            if (gen != preheatGeneration.get()) return@launch
            if (!result.shouldPreheat()) {
                _predictiveState.value = LauncherViewModel.PredictiveState.Idle
                return@launch
            }
            val versionId = result.topVersionId ?: return@launch
            val account = _account.value ?: return@launch

            _predictiveState.value = LauncherViewModel.PredictiveState.Preheating(versionId, result.confidence)
            // 预热全程静默：不更新 _status，避免在 UI 暴露预加载信息

            // 3. 构造 LaunchProfile（这是启动时最耗时的阶段，含 verifyLibraries 全量文件校验）
            // 版本 JSON 读取失败不得静默当成 0（会选错运行时）
            val requiredJavaVer = withContext(Dispatchers.IO) {
                core.profileBuilder().getRequiredJavaVersion(versionId)
            }
            if (gen != preheatGeneration.get()) return@launch
            val javaExe = resolveJavaExe(versionId, requiredJavaVer)
            if (javaExe.isEmpty()) {
                _predictiveState.value = LauncherViewModel.PredictiveState.Failed("无法解析 Java 路径")
                return@launch
            }
            // 必须与正式启动同样传入 Java 主版本/架构，否则兼容层（PmclBootstrap/RetroWrapper）被跳过
            val javaMajor = withContext(Dispatchers.IO) {
                JavaRuntimeFinder.getMajorVersion(javaExe) ?: 0
            }
            val javaArch = withContext(Dispatchers.IO) {
                JavaRuntimeFinder.getArchitecture(javaExe)
            }
            if (gen != preheatGeneration.get()) return@launch
            val launchProfile = try {
                withContext(Dispatchers.IO) {
                    core.profileBuilder().build(versionId, account, javaMajor, javaArch)
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _predictiveState.value = LauncherViewModel.PredictiveState.Failed("构建启动配置失败：${e.message}")
                return@launch
            }
            if (gen != preheatGeneration.get()) return@launch

            // 4. JVM 页缓存预热（启动 `java -version` 子进程，立即退出但触发 OS 缓存）
            withContext(Dispatchers.IO) {
                core.launch().prewarmJvm(javaExe)
            }
            if (gen != preheatGeneration.get()) return@launch

            // 5. 缓存预热结果（代数校验：离开页面后禁止晚到写入）
            preheatedProfile = launchProfile
            preheatedJavaExe = javaExe
            preheatedJavaMajor = javaMajor
            preheatedVersionId = versionId
            preheatedAccountUuid = account.uuid
            preheatedAccessToken = account.accessToken.orEmpty()
            _predictiveState.value = LauncherViewModel.PredictiveState.Ready(versionId, result.confidence)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (gen == preheatGeneration.get()) {
                _predictiveState.value = LauncherViewModel.PredictiveState.Failed(e.message ?: "未知错误")
            }
        }
    }
}

/**
 * Java 路径解析辅助（与 launch() 内的逻辑保持一致）。
 * 版本/全局自定义路径若不满足 requiredJavaVer 最低要求则忽略，回退自动检测。
 */
@PublishedApi
internal suspend fun LauncherViewModel.resolveJavaExe(versionId: String, requiredJavaVer: Int): String {
    return withContext(Dispatchers.IO) {
        val versionPath = preferences.getVersionJavaPath(versionId)
        if (versionPath.isNotEmpty()) {
            if (JavaRuntimeFinder.meetsRequirement(versionPath, requiredJavaVer)) {
                return@withContext versionPath
            }
            System.err.println("[PMCL] 版本 Java 路径不满足要求（需要 Java "
                    + requiredJavaVer + "+），已忽略: $versionPath")
        }
        val globalPath = preferences.getJavaPath()
        if (globalPath.isNotEmpty()) {
            if (JavaRuntimeFinder.meetsRequirement(globalPath, requiredJavaVer)) {
                return@withContext globalPath
            }
            System.err.println("[PMCL] 全局 Java 路径不满足要求（需要 Java "
                    + requiredJavaVer + "+），已忽略: $globalPath")
        }
        try {
            val preferTranslation = preferences.preferLegacyTranslation()
                    && requiredJavaVer in 1..10
                    && com.pmcl.core.launch.RetroWrapperSupport.isTranslationEligible(versionId)
            JavaRuntimeFinder.findJavaExecutable(
                config.getRuntimesDir(), requiredJavaVer, preferTranslation
            ) ?: ""
        } catch (e: Throwable) { "" }
    }
}

/**
 * 取消预判启动：清空预存的 profile（用户离开启动页时调用）。
 * 不需要杀进程，因为预热阶段没有启动 MC 进程。
 */
fun LauncherViewModel.cancelPreheat() {
    preheatGeneration.incrementAndGet()
    preheatJob?.cancel()
    preheatJob = null
    val hadWork = preheatedProfile != null ||
            _predictiveState.value is LauncherViewModel.PredictiveState.Preheating ||
            _predictiveState.value is LauncherViewModel.PredictiveState.Ready
    preheatedProfile = null
    preheatedJavaExe = ""
    preheatedJavaMajor = 0
    preheatedVersionId = ""
    preheatedAccountUuid = ""
    preheatedAccessToken = ""
    if (hadWork) {
        _predictiveState.value = LauncherViewModel.PredictiveState.Aborted
        // 静默取消：不更新 _status，避免在 UI 暴露预加载信息
    }
    // 不立刻重置 Idle，让 UI 有机会显示 Aborted；下次 predictAndPreheat 会重置
}

/** 账号切换后作废预热 profile（避免用旧 token 启动） */
fun LauncherViewModel.invalidatePreheatForAccountChange() {
    if (preheatedProfile != null ||
        _predictiveState.value is LauncherViewModel.PredictiveState.Preheating ||
        _predictiveState.value is LauncherViewModel.PredictiveState.Ready
    ) {
        cancelPreheat()
    }
}

/**
 * 尝试采用预热的 LaunchProfile：若用户启动的版本与预热版本一致，
 * 返回预存的 (profile, javaExe) 跳过 build() 阶段；否则清空预热并返回 null。
 *
 * @param versionId 用户实际启动的版本 ID
 * @param javaMajorVer 实际用于启动的 Java 主版本（须与预热时一致，否则兼容层参数会错）
 * @return 采用成功时返回 Pair(profile, javaExe)，不匹配或无预热时返回 null
 */
@PublishedApi
internal fun LauncherViewModel.tryAdoptPreheated(
    versionId: String,
    javaMajorVer: Int
): Pair<com.pmcl.core.launch.LaunchProfile, String>? {
    val profile = preheatedProfile ?: return null
    val preheatedVer = preheatedVersionId
    val current = _launchAccountOverride ?: _account.value
    val currentUuid = current?.uuid.orEmpty()
    val currentToken = current?.accessToken.orEmpty()
    val javaMismatch = preheatedJavaMajor > 0 && javaMajorVer > 0 && preheatedJavaMajor != javaMajorVer
    // 旧预热（javaMajor=0）可能跳过了 PmclBootstrap，Java 9+ 上不可复用
    val staleCompat = preheatedJavaMajor <= 0 && javaMajorVer >= 9
    // gameArgs 已固化 accessToken；刷新后必须重建，否则游戏收到 401
    val tokenMismatch = preheatedAccessToken.isEmpty() || preheatedAccessToken != currentToken
    if (preheatedVer != versionId
        || preheatedAccountUuid.isEmpty()
        || preheatedAccountUuid != currentUuid
        || tokenMismatch
        || javaMismatch
        || staleCompat
    ) {
        preheatedProfile = null
        preheatedJavaExe = ""
        preheatedJavaMajor = 0
        preheatedVersionId = ""
        preheatedAccountUuid = ""
        preheatedAccessToken = ""
        _predictiveState.value = LauncherViewModel.PredictiveState.Aborted
        return null
    }
    val javaExe = preheatedJavaExe
    preheatedProfile = null
    preheatedJavaExe = ""
    preheatedJavaMajor = 0
    preheatedVersionId = ""
    preheatedAccountUuid = ""
    preheatedAccessToken = ""
    _predictiveState.value = LauncherViewModel.PredictiveState.Adopted
    return Pair(profile, javaExe)
}

/**
 * 启动前确保在线账号令牌可用：
 * - 微软：本地临近过期，或远端校验 401/403 → refresh（避免 expiresAt 未到但会话已被吊销）
 * - Yggdrasil：先 validate 再 refresh
 */
@PublishedApi
internal suspend fun LauncherViewModel.ensureLaunchAccount(
    account: com.pmcl.core.auth.Account
): com.pmcl.core.auth.Account {
    return when (account.type) {
        com.pmcl.core.auth.Account.AccountType.MICROSOFT -> {
            if (account.msRefreshToken.isEmpty()) return account
            var mustRefresh = account.needsMicrosoftRefresh()
            if (!mustRefresh) {
                // expiresAt 仍有效时，向 minecraftservices 探测一次，捕获服务端提前吊销
                mustRefresh = try {
                    val valid = withContext(Dispatchers.IO) {
                        core.auth().isMicrosoftAccessTokenValid(account)
                    }
                    !valid
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // 网络抖动时不阻断启动，沿用本地 token
                    false
                }
            }
            if (!mustRefresh) return account
            _status.value = I18n.t("status.refreshing_microsoft_token")
            try {
                val refreshed = withContext(Dispatchers.IO) {
                    core.auth().refreshMicrosoftAccount(account)
                }
                upsertAccount(refreshed)
                if (_launchAccountOverride?.uuid == account.uuid) {
                    _launchAccountOverride = refreshed
                }
                // 预热 profile 已把旧 accessToken 写进 gameArgs，必须作废
                if (refreshed.accessToken != account.accessToken) {
                    invalidatePreheatForAccountChange()
                }
                refreshed
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw IllegalStateException(
                    I18n.t("status.microsoft_token_refresh_failed", e.message ?: I18n.t("common.unknown")), e
                )
            }
        }
        com.pmcl.core.auth.Account.AccountType.YGGDRASIL -> {
            val api = account.authServerUrl
            if (api.isEmpty()) return account
            val valid = withContext(Dispatchers.IO) {
                core.auth().yggdrasilValidate(api, account.accessToken, account.clientToken)
            }
            if (valid) return account
            _status.value = I18n.t("status.refreshing_yggdrasil_token")
            val newTok = withContext(Dispatchers.IO) {
                core.auth().yggdrasilRefresh(api, account.accessToken, account.clientToken)
            } ?: throw IllegalStateException(I18n.t("status.yggdrasil_token_refresh_failed"))
            val updated = account.withAccessToken(newTok)
            upsertAccount(updated)
            if (_launchAccountOverride?.uuid == account.uuid) {
                _launchAccountOverride = updated
            }
            updated
        }
        else -> account
    }
}

fun LauncherViewModel.launch() {
    val versionId = _selectedVersion.value ?: run {
        _status.value = I18n.t("status.version_select_first")
        clearLaunchInstanceContext()
        return
    }
    if ((_launchAccountOverride ?: _account.value) == null) {
        _status.value = I18n.t("status.login_first")
        clearLaunchInstanceContext()
        return
    }
    // Companion 占用启动槽时禁止桌面再开，避免双开 MC / UI 状态分叉
    if (isCompanionLaunchBusy()) {
        _status.value = I18n.t("status.launch_busy_companion")
        clearLaunchInstanceContext()
        return
    }
    // 准备阶段互斥：双击不会并行构建两套 profile；进程启动后即释放，允许多开
    if (!launchPreparing.compareAndSet(false, true)) {
        _status.value = I18n.t("status.launch_busy")
        return
    }
    // mod 冲突检测：仅警告，不阻断启动
    // （NeoForge 支持 jar-in-jar 内嵌依赖，Sinytra Connector 提供 fabric 兼容层，
    //   静态扫描无法检测这些，误报率高；真正的冲突游戏自己会崩并生成崩溃报告）
    val conflicts = _modConflicts.value
    if (conflicts != null && conflicts.hasIssues()) {
        appendGameLog("[警告] mod 冲突检测（仅供参考，不阻断启动）：")
        conflicts.getErrors().take(5).forEach {
            appendGameLog("  - $it")
        }
        if (conflicts.getErrors().size > 5) {
            appendGameLog("  …还有 ${conflicts.getErrors().size - 5} 条，见模组页")
        }
    }

    scope.launch {
        _status.value = I18n.t("status.building_launch_profile")
        var instanceId: String? = null
        var timeTracked = false
        // 本次启动关联的实例（非实例启动时为 null），用于退出后回写实例游玩统计
        var launchedInstance: InstanceInfo? = null
        // 启动流程计时：记录从点击启动到 MC 主菜单就绪的完整时间线，输出到 latest.log
        val tracer = com.pmcl.core.launch.LaunchTracer()
        tracer.mark("launch_start")
        try {
            var account = _launchAccountOverride ?: _account.value
                ?: throw IllegalStateException(I18n.t("status.login_first"))
            account = ensureLaunchAccount(account)
            // 先读取版本要求的 Java 版本，用于选择合适的 Java 运行时
            // alpha/beta/1.7- 无 javaVersion 字段时由 builder 返回 8；IO/解析失败不得吞成 0
            val requiredJavaVer = withContext(Dispatchers.IO) {
                core.profileBuilder().getRequiredJavaVersion(versionId)
            }
            var javaExe = resolveJavaExe(versionId, requiredJavaVer)
            if (javaExe.isEmpty()) {
                // 自动下载缺失的 Java 运行时，避免用户手动安装
                // 老版本（1.12.2-）且开启了转译模式 → 下载 Java 21（RetroWrapper 兼容层自动处理）
                // 老版本未开启转译 → 下载 Java 8（原生兼容）
                // 1.17–1.20.4（required 16/17）→ Java 17；1.20.5+ → Java 21
                val preferTranslation = preferences.preferLegacyTranslation()
                        && requiredJavaVer in 1..10
                        && com.pmcl.core.launch.RetroWrapperSupport.isTranslationEligible(versionId)
                val downloadVer = when {
                    requiredJavaVer in 1..10 && preferTranslation -> 21
                    requiredJavaVer in 1..10 -> 8
                    requiredJavaVer in 11..17 -> 17
                    else -> 21
                }
                val runtimeType = when (downloadVer) {
                    8 -> com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.JAVA_8
                    17 -> com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.JAVA_17
                    else -> com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.JAVA_21
                }
                _status.value = "未找到 Java 运行时，正在自动下载 Java $downloadVer…"
                setGameLogs(listOf("未找到 Java 运行时，正在自动下载 Java $downloadVer…"))
                try {
                    withContext(Dispatchers.IO) {
                        val entries = core.javaDownloader().listRuntimes(runtimeType).join()
                        if (entries.isNullOrEmpty()) {
                            throw RuntimeException("Mojang 清单未返回可用的 Java $downloadVer 运行时")
                        }
                        val entry = entries[0]
                        core.javaDownloader().install(runtimeType, entry) { msg ->
                            _status.value = msg
                        }.join()
                    }
                    // 重新查找 Java
                    javaExe = withContext(Dispatchers.IO) {
                        JavaRuntimeFinder.findJavaExecutable(
                            config.getRuntimesDir(), requiredJavaVer, preferTranslation
                        ) ?: ""
                    }
                } catch (e: Throwable) {
                    appendGameLog("Java $downloadVer 自动下载失败：${e.message}")
                }
                if (javaExe.isEmpty()) {
                    _status.value = I18n.t("status.launch_failed_no_java")
                    setGameLogs(listOf(
                        "启动失败：未找到任何 Java 运行时",
                        "请安装 Java（推荐 Java 8 用于旧版本，Java 21 用于新版本）",
                        "下载地址：https://adoptium.net/temurin/releases/"
                    ))
                    return@launch
                }
                appendGameLog("Java $downloadVer 自动下载完成：$javaExe")
            }
            // 获取实际 Java 主版本号，用于条件注入 Java 16+ 专属参数（避免 Java 8 报错）
            val javaMajorVer = withContext(Dispatchers.IO) {
                JavaRuntimeFinder.getMajorVersion(javaExe)
            } ?: run {
                _status.value = I18n.t("status.java_version_detect_failed", javaExe)
                setGameLogs(listOf(I18n.t("status.java_version_detect_failed", javaExe)))
                return@launch
            }
            // 旧版本用 Java 9+ 启动时，PMCL 兼容层会自动处理 LaunchWrapper 的 URLClassLoader 问题
            // 不再硬性拦截，而是显示警告并继续启动（兼容层通过 -Djava.system.class.loader 解决）
            val usingCompatLayer = requiredJavaVer in 1..10 && javaMajorVer > 0 && javaMajorVer >= 9
            // 检测游戏 Java 的架构，用于让 native 库选择匹配架构的版本
            // 在 ARM64 系统上用 x86_64 Java 启动老版本时，此参数确保选择 x86_64 natives
            val javaArch = withContext(Dispatchers.IO) {
                JavaRuntimeFinder.getArchitecture(javaExe)
            }
            tracer.mark("java_resolved")
            // 龙芯平台兼容性检测：native 库可能不完整，提示用户
            if (JavaRuntimeFinder.isLoongson()) {
                val isLoongArch = JavaRuntimeFinder.isLoongArch64()
                val archName = if (isLoongArch) "LoongArch64" else "MIPS64el"
                val isOldVersion = requiredJavaVer in 1..10

                if (isOldVersion || !isLoongArch) {
                    // 旧版本（LWJGL 2.x）或 MIPS64el：无原生 native，需 x86_64 + 二进制翻译
                    _status.value = I18n.t("status.compat_hint_loongson", archName)
                    val options = mutableListOf<LauncherViewModel.CompatOption>()

                    // 选项1：仍尝试启动（可能因 native 库缺失而崩溃）
                    options.add(LauncherViewModel.CompatOption(
                        title = "仍尝试启动",
                        description = "龙芯 $archName 上旧版本 Minecraft 的 LWJGL 原生库可能缺失。\n" +
                                if (isLoongArch)
                                    "若已安装 LATX 二进制翻译 + x86_64 Java，native 库可通过翻译层运行。\n游戏可能崩溃，请知悉风险。"
                                else
                                    "MIPS64el 龙芯无 x86 二进制翻译能力，旧版本大概率无法运行。\n游戏可能崩溃，请知悉风险。",
                        action = { launchWithSpecificJava(versionId, javaExe, javaMajorVer, javaArch) }
                    ))

                    // 选项2：安装龙芯版 JDK（打开龙芯开源社区）
                    options.add(LauncherViewModel.CompatOption(
                        title = "前往龙芯开源社区下载 JDK",
                        description = "打开浏览器访问龙芯开源社区，下载 LoongArch64 版 JDK\n" +
                                "安装后 PMCL 会自动检测并使用",
                        action = {
                            try {
                                val url = "https://www.loongnix.cn/zh/api/java/"
                                if (System.getProperty("os.name", "").lowercase().contains("linux")) {
                                    Runtime.getRuntime().exec(arrayOf("xdg-open", url))
                                } else {
                                    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                                }
                            } catch (e: Throwable) {
                                _status.value = I18n.t("status.cannot_open_browser", e.message ?: I18n.t("common.unknown"))
                            }
                        }
                    ))

                    _compatTitle.value = "龙芯 $archName 兼容性提示"
                    _compatOptions.value = options
                    return@launch
                }
            }

            // RISC-V 平台兼容性检测：native 库可能不完整，提示用户
            if (JavaRuntimeFinder.isRiscV()) {
                val isOldVersion = requiredJavaVer in 1..10

                if (isOldVersion) {
                    // 旧版本（LWJGL 2.x）：无原生 native，需 x86_64 + QEMU 用户态翻译
                    _status.value = I18n.t("status.compat_hint_riscv")
                    val options = mutableListOf<LauncherViewModel.CompatOption>()

                    // 选项1：仍尝试启动（可能因 native 库缺失而崩溃）
                    options.add(LauncherViewModel.CompatOption(
                        title = "仍尝试启动",
                        description = "RISC-V 64 上旧版本 Minecraft 的 LWJGL 2.x 原生库无 RISC-V 版本。\n" +
                                "若已安装 QEMU 用户态翻译 + x86_64 Java，native 库可通过翻译层运行。\n" +
                                "游戏可能崩溃或性能较差，请知悉风险。",
                        action = { launchWithSpecificJava(versionId, javaExe, javaMajorVer, javaArch) }
                    ))

                    // 选项2：安装 RISC-V 版 JDK（打开 Adoptium）
                    options.add(LauncherViewModel.CompatOption(
                        title = "前往 Adoptium 下载 RISC-V JDK",
                        description = "打开浏览器访问 Adoptium，下载 RISC-V 64 版 JDK\n" +
                                "安装后 PMCL 会自动检测并使用",
                        action = {
                            try {
                                val url = "https://adoptium.net/temurin/releases/?version=17&arch=riscv64"
                                if (System.getProperty("os.name", "").lowercase().contains("linux")) {
                                    Runtime.getRuntime().exec(arrayOf("xdg-open", url))
                                } else {
                                    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                                }
                            } catch (e: Throwable) {
                                _status.value = I18n.t("status.cannot_open_browser", e.message ?: I18n.t("common.unknown"))
                            }
                        }
                    ))

                    _compatTitle.value = "RISC-V 64 兼容性提示"
                    _compatOptions.value = options
                    return@launch
                }
            }

            // Apple Silicon：1.17 前官方 macOS natives 无 arm64（含 1.13–1.16 LWJGL3 x86）。
            // LWJGL2（~1.12）可走 RetroWrapper；1.13–1.16 必须用 Rosetta x86 Java。
            val translationEligible =
                com.pmcl.core.launch.RetroWrapperSupport.isTranslationEligible(versionId)
            val needsRosetta =
                com.pmcl.core.launch.RetroWrapperSupport.needsRosettaOnAppleSilicon(versionId)
            val isArm64Java = javaArch.contains("aarch64") || javaArch.contains("arm64")
            val isArchMismatch = needsRosetta
                    && isArm64Java
                    && System.getProperty("os.name", "").lowercase().contains("mac")
                    && JavaRuntimeFinder.isAppleSiliconMac()
            if (isArchMismatch) {
                val translationOn = preferences.preferLegacyTranslation()
                // RetroWrapper shouldApply：arm64 Java ≥9 即可；不必死等 17
                if (translationEligible && translationOn && javaMajorVer >= 9) {
                    // Classic～1.12 + AUTO/ON：RetroWrapper / FrankenLWJGL
                    _status.value = "转译运行：Java $javaMajorVer (arm64) + RetroWrapper"
                } else {
                _status.value = I18n.t("status.compat_issue_arch_mismatch")
                val externalLaunchers = withContext(Dispatchers.IO) {
                    ExternalLauncherDetector.detectLaunchers()
                }
                val externalJavas = withContext(Dispatchers.IO) {
                    ExternalLauncherDetector.detectExternalJavaRuntimes(true)
                }
                val x86Javas = externalJavas.filter {
                    (it.arch.contains("x86_64") || it.arch.contains("amd64"))
                            && it.majorVersion in 8..21
                }.sortedBy {
                    // 优先 Java 8，其次接近 required
                    when {
                        it.majorVersion == 8 -> 0
                        it.majorVersion == requiredJavaVer -> 1
                        else -> 2 + it.majorVersion
                    }
                }

                val options = mutableListOf<LauncherViewModel.CompatOption>()

                if (translationEligible) {
                    options.add(LauncherViewModel.CompatOption(
                        title = "转译运行（推荐：Java 21 + RetroWrapper）",
                        description = "使用开源 RetroWrapper / FrankenLWJGL，用当前 arm64 Java $javaMajorVer\n"
                                + "原生启动 Classic～1.12，无需安装 x86 Java 8（Rosetta）",
                        action = {
                            preferences.setLegacyTranslationMode("ON")
                            launchWithSpecificJava(versionId, javaExe, javaMajorVer, javaArch)
                        }
                    ))
                }

                if (x86Javas.isNotEmpty()) {
                    val java = x86Javas.first()
                    options.add(LauncherViewModel.CompatOption(
                        title = "使用 ${java.source} 的 x86_64 Java ${java.majorVersion} 启动（推荐）",
                        description = "路径: ${java.javaPath}\n"
                                + "1.13–1.16 的 LWJGL natives 仅为 x86_64，须通过 Rosetta 运行",
                        action = {
                            launchWithSpecificJava(versionId, java.javaPath, java.majorVersion, "x86_64")
                        }
                    ))
                }

                // 应用内下载 Mojang 清单中的 mac-os（x86_64）Java 8（Apple Silicon 强制 Rosetta）
                options.add(0, LauncherViewModel.CompatOption(
                    title = "自动下载 x86_64 Java 8（Rosetta）",
                    description = "通过 PMCL 下载官方清单中的 macOS x86_64 Java 8，\n"
                            + "并用现代 GLFW 修复后以 Rosetta 启动（适合 1.13–1.16）",
                    action = { downloadX86Java8AndLaunch(versionId) }
                ))

                for (launcher in externalLaunchers) {
                    options.add(LauncherViewModel.CompatOption(
                        title = "用 ${launcher.name} 启动",
                        description = "路径: ${launcher.executablePath}\n打开 ${launcher.name} 启动器，从中启动此版本",
                        action = { launchWithExternalLauncher(launcher, versionId) }
                    ))
                }

                options.add(LauncherViewModel.CompatOption(
                    title = "浏览器安装 x86_64 Java 8",
                    description = "打开浏览器下载 x86_64 版本的 Java 8\n安装后 PMCL 会自动检测并使用（Apple Silicon 需 Rosetta）",
                    action = {
                        try {
                            val url = "https://adoptium.net/temurin/releases/?version=8&arch=x64"
                            if (System.getProperty("os.name", "").lowercase().contains("mac")) {
                                Runtime.getRuntime().exec(arrayOf("open", url))
                            } else {
                                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                            }
                            _status.value = "已打开浏览器，请下载并安装 x86_64 Java 8"
                        } catch (e: Throwable) {
                            _status.value = I18n.t("status.cannot_open_browser", e.message ?: I18n.t("common.unknown"))
                        }
                    }
                ))

                _compatTitle.value = if (translationEligible) {
                    "兼容性：旧版本与 arm64 Java"
                } else {
                    "兼容性：1.13–1.16 需要 x86_64 Java（Rosetta）"
                }
                _compatOptions.value = options
                return@launch
                }
            }
            // 尝试采用预判启动的预热 profile：版本一致则复用预热的 profile，跳过 build 阶段
            // （build 内部含 verifyLibraries 全量文件校验，是最耗时的 IO 步骤）
            // 实例启动（_pendingInstanceDir != null）不采用预热，因为实例有独立的 gameDir/libraries
            val adopted = if (_pendingInstanceDir == null) {
                tryAdoptPreheated(versionId, javaMajorVer)
            } else null
            var profile = adopted?.first ?: withContext(Dispatchers.IO) {
                val instDir = _pendingInstanceDir
                val instInfo = _pendingInstanceInfo
                if (instDir != null && instInfo != null) {
                    // 实例启动：用基础版本的 JSON/jar/库，但 gameDir 指向实例目录
                    launchedInstance = instInfo
                    core.profileBuilder().buildInstance(
                        versionId, instDir, account, javaMajorVer, javaArch
                    )
                } else {
                    core.profileBuilder().build(versionId, account, javaMajorVer, javaArch)
                }
            }
            // 防御：Java 9+ 仍以 LaunchWrapper 为 JVM 主类（旧预热 javaMajor=0）→ 强制重建
            if (javaMajorVer >= 9
                && profile.mainClass?.contains("launchwrapper", ignoreCase = true) == true
            ) {
                profile = withContext(Dispatchers.IO) {
                    core.profileBuilder().build(versionId, account, javaMajorVer, javaArch)
                }
            }
            tracer.mark("profile_built")

            // 创建/复用 GameLogger 持久化日志（多实例：每个实例独立日志文件）
            instanceId = "${versionId}_${System.currentTimeMillis()}"
            val logFile = config.getWorkDir().resolve("logs").resolve("$instanceId.log")
            val instLogger = withContext(Dispatchers.IO) {
                try { GameLogger(logFile) } catch (e: Throwable) {
                    System.err.println("[VM] GameLogger 创建失败: ${e.message}")
                    null
                }
            }
            if (instLogger == null) {
                appendGameLog(I18n.t("status.game_log_create_failed", logFile.toString()))
            }
            instanceLoggers[instanceId] = instLogger
            gameLogger = instLogger

            // 初始化实例日志列表
            val bootstrapActive = profile.mainClass?.contains("PmclBootstrap") == true
            val initLogs = if (usingCompatLayer && bootstrapActive) {
                mutableListOf(
                    "[PMCL 兼容层] 检测到旧版本使用 Java $javaMajorVer 启动（推荐 Java ${requiredJavaVer}）",
                    "[PMCL 兼容层] 已通过 PmclBootstrap 入口类注入 URLClassLoader，解决 LaunchWrapper 兼容问题",
                    "[PMCL 兼容层] 已注入 --add-opens 参数，允许旧版本反射访问 Java 内部 API",
                    "[PMCL 兼容层] 如遇问题，请安装 Java 8 以获得最佳兼容性",
                    ""
                )
            } else mutableListOf()
            instanceLogs[instanceId] = initLogs
            setGameLogs(initLogs.toList())

            // 添加到运行中实例列表，设为活跃
            _runningInstances.update { list ->
                list.map { it.copy(active = false) } + LauncherViewModel.RunningInstance(
                    id = instanceId,
                    versionId = versionId,
                    accountName = account.username,
                    startTime = System.currentTimeMillis(),
                    active = true
                )
            }
            _gameRunning.value = true
            _status.value = I18n.t("status.launching", javaExe, javaMajorVer, javaArch, versionId) +
                    if (usingCompatLayer) I18n.t("status.compat_layer_suffix") else ""
            // Better MC 等大型整合包会在 Mojang 红屏停 1–3 分钟，进度条几乎不动——不是卡死
            val modsHintCount = try {
                profile.gameDir.resolve("mods").toFile().listFiles()
                    ?.count { it.isFile && it.name.endsWith(".jar", ignoreCase = true) } ?: 0
            } catch (_: Throwable) { 0 }
            if (modsHintCount >= 80) {
                appendGameLog(I18n.t("status.large_pack_splash_hint"))
                _status.value = I18n.t("status.large_pack_loading")
            }

            // 记录启动前的崩溃报告快照（用于退出后对比新增）
            val crashDirBefore = withContext(Dispatchers.IO) {
                try { core.crashAnalyzer().scanReports(config.getWorkDir()).map { it.getFile().toString() }.toSet() }
                catch (t: Throwable) { emptySet<String>() }
            }

            // 记录启动：最近使用列表 + 最后游玩时间戳 + 时长追踪
            val launchTime = System.currentTimeMillis()
            preferences.recordRecentVersion(versionId)
            preferences.setLastPlayedTime(versionId, launchTime)
            _recentVersions.value = preferences.getRecentVersions()
            _lastPlayedTimes.value = HashMap(preferences.getLastPlayedTimesRaw())
            // 携带实例 ID 和已安装模组列表，用于细分统计（按模组/按实例）
            val sessionModIds = _installedMods.value.mapNotNull { it.getModId().takeIf(String::isNotEmpty) }
            core.playTimeTracker().recordStart(versionId, instanceId ?: "", sessionModIds)
            timeTracked = true

            // launchAsync 返回 CompletableFuture，需等待进程退出，否则 gameRunning 会立即被 finally 重置
            // 预热仅预存 LaunchProfile（不启动 MC 进程），此处始终调用 launchAsync 启动真正的 MC 进程
            val future = core.launch().launchAsync(
                profile, javaExe,
                { line ->
                    // 同时写入实例日志和全局日志（如果该实例是活跃的）
                    instanceLogs[instanceId]?.let { logs ->
                        synchronized(logs) {
                            logs.add(line)
                            if (logs.size > 2000) logs.subList(0, logs.size - 2000).clear()
                        }
                    }
                    // 仅当此实例为活跃时增量追加 UI（避免每行全量重建）
                    if (_runningInstances.value.any { it.id == instanceId && it.active }) {
                        appendGameLog(line)
                    }
                    // 解析游戏日志，更新会话上下文（服务器地址 / 世界名）用于细分统计
                    try {
                        if (line.contains("Sound engine started") || line.contains("Game took")) {
                            _status.value = I18n.t("launch.game_running")
                        } else if (line.contains("Reloading ResourceManager")) {
                            _status.value = I18n.t("status.large_pack_loading")
                        }
                        if (line.contains("Connecting to")) {
                            // 例：[Render thread/INFO]: Connecting to mc.example.com, 25565
                            val m = Regex("""Connecting to\s+([^,\s]+)(?:[,\s]+(\d+))?""").find(line)
                            if (m != null) {
                                val host = m.groupValues[1]
                                val port = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
                                val server = if (port != null) "$host:$port" else host
                                core.playTimeTracker().updateSessionServer(versionId, server)
                            }
                        } else if (line.contains("Saving chunks for level")) {
                            // 例：Saving chunks for level 'worldName'/minecraft:overworld
                            val m = Regex("""Saving chunks for level '([^']+)'""").find(line)
                            if (m != null) {
                                core.playTimeTracker().updateSessionWorld(versionId, m.groupValues[1])
                            }
                        } else if (line.contains("Preparing spawn area") && !line.contains("Connecting to")) {
                            // 单人世界加载阶段，若尚未记录世界名，用 "单人世界" 占位
                            core.playTimeTracker().updateSessionWorld(versionId, "Singleplayer")
                        }
                    } catch (_: Throwable) { }
                },
                instLogger,
                tracer
            )
            // 进程已提交启动：释放准备锁，允许再开另一实例
            launchPreparing.set(false)
            // 使用可取消等待替代 future.join()（join 不可中断，协程取消时线程持续阻塞）
            val exitCode = awaitCancellableFuture(future)
            if (exitCode == com.pmcl.core.launch.LaunchManager.EXIT_CANCELLED) {
                _status.value = I18n.t("status.launch_cancelled")
                appendGameLog(I18n.t("status.launch_cancelled"))
                return@launch
            }
            _status.value = I18n.t("status.game_exited_with_version", exitCode, versionId)

            // 异常退出检测：非 0 退出码视为崩溃（用退出实例自身日志，勿用当前 UI 活跃缓冲）
            // EXIT_CANCELLED 已在上方处理，不得弹崩溃 UI
            if (exitCode != 0) {
                val recentLogs = instanceId?.let { id ->
                    instanceLogs[id]?.let { logs ->
                        synchronized(logs) { logs.takeLast(80).toList() }
                    }
                } ?: _gameLogs.value.takeLast(80).map { it.text }
                val report = withContext(Dispatchers.IO) {
                    try {
                        val after = core.crashAnalyzer().scanReports(config.getWorkDir())
                        val newReport = after.firstOrNull { it.getFile().toString() !in crashDirBefore }
                        if (newReport != null) {
                            newReport
                        } else {
                            val logText = recentLogs.joinToString("\n")
                            if (logText.isNotBlank()) core.crashAnalyzer().analyze(logText, null)
                            else null
                        }
                    } catch (t: kotlinx.coroutines.CancellationException) {
                        throw t
                    } catch (_: Throwable) { null }
                }
                _crashEvent.value = LauncherViewModel.CrashEvent(exitCode, report, recentLogs, versionId)
                _crashReports.value = withContext(Dispatchers.IO) {
                    try { core.crashAnalyzer().scanReports(config.getWorkDir()) }
                    catch (t: kotlinx.coroutines.CancellationException) { throw t }
                    catch (_: Throwable) { emptyList() }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.launch_failed", e.message ?: I18n.t("common.unknown"))
            appendGameLog("[错误] ${e.message}")
            instanceId?.let { id ->
                instanceLogs[id]?.let { logs ->
                    synchronized(logs) { logs.add("[错误] ${e.message}") }
                }
            }
        } finally {
            // 准备失败或提前 return 时也要释放；成功路径已在 launchAsync 后释放
            launchPreparing.set(false)
            // 确保 recordEnd 被调用：即使 launchAsync 抛异常也要记录时长
            if (timeTracked) {
                core.playTimeTracker().recordEnd(versionId)
            }
            // 回写实例级游玩统计：上次游玩时间 + 累计时长，持久化后刷新实例列表 UI
            // （必须在清除 _pendingInstanceInfo 与移除运行实例之前执行，才能读到启动时间）
            val finishedInstance = launchedInstance
            if (finishedInstance != null && timeTracked) {
                try {
                    val startedAt = instanceId?.let { id ->
                        _runningInstances.value.firstOrNull { it.id == id }?.startTime
                    }
                    val now = System.currentTimeMillis()
                    val sessionSeconds = if (startedAt != null)
                        ((now - startedAt) / 1000).coerceAtLeast(0) else 0L
                    finishedInstance.setLastPlayedAt(now)
                    finishedInstance.setTotalPlayTimeSeconds(
                        finishedInstance.getTotalPlayTimeSeconds() + sessionSeconds
                    )
                    withContext(Dispatchers.IO) { core.instances().updateInstance(finishedInstance) }
                    loadInstances()
                } catch (t: Throwable) {
                    System.err.println("[VM] 实例游玩时长回写失败: ${t.message}")
                    _status.value = I18n.t("status.instance_playtime_save_failed",
                        t.message ?: I18n.t("common.unknown"))
                }
            }
            // 清除实例启动上下文与单次账户覆盖
            clearLaunchInstanceContext()
            instanceId?.let { id ->
                // 从运行列表中移除此实例
                _runningInstances.update { list ->
                    val remaining = list.filter { it.id != id }
                    // 如果活跃实例退出了，将最后一个实例设为活跃
                    if (remaining.isNotEmpty() && !remaining.any { it.active }) {
                        remaining.mapIndexed { idx, inst ->
                            if (idx == remaining.lastIndex) inst.copy(active = true)
                            else inst
                        }
                    } else remaining
                }
                // 更新 UI 日志为新的活跃实例
                val activeInst = _runningInstances.value.firstOrNull { it.active }
                if (activeInst != null) {
                    setGameLogs(instanceLogs[activeInst.id]?.let { logs ->
                        synchronized(logs) { logs.toList() }
                    } ?: emptyList())
                }
                _gameRunning.value = _runningInstances.value.isNotEmpty()
                // 清理此实例的日志资源
                instanceLoggers.remove(id)?.close()
                instanceLogs.remove(id)
                gameLogger = instanceLoggers.values.lastOrNull()
            }
        }
    }
}

/**
 * 切换活跃实例（UI 日志面板显示该实例的日志）。
 */
fun LauncherViewModel.selectInstance(instanceId: String) {
    _runningInstances.update { list ->
        list.map { it.copy(active = it.id == instanceId) }
    }
    setGameLogs(instanceLogs[instanceId]?.let { logs ->
        synchronized(logs) { logs.toList() }
    } ?: emptyList())
}

/** 清空当前 UI 游戏日志（不删除磁盘日志文件） */
fun LauncherViewModel.clearGameLogs() {
    _gameLogs.value = emptyList()
    val activeId = _runningInstances.value.firstOrNull { it.active }?.id
    if (activeId != null) {
        instanceLogs[activeId]?.let { logs ->
            synchronized(logs) { logs.clear() }
        }
    }
}

/** 在系统文件管理器中打开游戏日志目录 */
fun LauncherViewModel.openGameLogFolder() {
    scope.launch {
        try {
            val dir = withContext(Dispatchers.IO) {
                val p = config.getWorkDir().resolve("logs")
                java.nio.file.Files.createDirectories(p)
                p.toFile()
            }
            withContext(Dispatchers.IO) {
                java.awt.Desktop.getDesktop().open(dir)
            }
        } catch (e: Throwable) {
            _status.value = I18n.t("log.open_folder_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

