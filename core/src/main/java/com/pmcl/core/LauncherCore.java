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

        this.versionManager = new VersionManager(config);
        // 直接传入 preferences 一次性构建正确的 HttpClient，避免构造+reconfigure 重复构建
        this.downloadManager = new DownloadManager(config, preferences);
        this.authService = new AuthService();
        this.runtimeManager = new RuntimeManager();
        this.launchManager = new LaunchManager(config);
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

    /** 应用语言（zh_CN / en_US / ja_JP） */
    public void applyLanguage(String lang) {
        if ("en_US".equals(lang)) I18n.setLocale(I18n.EN_US);
        else if ("ja_JP".equals(lang)) I18n.setLocale(I18n.JA_JP);
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
