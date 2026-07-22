package com.pmcl.core;

import com.pmcl.core.auth.AuthService;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.download.DownloadQueueManager;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.launch.LaunchManager;
import com.pmcl.core.launch.LaunchProfileBuilder;
import com.pmcl.core.market.ModMarketManager;
import com.pmcl.core.modloader.ModLoaderManager;
import com.pmcl.core.mods.ModManager;
import com.pmcl.core.mods.ModTagStore;
import com.pmcl.core.mods.ModUpdateChecker;
import com.pmcl.core.mods.ModDependencyResolver;
import com.pmcl.core.modpack.ModpackManager;
import com.pmcl.core.multiplayer.MultiplayerManager;
import com.pmcl.core.friend.FriendManager;
import com.pmcl.core.migration.MigrationManager;
import com.pmcl.core.news.NewsClient;
import com.pmcl.core.instance.InstanceManager;
import com.pmcl.core.plugin.PluginManager;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.runtime.JavaRuntimeDownloader;
import com.pmcl.core.runtime.RuntimeManager;
import com.pmcl.core.stats.PlayTimeTracker;
import com.pmcl.core.translate.TranslateClient;
import com.pmcl.core.util.PastebinClient;
import com.pmcl.core.update.SelfUpdater;
import com.pmcl.core.update.GitHubReleaseSyncChecker;
import com.pmcl.core.version.VersionManager;
import com.pmcl.core.gamecontent.WorldManager;
import com.pmcl.core.gamecontent.ScreenshotManager;
import com.pmcl.core.gamecontent.ResourcePackManager;
import com.pmcl.core.gamecontent.ShaderPackManager;
import com.pmcl.core.gamecontent.DatapackManager;
import com.pmcl.core.i18n.I18n;
import com.pmcl.core.install.IntegrityChecker;
import com.pmcl.core.launch.CrashAnalyzer;
import com.pmcl.core.launch.ProcessMonitor;
import com.pmcl.core.web.WikiBrowser;
import okhttp3.OkHttpClient;

import java.nio.file.Paths;

/**
 * 启动器内核入口
 * <p>
 * 由 UI 层（Compose Multiplatform）通过 JVM 同进程直接调用。
 * 所有 MC 启动相关逻辑都在这里实现，UI 仅负责展示与交互。
 */
public final class LauncherCore {

    private final LauncherConfig config;
    private final Preferences preferences;

    private final VersionManager versionManager;
    private final DownloadManager downloadManager;
    private final AuthService authService;
    private final RuntimeManager runtimeManager;
    private final LaunchManager launchManager;
    private final VersionInstaller versionInstaller;
    private final ModLoaderManager modLoaderManager;
    private final ModMarketManager modMarketManager;
    private final ModManager modManager;
    private final ModpackManager modpackManager;
    private final DownloadQueueManager downloadQueue;
    private final ModUpdateChecker modUpdateChecker;
    private final ModDependencyResolver modDependencyResolver;
    private final ModTagStore modTagStore;
    private final PlayTimeTracker playTimeTracker;
    private final PastebinClient pastebinClient;
    private final LaunchProfileBuilder profileBuilder;
    private final JavaRuntimeDownloader javaRuntimeDownloader;
    private final WorldManager worldManager;
    private final ScreenshotManager screenshotManager;
    private final ResourcePackManager resourcePackManager;
    private final ShaderPackManager shaderPackManager;
    private final DatapackManager datapackManager;
    private final IntegrityChecker integrityChecker;
    private final CrashAnalyzer crashAnalyzer;
    private final ProcessMonitor processMonitor;
    private final SelfUpdater selfUpdater;
    private final GitHubReleaseSyncChecker githubSync;
    private final NewsClient newsClient;
    private final MultiplayerManager multiplayerManager;
    private final FriendManager friendManager;
    private final MigrationManager migrationManager;
    private final PluginManager pluginManager;
    private final TranslateClient translateClient;
    private final InstanceManager instanceManager;

    public LauncherCore() {
        this(new LauncherConfig());
    }

