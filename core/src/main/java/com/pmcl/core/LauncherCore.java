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
import com.pmcl.core.modpack.ModpackManager;
import com.pmcl.core.multiplayer.MultiplayerManager;
import com.pmcl.core.migration.MigrationManager;
import com.pmcl.core.news.NewsClient;
import com.pmcl.core.plugin.PluginManager;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.runtime.JavaRuntimeDownloader;
import com.pmcl.core.runtime.RuntimeManager;
import com.pmcl.core.translate.TranslateClient;
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
    private final MigrationManager migrationManager;
    private final PluginManager pluginManager;
    private final TranslateClient translateClient;

    public LauncherCore() {
        this(new LauncherConfig());
    }

    public LauncherCore(LauncherConfig config) {
        this.config = config;
        this.preferences = new Preferences(
                Paths.get(System.getProperty("user.home"), ".pmcl", "preferences.json"));

        this.versionManager = new VersionManager(config);
        this.downloadManager = new DownloadManager(config);
        // 启动时根据偏好初始化镜像/代理/限速
        this.downloadManager.reconfigure(preferences);
        this.authService = new AuthService();
        this.runtimeManager = new RuntimeManager();
        this.launchManager = new LaunchManager(config);
        this.versionInstaller = new VersionInstaller(config, versionManager, downloadManager);
        this.modLoaderManager = new ModLoaderManager(config, downloadManager);
        this.modMarketManager = new ModMarketManager(config, downloadManager);
        this.modManager = new ModManager(config.getWorkDir().resolve("mods"));
        this.modpackManager = new ModpackManager(config, downloadManager, versionInstaller,
                modLoaderManager, preferences);
        this.downloadQueue = new DownloadQueueManager(config, downloadManager, versionInstaller,
                modMarketManager, modLoaderManager, preferences);
        this.modUpdateChecker = new ModUpdateChecker(config, modMarketManager, preferences);
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
        this.selfUpdater = new SelfUpdater(downloadManager, "", "0.0.0");
        this.newsClient = new NewsClient(downloadManager.httpClient());
        this.multiplayerManager = new MultiplayerManager();
        this.migrationManager = new MigrationManager(config.getWorkDir());

        // Plugin system
        this.pluginManager = new PluginManager(this);
        // Inject plugin manager into launch manager for hooks/events
        this.launchManager.setPluginManager(pluginManager);

        this.translateClient = new TranslateClient(downloadManager);

        // 应用持久化的语言偏好
        applyLanguage(preferences.getLanguage());
    }

    /** 应用语言（zh_CN / en_US） */
    public void applyLanguage(String lang) {
        if ("en_US".equals(lang)) I18n.setLocale(I18n.EN_US);
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
        newsClient.updateHttpClient(http);
        modMarketManager.updateHttpClients(http);
        translateClient.updateHttpClient(http);
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

    public MigrationManager migration() { return migrationManager; }

    public LaunchManager launch() { return launchManager; }

    public VersionInstaller install() { return versionInstaller; }

    public ModLoaderManager modLoaders() { return modLoaderManager; }

    public ModMarketManager modMarket() { return modMarketManager; }

    public ModManager modManager() { return modManager; }
    public ModpackManager modpacks() { return modpackManager; }
    public DownloadQueueManager downloadQueue() { return downloadQueue; }
    public ModUpdateChecker modUpdateChecker() { return modUpdateChecker; }

    public LaunchProfileBuilder profileBuilder() { return profileBuilder; }

    public PluginManager plugins() { return pluginManager; }
}
