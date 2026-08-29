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
import com.pmcl.core.metal.MetalRenderInstaller;
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
    private final MetalRenderInstaller metalRenderInstaller;
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
                config.getWorkDir().resolve("preferences.json"));
        this.instanceManager = new InstanceManager(config);

        this.versionManager = new VersionManager(config, preferences);
        // 直接传入 preferences 一次性构建正确的 HttpClient，避免构造+reconfigure 重复构建
        this.downloadManager = new DownloadManager(config, preferences);
        this.authService = new AuthService();
        // 读取自定义 Azure / GitHub client_id（若存在）
        try {
            java.nio.file.Path clientIdFile = config.getWorkDir().resolve("azure_client_id.txt");
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
        try {
            java.nio.file.Path ghIdFile = config.getWorkDir().resolve("github_client_id.txt");
            if (java.nio.file.Files.exists(ghIdFile)) {
                String customId = java.nio.file.Files.readString(ghIdFile,
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!customId.isEmpty()) {
                    authService.setGitHubClientId(customId);
                }
            }
        } catch (Throwable t) {
            System.err.println("[LauncherCore] 读取 github_client_id.txt 失败: " + t.getMessage());
        }
        this.runtimeManager = new RuntimeManager();
        this.launchManager = new LaunchManager(config, preferences);
        this.versionInstaller = new VersionInstaller(config, versionManager, downloadManager);
        this.modLoaderManager = new ModLoaderManager(config, downloadManager, versionInstaller);
        this.modMarketManager = new ModMarketManager(config, downloadManager);
        this.modManager = new ModManager(config.getWorkDir().resolve("mods"));
        this.modpackManager = new ModpackManager(config, downloadManager, versionInstaller,
                modLoaderManager, preferences, modMarketManager);
        this.downloadQueue = new DownloadQueueManager(config, downloadManager, versionInstaller,
                modMarketManager, modLoaderManager, preferences);
        this.modUpdateChecker = new ModUpdateChecker(config, modMarketManager, preferences);
        this.modDependencyResolver = new ModDependencyResolver(config, modMarketManager, preferences);
        this.metalRenderInstaller = new MetalRenderInstaller(
                config, preferences, modMarketManager.getModrinthClient(), downloadManager);
        this.modTagStore = new com.pmcl.core.mods.ModTagStore(
                config.getWorkDir().resolve("mod_tags.json"));
        this.playTimeTracker = new PlayTimeTracker(
                config.getWorkDir().resolve("playtime.json"));
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
        String launcherVersion = currentLauncherVersion();
        this.selfUpdater = initOptional("SelfUpdater",
                () -> new SelfUpdater(downloadManager, "", launcherVersion));
        // GitHub Release 同步更新（从 Preferences 读取配置，enabled 时自动检查）
        this.githubSync = initOptional("GitHubReleaseSync",
                () -> {
                    GitHubReleaseSyncChecker checker = new GitHubReleaseSyncChecker(launcherVersion);
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
            if (this.multiplayerManager != null) {
                this.multiplayerManager.setPluginManager(this.pluginManager);
            }
            if (this.downloadQueue != null) {
                this.downloadQueue.setPluginManager(this.pluginManager);
            }
        }

        // 应用持久化的语言偏好（失败不中断）
        try {
            applyLanguage(preferences.getLanguage());
        } catch (Throwable e) {
            System.err.println("[LauncherCore] 语言设置失败: " + e.getMessage());
        }
        // CLI 等不经过 ViewModel 的入口也需要 JVM 代理属性
        try {
            applyJvmProxyProperties();
        } catch (Throwable e) {
            System.err.println("[LauncherCore] 应用代理系统属性失败: " + e.getMessage());
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
     * 用当前网络配置探测连通性（走代理/镜像）。成功返回状态描述。
     */
    public String testProxyConnection() throws java.io.IOException {
        String url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
        String mt = preferences.getMirrorType();
        if ("BMCLAPI".equals(mt)) {
            url = "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json";
        } else if ("CUSTOM".equals(mt)) {
            String base = preferences.getCustomMirrorBase();
            if (base != null && !base.isEmpty()) {
                if (!base.endsWith("/")) base = base + "/";
                url = base + "mc/game/version_manifest_v2.json";
            }
        }
        return downloadManager.testConnection(url);
    }

    /**
     * 设置 Java 全局代理系统属性（HTTP CONNECT 或 SOCKS5）。
     * 优先使用 Preferences；未启用时回退环境变量 HTTP_PROXY / HTTPS_PROXY / ALL_PROXY。
     */
    private void applyJvmProxyProperties() {
        com.pmcl.core.util.ProxySupport.applyJvmProperties(preferences);
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

    /** Metal 渲染安装器（Apple Silicon Mac 专用） */
    public MetalRenderInstaller metalRender() { return metalRenderInstaller; }

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
    public String launcherVersion() { return currentLauncherVersion(); }

    /** 从构建产物 Manifest 读取版本；IDE 运行时回退开发版本。 */
    private static String currentLauncherVersion() {
        String version = LauncherCore.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = System.getProperty("pmcl.version", "1.3.0");
        }
        return version;
    }

    /**
     * 统一关闭内核子系统（下载队列、网络、启动、鉴权、模组检查等）。
     * 幂等；供 UI / Companion / 测试入口在退出前调用，避免孤儿线程与连接泄漏。
     */
    public void shutdown() {
        safeShutdown("multiplayer", () -> {
            if (multiplayerManager != null) multiplayerManager.leaveRoom();
        });
        safeShutdown("friend", () -> {
            if (friendManager != null) friendManager.close();
        });
        safeShutdown("githubSync", () -> {
            if (githubSync != null) githubSync.close();
        });
        safeShutdown("downloadQueue", () -> {
            if (downloadQueue != null) downloadQueue.shutdown();
        });
        safeShutdown("launch", () -> {
            if (launchManager != null) launchManager.shutdown();
        });
        safeShutdown("modUpdateChecker", () -> {
            if (modUpdateChecker != null) modUpdateChecker.shutdown();
        });
        safeShutdown("modDependencyResolver", () -> {
            if (modDependencyResolver != null) modDependencyResolver.shutdown();
        });
        safeShutdown("downloads", () -> {
            if (downloadManager != null) downloadManager.shutdown();
        });
        safeShutdown("auth", () -> {
            if (authService != null) authService.shutdown();
        });
        safeShutdown("preferences", () -> {
            if (preferences != null) preferences.shutdown();
        });
        safeShutdown("plugins", () -> {
            if (pluginManager != null) pluginManager.close();
        });
    }

    private static void safeShutdown(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            System.err.println("[LauncherCore] shutdown " + name + " 失败: " + e.getMessage());
        }
    }
}