    public LauncherCore(LauncherConfig config) {
        this.config = config;
        this.preferences = new Preferences(
                Paths.get(System.getProperty("user.home"), ".pmcl", "preferences.json"));
        this.instanceManager = new InstanceManager(config);

        this.versionManager = new VersionManager(config, preferences);
        // 直接传入 preferences 一次性构建正确的 HttpClient，避免构造+reconfigure 重复构建
        this.downloadManager = new DownloadManager(config, preferences);
        this.authService = new AuthService();
        // 读取自定义 Azure client_id（若存在），启用浏览器授权码流程
        try {
            java.nio.file.Path clientIdFile = Paths.get(
                    System.getProperty("user.home"), ".pmcl", "azure_client_id.txt");
            if (java.nio.file.Files.exists(clientIdFile)) {
                String customId = java.nio.file.Files.readString(clientIdFile,
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!customId.isEmpty()) {
                    authService.setAzureClientId(customId);
                }
            }
        } catch (Throwable t) {
            System.err.println("[LauncherCore] 读取 azure_client_id.txt 失败: " + t.getMessage());
        }
        this.runtimeManager = new RuntimeManager();
        this.launchManager = new LaunchManager(config, preferences);
        this.versionInstaller = new VersionInstaller(config, versionManager, downloadManager);
        this.modLoaderManager = new ModLoaderManager(config, downloadManager);
        this.modMarketManager = new ModMarketManager(config, downloadManager);
        this.modManager = new ModManager(config.getWorkDir().resolve("mods"));
        this.modpackManager = new ModpackManager(config, downloadManager, versionInstaller,
                modLoaderManager, preferences, modMarketManager);
        this.downloadQueue = new DownloadQueueManager(config, downloadManager, versionInstaller,
                modMarketManager, modLoaderManager, preferences);
        this.modUpdateChecker = new ModUpdateChecker(config, modMarketManager, preferences);
        this.modDependencyResolver = new ModDependencyResolver(config, modMarketManager, preferences);
        this.modTagStore = new com.pmcl.core.mods.ModTagStore(
                Paths.get(System.getProperty("user.home"), ".pmcl", "mod_tags.json"));
        this.playTimeTracker = new PlayTimeTracker(
                Paths.get(System.getProperty("user.home"), ".pmcl", "playtime.json"));
        this.pastebinClient = new PastebinClient(downloadManager.httpClient());
        this.profileBuilder = new LaunchProfileBuilder(config, preferences, downloadManager);
        this.javaRuntimeDownloader = new JavaRuntimeDownloader(config, downloadManager);
        this.worldManager = new WorldManager(config.getWorkDir());
        this.screenshotManager = new ScreenshotManager(config.getWorkDir());
        this.resourcePackManager = new ResourcePackManager(config.getWorkDir());
        this.shaderPackManager = new ShaderPackManager(config.getWorkDir());
        this.datapackManager = new DatapackManager();
        this.integrityChecker = new IntegrityChecker(config);
        this.crashAnalyzer = new CrashAnalyzer();
        this.processMonitor = new ProcessMonitor();

        // 可选子系统：失败不中断启动器，对应功能降级不可用
        this.selfUpdater = initOptional("SelfUpdater",
                () -> new SelfUpdater(downloadManager, "", "0.0.0"));
        // GitHub Release 同步更新（从 Preferences 读取配置，enabled 时自动检查）
        this.githubSync = initOptional("GitHubReleaseSync",
                () -> {
                    GitHubReleaseSyncChecker checker = new GitHubReleaseSyncChecker("1.0.0");
                    checker.setGithubRepo(preferences.getGithubRepo());
                    if (preferences.isGithubSyncEnabled()) {
                        try {
                            checker.start();
                            System.err.println("[LauncherCore] GitHub Release 同步已启动: "
                                    + preferences.getGithubRepo());
                        } catch (Throwable t) {
                            System.err.println("[LauncherCore] GitHub Release 同步启动失败: " + t.getMessage());
                        }
                    }
                    return checker;
                });
        this.newsClient = initOptional("NewsClient",
                () -> new NewsClient(downloadManager.httpClient()));
        this.multiplayerManager = initOptional("MultiplayerManager",
                () -> new MultiplayerManager());
        this.friendManager = initOptional("FriendManager",
                () -> {
                    FriendManager fm = new FriendManager();
                    fm.initialize();
                    return fm;
                });
        this.migrationManager = new MigrationManager(config.getWorkDir());
        this.pluginManager = initOptional("PluginManager",
                () -> new PluginManager(this));
        this.translateClient = initOptional("TranslateClient",
                () -> new TranslateClient(downloadManager));

        // Inject plugin manager into launch manager for hooks/events
        if (this.pluginManager != null) {
            this.launchManager.setPluginManager(this.pluginManager);
        }

        // 应用持久化的语言偏好（失败不中断）
        try {
            applyLanguage(preferences.getLanguage());
        } catch (Throwable e) {
            System.err.println("[LauncherCore] 语言设置失败: " + e.getMessage());
        }
    }

