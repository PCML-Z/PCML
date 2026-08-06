package com.lash.pmcl.core.auth

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * 账号认证 + 多账号管理 — Android 版。
 *
 * 多账号存储格式（{paths.accounts}）：
 * ```
 * {
 *   "selected": "uuid-of-current",
 *   "accounts": [
 *     { "uuid": "...", "username": "...", "accessToken": "...", "type": "OFFLINE|MICROSOFT" }
 *   ]
 * }
 * ```
 *
 * 与桌面版的差异：
 * - 路径由 [PmclPaths] 提供，不依赖 ~/.pmcl
 * - [TokenEncryptor] 改为实例化（依赖 Android Keystore / Android ID），不再是静态工具类
 * - 移除 GitHub 设备码登录支持，仅保留 OFFLINE + MICROSOFT + YGGDRASIL
 *   GitHub 登录可在后续按需迁移
 * - 保留 per-account 刷新锁、corruptedAccounts 提示、原子 tmp→rename 写入
 */
class AuthService(
    private val paths: PmclPaths,
    private val tokenEncryptor: TokenEncryptor
) {

    @Volatile
    private var flow: MicrosoftAuthFlow = MicrosoftAuthFlow()
    private var yggdrasilFlow: YggdrasilAuthFlow = YggdrasilAuthFlow()

    private val gson = Gson()

    /** 每账号刷新锁，防止并发刷新同一 refresh_token 导致 token 失效 */
    private val refreshLocks: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

    /** 串行化 accounts.json 读写，避免 load/save 竞态与共享 .tmp 撕文件 */
    private val accountStoreLock = Any()

    /**
     * 创建离线账号。
     *
     * UUID 使用 Bukkit/Paper 兼容前缀 {@code OfflinePlayer:}（非历史 {@code Offline:}），
     * 以便与主流离线服 / 皮肤站工具对齐。
     */
    fun offline(username: String): Account {
        val uuid = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + username).toByteArray(StandardCharsets.UTF_8)
        ).toString()
        // accessToken 置空：离线会话不能用于 Mojang API
        return Account(username, uuid, "", Account.AccountType.OFFLINE)
    }

    /** 请求设备码（UI 层显示给用户） */
    @Throws(IOException::class)
    fun requestDeviceCode(): DeviceCode = flow.requestDeviceCode()

    /**
     * 设置自定义 Azure client_id。
     * 传入 null 或空字符串则回退到 legacy client_id（仅支持 device code flow）。
     */
    fun setAzureClientId(clientId: String?) {
        val old = this.flow
        this.flow = MicrosoftAuthFlow(clientId ?: "")
        try { old.shutdown() } catch (_: Throwable) {}
    }

    /** 判断当前是否使用自定义 client_id */
    fun hasCustomClientId(): Boolean = flow.hasCustomClientId()

    /**
     * 异步等待用户完成登录，并完成剩余流程，最终返回 Account。
     * 捕获 flow 引用到局部变量，防止 setAzureClientId 在登录过程中替换 flow。
     */
    fun loginMicrosoftAsync(
        dc: DeviceCode,
        onPending: Consumer<String>?
    ): CompletableFuture<Account> {
        val currentFlow = this.flow
        return currentFlow.pollForMsOAuthToken(dc, onPending)
            .thenApplyAsync { token ->
                try {
                    currentFlow.completeLogin(token)
                } catch (e: IOException) {
                    throw RuntimeException("微软登录失败: ${e.message}", e)
                }
            }
    }

    /**
     * 启动前刷新微软账号 MC token（需已持久化 refresh_token）。
     * 使用 per-account 锁防止并发刷新同一 refresh_token 导致 token 轮换竞态。
     */
    @Throws(IOException::class)
    fun refreshMicrosoftAccount(account: Account): Account {
        if (account.type != Account.AccountType.MICROSOFT) {
            throw IOException("非微软账号")
        }
        if (account.msRefreshToken.isEmpty()) {
            throw IOException("无 refresh_token，请重新登录微软账号")
        }
        val lock = refreshLocks.computeIfAbsent(account.uuid) { Any() }
        synchronized(lock) {
            val refreshed = flow.refreshLogin(account.msRefreshToken)
            // 若 refresh 后 UUID 变化（极少见），仍采用刷新结果；否则保留皮肤站字段等
            return if (account.uuid == refreshed.uuid) {
                account.withMicrosoftSession(
                    refreshed.accessToken,
                    refreshed.msRefreshToken,
                    refreshed.expiresAt
                )
            } else {
                refreshed
            }
        }
    }

    /**
     * 校验微软账号当前 MC accessToken 是否仍有效（GET minecraft/profile）。
     * 网络失败时抛 IOException；401/403 返回 false。
     */
    @Throws(IOException::class)
    fun isMicrosoftAccessTokenValid(account: Account): Boolean {
        if (account.type != Account.AccountType.MICROSOFT) return false
        return flow.isMcAccessTokenValid(account.accessToken)
    }

    // ============ Yggdrasil 皮肤站认证 ============

    /**
     * Yggdrasil 皮肤站登录。
     *
     * @param apiUrl   皮肤站 API 根地址（如 https://littleskin.cn/api/yggdrasil）
     * @param username 用户名或邮箱
     * @param password 密码
     * @return 登录成功后的 Account
     */
    @Throws(IOException::class)
    fun yggdrasilLogin(apiUrl: String, username: String, password: String): Account {
        return yggdrasilFlow.login(apiUrl, username, password)
    }

    /**
     * 校验 Yggdrasil accessToken 是否仍有效。
     * @param clientToken 可选，用于会话绑定校验
     * @return true 有效，false 已失效
     */
    @Throws(IOException::class)
    fun yggdrasilValidate(apiUrl: String, accessToken: String, clientToken: String? = null): Boolean {
        return if (clientToken != null)
            yggdrasilFlow.validate(apiUrl, accessToken, clientToken)
        else
            yggdrasilFlow.validate(apiUrl, accessToken)
    }

    /**
     * 刷新 Yggdrasil accessToken（使用 clientToken 保持会话绑定）。
     * 成功后返回新的 accessToken，失败返回 null。
     */
    @Throws(IOException::class)
    fun yggdrasilRefresh(apiUrl: String, accessToken: String, clientToken: String? = null): String? {
        return if (clientToken != null)
            yggdrasilFlow.refresh(apiUrl, accessToken, clientToken)
        else
            yggdrasilFlow.refresh(apiUrl, accessToken)
    }

    // ============ 多账号持久化 ============

    /**
     * 加载所有账号 + 当前选中账号。
     * 文件不存在时返回空 AccountStore。
     */
    @Throws(IOException::class)
    fun loadStore(file: Path = paths.accounts): AccountStore {
        synchronized(accountStoreLock) {
            if (!Files.exists(file)) return AccountStore(emptyList(), null)
            val raw = try {
                FileUtils.readString(file)
            } catch (e: IOException) {
                throw e
            }
            val root: JsonObject = try {
                JsonParser.parseString(raw).asJsonObject
            } catch (t: Throwable) {
                // 解析失败：保留原文件并复制备份
                System.err.println("[AuthService] 账号文件解析失败（已保留原文件）: ${t.message}")
                try {
                    val backup = file.resolveSibling(
                        file.fileName.toString() + ".corrupt." + System.currentTimeMillis()
                    )
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)
                    System.err.println("[AuthService] 可疑文件已复制至: $backup")
                } catch (backupErr: Throwable) {
                    System.err.println("[AuthService] 备份可疑文件失败: ${backupErr.message}")
                }
                return AccountStore(emptyList(), null)
            }
            val accounts = ArrayList<Account>()
            val corrupted = ArrayList<String>()
            if (root.has("accounts")) {
                for (e in root.getAsJsonArray("accounts")) {
                    val o = e.asJsonObject
                    val accountType = try {
                        Account.AccountType.valueOf(
                            if (o.has("type") && !o.get("type").isJsonNull)
                                o.get("type").asString else "OFFLINE"
                        )
                    } catch (_: IllegalArgumentException) {
                        Account.AccountType.OFFLINE
                    }
                    val encRefresh = if (o.has("msRefreshToken") && !o.get("msRefreshToken").isJsonNull)
                        o.get("msRefreshToken").asString else ""
                    val msRefresh = if (encRefresh.isEmpty()) "" else tokenEncryptor.decrypt(encRefresh) ?: ""
                    var expiresAt = 0L
                    if (o.has("expiresAt") && !o.get("expiresAt").isJsonNull) {
                        try { expiresAt = o.get("expiresAt").asLong } catch (_: Throwable) {}
                    }
                    val accessToken = tokenEncryptor.decrypt(
                        if (o.has("accessToken") && !o.get("accessToken").isJsonNull)
                            o.get("accessToken").asString else ""
                    )
                    if (accessToken == null) {
                        val uname = if (o.has("username") && !o.get("username").isJsonNull)
                            o.get("username").asString else "(unknown)"
                        System.err.println("[AuthService] 账号 accessToken 解密失败，已记录: $uname")
                        corrupted.add(uname)
                        continue
                    }
                    accounts.add(
                        Account(
                            username = if (o.has("username") && !o.get("username").isJsonNull)
                                o.get("username").asString else "",
                            uuid = if (o.has("uuid") && !o.get("uuid").isJsonNull)
                                o.get("uuid").asString else "",
                            accessToken = accessToken,
                            type = accountType,
                            skinUrl = if (o.has("skinUrl") && !o.get("skinUrl").isJsonNull)
                                o.get("skinUrl").asString else "",
                            skinModel = if (o.has("skinModel") && !o.get("skinModel").isJsonNull)
                                o.get("skinModel").asString else "classic",
                            xuid = if (o.has("xuid") && !o.get("xuid").isJsonNull)
                                o.get("xuid").asString else "",
                            authServerUrl = if (o.has("authServerUrl") && !o.get("authServerUrl").isJsonNull)
                                o.get("authServerUrl").asString else "",
                            msRefreshToken = msRefresh,
                            expiresAt = expiresAt,
                            clientToken = if (o.has("clientToken") && !o.get("clientToken").isJsonNull)
                                o.get("clientToken").asString else ""
                        )
                    )
                }
            }
            val selected = if (root.has("selected") && !root.get("selected").isJsonNull)
                root.get("selected").asString else null
            return AccountStore(accounts, selected, corrupted)
        }
    }

    /** 保存账号集合 + 选中状态 */
    @Throws(IOException::class)
    fun saveStore(store: AccountStore, file: Path = paths.accounts) {
        synchronized(accountStoreLock) {
            val root = JsonObject()
            store.selectedUuid?.let { root.addProperty("selected", it) }
            val arr = JsonArray()
            for (a in store.accounts) {
                val o = JsonObject()
                o.addProperty("uuid", a.uuid)
                o.addProperty("username", a.username)
                val plainToken = a.accessToken
                val encToken = tokenEncryptor.encrypt(plainToken)
                if (plainToken.isNotEmpty() && encToken.isEmpty()) {
                    throw IOException("无法加密账号 accessToken（${a.username}），账号文件未保存。" +
                        "请检查 Android Keystore 或应用私有目录权限")
                }
                o.addProperty("accessToken", encToken)
                o.addProperty("type", a.type.name)
                o.addProperty("skinUrl", a.skinUrl)
                o.addProperty("skinModel", a.skinModel)
                o.addProperty("xuid", a.xuid)
                o.addProperty("authServerUrl", a.authServerUrl)
                if (a.clientToken.isNotEmpty()) {
                    o.addProperty("clientToken", a.clientToken)
                }
                val plainRefresh = a.msRefreshToken
                if (plainRefresh.isNotEmpty()) {
                    val encRefresh = tokenEncryptor.encrypt(plainRefresh)
                    if (encRefresh.isEmpty()) {
                        throw IOException("无法加密账号 msRefreshToken（${a.username}），账号文件未保存。" +
                            "请检查 Android Keystore 或应用私有目录权限")
                    }
                    o.addProperty("msRefreshToken", encRefresh)
                }
                if (a.expiresAt > 0) {
                    o.addProperty("expiresAt", a.expiresAt)
                }
                arr.add(o)
            }
            root.add("accounts", arr)
            Files.createDirectories(file.parent)
            // 唯一 tmp，避免并发 save 争用 accounts.json.tmp
            var tmp: Path? = file.resolveSibling(
                file.fileName.toString() + ".tmp." + UUID.randomUUID()
            )
            try {
                FileUtils.writeString(tmp!!, gson.toJson(root))
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                }
                tmp = null
            } finally {
                tmp?.let { try { Files.deleteIfExists(it) } catch (_: IOException) {} }
            }
        }
    }

    /** 关闭微软登录流的调度器与连接池 */
    fun shutdown() {
        try { flow.shutdown() } catch (_: Throwable) {}
    }
}