    /** 初始化可选子系统，失败时记录日志并返回 null，不中断启动流程 */
    private static <T> T initOptional(String name, java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable e) {
            System.err.println("[LauncherCore] 可选子系统 " + name + " 初始化失败（已降级）: " + e.getMessage());
            return null;
        }
    }

    /** 应用语言（zh_CN / zh_TW / en_US / ja_JP / ud_EN 颠倒英语） */
    public void applyLanguage(String lang) {
        if ("en_US".equals(lang)) I18n.setLocale(I18n.EN_US);
        else if ("ja_JP".equals(lang)) I18n.setLocale(I18n.JA_JP);
        else if ("ud_EN".equals(lang)) I18n.setLocale(I18n.UD_EN);
        else if ("zh_TW".equals(lang)) I18n.setLocale(I18n.ZH_TW);
        else I18n.setLocale(I18n.ZH_CN);
    }

    public LauncherConfig getConfig() { return config; }

    public Preferences getPreferences() { return preferences; }

    public VersionManager versions() { return versionManager; }

    public DownloadManager downloads() { return downloadManager; }

    /** 应用最新的网络偏好（用户在设置页修改后调用） */
    public void applyNetworkPreferences() {
        downloadManager.reconfigure(preferences);
        // 同步更新各模块的 http 客户端，让代理配置对新闻/模组市场请求生效
        OkHttpClient http = downloadManager.httpClient();
        if (newsClient != null) newsClient.updateHttpClient(http);
        modMarketManager.updateHttpClients(http);
        pastebinClient.updateHttpClient(http);
        if (translateClient != null) translateClient.updateHttpClient(http);
        // 同步 Java 全局代理系统属性，让 URL.readBytes() 等原生 HTTP 也走代理
        applyJvmProxyProperties();
    }

    /**
     * 设置 Java 全局代理系统属性（http.proxyHost / https.proxyHost 等）。
     * 优先使用 Preferences 配置；若未配置则回退到环境变量 HTTP_PROXY/HTTPS_PROXY。
     * 用于头像、皮肤图片等使用 java.net.URL 的下载场景。
     */
    private void applyJvmProxyProperties() {
        String host = null;
        String port = null;
        if (preferences.isUseProxy()) {
            host = preferences.getProxyHost();
            int p = preferences.getProxyPort();
            if (p > 0) port = String.valueOf(p);
        } else {
            // 回退到环境变量
            String env = System.getenv("HTTPS_PROXY");
            if (env == null || env.isEmpty()) env = System.getenv("https_proxy");
            if (env == null || env.isEmpty()) env = System.getenv("HTTP_PROXY");
            if (env == null || env.isEmpty()) env = System.getenv("http_proxy");
            if (env != null && !env.isEmpty()) {
                try {
                    java.net.URI uri = java.net.URI.create(env);
                    host = uri.getHost();
                    int p = uri.getPort();
                    if (p > 0) port = String.valueOf(p);
                } catch (Exception ignored) {}
            }
        }
        if (host != null && !host.isEmpty() && port != null) {
            // M71 修复：校验 host 字符合法性，防止 null 字节注入等攻击
            // 合法 host 仅允许字母、数字、点、连字符、冒号（IPv6 地址）
            // 拒绝包含空格、控制字符、换行等可能导致系统属性注入的字符
            if (!isSafeProxyHost(host)) {
                System.err.println("[LauncherCore] 拒绝设置代理系统属性：host 包含非法字符");
                System.clearProperty("http.proxyHost");
                System.clearProperty("http.proxyPort");
                System.clearProperty("https.proxyHost");
                System.clearProperty("https.proxyPort");
                return;
            }
            System.setProperty("http.proxyHost", host);
            System.setProperty("http.proxyPort", port);
            System.setProperty("https.proxyHost", host);
            System.setProperty("https.proxyPort", port);
        } else {
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
        }
    }

    /**
     * M71：校验代理 host 字符集，防止 null 字节、控制字符、空白字符注入。
     * <p>
     * 合法字符：
     * <ul>
     *   <li>字母 a-z A-Z</li>
     *   <li>数字 0-9</li>
     *   <li>点（.）、连字符（-）、冒号（:）—— 支持 IPv6 地址</li>
     *   <li>方括号 [ ] —— 支持 [IPv6] 格式</li>
     * </ul>
     * 拒绝空格、换行、tab、null 字节（\0）及其他控制字符。
     */
    private static boolean isSafeProxyHost(String host) {
        if (host == null || host.isEmpty()) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.' || c == '-' || c == ':' || c == '[' || c == ']') continue;
            if (c >= 'a' && c <= 'z') continue;
            if (c >= 'A' && c <= 'Z') continue;
            if (c >= '0' && c <= '9') continue;
            // 非法字符：空格、控制字符、null 字节、换行等
            return false;
        }
        return true;
    }

    public AuthService auth() { return authService; }

    public RuntimeManager runtime() { return runtimeManager; }

    public JavaRuntimeDownloader javaDownloader() { return javaRuntimeDownloader; }

    public WorldManager worlds() { return worldManager; }

    public ScreenshotManager screenshots() { return screenshotManager; }

    public ResourcePackManager resourcePacks() { return resourcePackManager; }

    public ShaderPackManager shaderPacks() { return shaderPackManager; }

    public DatapackManager datapacks() { return datapackManager; }

    public IntegrityChecker integrity() { return integrityChecker; }

    public CrashAnalyzer crashAnalyzer() { return crashAnalyzer; }

    public ProcessMonitor processMonitor() { return processMonitor; }

    public SelfUpdater selfUpdater() { return selfUpdater; }

    public GitHubReleaseSyncChecker githubSync() { return githubSync; }

    public NewsClient news() { return newsClient; }

    public TranslateClient translate() { return translateClient; }

    public MultiplayerManager multiplayer() { return multiplayerManager; }
    public FriendManager friend() { return friendManager; }

    public MigrationManager migration() { return migrationManager; }

    public LaunchManager launch() { return launchManager; }

    public VersionInstaller install() { return versionInstaller; }

    public ModLoaderManager modLoaders() { return modLoaderManager; }

    public ModMarketManager modMarket() { return modMarketManager; }

    public ModManager modManager() { return modManager; }
    public ModTagStore modTagStore() { return modTagStore; }
    public ModpackManager modpacks() { return modpackManager; }
    public DownloadQueueManager downloadQueue() { return downloadQueue; }
    public ModUpdateChecker modUpdateChecker() { return modUpdateChecker; }
    public ModDependencyResolver modDependencyResolver() { return modDependencyResolver; }
    public PlayTimeTracker playTimeTracker() { return playTimeTracker; }
    public PastebinClient pastebin() { return pastebinClient; }

    public LaunchProfileBuilder profileBuilder() { return profileBuilder; }

    public PluginManager plugins() { return pluginManager; }

    public InstanceManager instances() { return instanceManager; }
}
