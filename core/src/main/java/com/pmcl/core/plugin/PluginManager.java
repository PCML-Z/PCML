package com.pmcl.core.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.pmcl.core.LauncherCore;
import com.pmcl.plugin.ComposableContent;
import com.pmcl.plugin.CommandHandler;
import com.pmcl.plugin.EventListener;
import com.pmcl.plugin.HomeCard;
import com.pmcl.plugin.LaunchHook;
import com.pmcl.plugin.PmclEvent;
import com.pmcl.plugin.PmclPlugin;
import com.pmcl.plugin.PluginContext;
import com.pmcl.plugin.PluginInfo;
import com.pmcl.plugin.PluginLoadedEvent;
import com.pmcl.plugin.PluginEnabledEvent;
import com.pmcl.plugin.PluginDisabledEvent;
import com.pmcl.plugin.PluginErrorEvent;
import com.pmcl.plugin.PluginPackageParser;
import com.pmcl.plugin.PluginPackageParser.PluginPackage;
import com.pmcl.plugin.ThemePack;
import com.pmcl.plugin.PluginPermission;
import com.pmcl.plugin.UrlRewriteHook;
import com.pmcl.plugin.UrlRewrittenEvent;
import com.pmcl.plugin.api.AccountsApi;
import com.pmcl.plugin.api.ActionHandler;
import com.pmcl.plugin.api.DownloadsApi;
import com.pmcl.plugin.api.FilesystemApi;
import com.pmcl.plugin.api.HttpApi;
import com.pmcl.plugin.api.I18nApi;
import com.pmcl.plugin.api.InstancesApi;
import com.pmcl.plugin.api.LaunchApi;
import com.pmcl.plugin.api.LoaderVersionsApi;
import com.pmcl.plugin.api.GameProcessApi;
import com.pmcl.plugin.api.ModpackApi;
import com.pmcl.plugin.api.ModsApi;
import com.pmcl.plugin.api.NavBadge;
import com.pmcl.plugin.api.NewsApi;
import com.pmcl.plugin.api.PluginDialogRequest;
import com.pmcl.plugin.api.PluginFilePickerRequest;
import com.pmcl.plugin.api.PluginInputDialogRequest;
import com.pmcl.plugin.api.PluginMenuAction;
import com.pmcl.plugin.api.PluginNotification;
import com.pmcl.plugin.api.PluginProgressUpdate;
import com.pmcl.plugin.api.PluginStatusBarAction;
import com.pmcl.plugin.api.PluginsApi;
import com.pmcl.plugin.api.MusicPlaybackSummary;
import com.pmcl.plugin.api.SchedulerApi;
import com.pmcl.plugin.api.SettingsApi;
import com.pmcl.plugin.api.UiApi;
import com.pmcl.plugin.api.VersionsApi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * Manages plugin discovery, loading, lifecycle, and state.
 *
 * Plugins are stored in ~/.pmcl/plugins/<pluginId>.jar
 * Plugin state (enabled/disabled) is persisted in ~/.pmcl/plugins/plugins.json
 * Plugin data is stored in ~/.pmcl/plugins/<pluginId>/data/
 *
 * Thread-safe: all operations use CopyOnWrite collections.
 */
public final class PluginManager {

    private static final String PLUGINS_DIR_NAME = "plugins";
    private static final String STATE_FILE = "plugins.json";

    /**
     * Reserved command names that plugins cannot register, because they clash
     * with built-in PMCL shell commands. Plugin command names are checked
     * case-insensitively against this set.
     */
    private static final Set<String> RESERVED_COMMAND_NAMES = Set.of(
            "help", "?", "versions", "vs", "remote", "rm", "install", "i",
            "launch", "play", "integrity", "check", "mods", "mod", "search", "s",
            "install-mod", "im", "modloaders", "ml", "worlds", "w", "datapacks", "dp",
            "screenshots", "shots", "resourcepacks", "rp", "shaders", "news", "crash",
            "migrate", "account", "whoami", "login", "logout", "java", "config",
            "pin", "unpin", "recent", "playtime", "mp", "multiplayer", "update",
            "sysinfo", "download", "wiki", "plugin", "plugins", "status", "exit", "quit",
            "cls", "clear", "cache", "log", "skin", "version", "ver", "open", "url", "theme"
    );

    private final LauncherCore core;
    private final Path pluginsDir;
    private final Path stateFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    /** Frozen at construction — plugins cannot downgrade via System.setProperty (C3). */
    private final PluginSecurityPolicy securityPolicy;

    /** Simple sliding-window rate limit for plugin HttpApi (global across plugins). */
    private final Object httpRateLock = new Object();
    private long httpRateWindowStartMs = 0L;
    private int httpRateWindowCount = 0;
    private static final int HTTP_RATE_LIMIT_PER_MINUTE = 60;

    // Revision counter — incremented on any structural change (load/unload/enable/disable).
    // GUI polls this to detect when plugin pages need refreshing.
    private volatile long revision = 0;

    // Loaded plugin entries (pluginId -> entry)
    private final Map<String, PluginEntry> loadedPlugins = new LinkedHashMap<>();
    /** Last discoverAndLoadAll() per-file failures (cleared at start of each scan). */
    private final List<String> lastDiscoveryErrors = new ArrayList<>();
    // Registered custom commands (pluginId -> list of commands)
    private final Map<String, List<RegisteredCommand>> customCommands = new HashMap<>();
    // Registered pages (pluginId -> list of pages)
    private final Map<String, List<RegisteredPage>> customPages = new HashMap<>();
    // Registered settings sections (pluginId -> list)
    private final Map<String, List<RegisteredPage>> customSettingsSections = new HashMap<>();
    // Menu / palette actions
    private final Map<String, List<PluginMenuAction>> customMenuActions = new HashMap<>();
    // Status bar actions
    private final Map<String, List<PluginStatusBarAction>> customStatusBarActions = new HashMap<>();
    // Home cards
    private final Map<String, List<HomeCard>> customHomeCards = new HashMap<>();
    // Registered theme packs (pluginId -> list of packs)
    private final Map<String, List<com.pmcl.plugin.ThemePack>> customThemePacks = new HashMap<>();
    // Event listeners
    private final List<EventListener> eventListeners = new CopyOnWriteArrayList<>();
    // Launch hooks
    private final List<LaunchHook> launchHooks = new CopyOnWriteArrayList<>();
    // URL rewrite hooks
    private final List<TrackedUrlRewriteHook> urlRewriteHooks = new CopyOnWriteArrayList<>();
    // Nav badges: pluginId -> (target -> text)
    private final Map<String, Map<String, String>> navBadges = new HashMap<>();
    // Hidden built-in nav routes: pluginId -> set of routes
    private final Map<String, java.util.Set<String>> hiddenBuiltinNav = new HashMap<>();
    // Plugin i18n keys tracked for cleanup: pluginId -> language -> keys
    private final Map<String, Map<String, java.util.Set<String>>> pluginStringKeys = new HashMap<>();
    // Scheduled tasks: pluginId -> taskId -> future
    private final Map<String, Map<String, java.util.concurrent.ScheduledFuture<?>>> scheduledTasks = new HashMap<>();
    private final java.util.concurrent.ScheduledExecutorService pluginScheduler =
            java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "pmcl-plugin-scheduler");
                t.setDaemon(true);
                return t;
            });
    // Host UI bridges
    private final ConcurrentLinkedQueue<PluginNotification> notifications = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PluginDialogRequest> dialogRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PluginFilePickerRequest> filePickerRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PluginProgressUpdate> progressUpdates = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PluginInputDialogRequest> inputDialogRequests = new ConcurrentLinkedQueue<>();
    private volatile Consumer<String> navigationHandler;
    private volatile Consumer<String> launchRequestHandler;
    private volatile Consumer<String> clipboardHandler;
    private volatile Consumer<String> openUrlHandler;
    private volatile MusicBridge musicBridge;
    private volatile String lastLaunchCancelReason = null;
    /** Phantom-reference tracker for closed plugin ClassLoaders (leak detection). */
    private final ClassLoaderLeakDetector leakDetector = new ClassLoaderLeakDetector();
    // Plugin enabled state (persisted)
    private Map<String, Boolean> enabledState = new HashMap<>();
    // Plugin configs (persisted per-plugin)
    private Map<String, Map<String, String>> pluginConfigs = new HashMap<>();

    // M24 修复：异步事件派发线程池。慢 listener 不再阻塞调用方（loadPlugin/enablePlugin 等）。
    // 使用守护线程，JVM 退出时自动终止；事件通知是 best-effort，无需等待。
    private final java.util.concurrent.ExecutorService eventExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "pmcl-plugin-event");
                t.setDaemon(true);
                return t;
            });

    public PluginManager(LauncherCore core) {
        this.core = core;
        this.pluginsDir = core.getConfig().getWorkDir().resolve(PLUGINS_DIR_NAME);
        this.stateFile = pluginsDir.resolve(STATE_FILE);
        // Capture before any plugin code runs; later System.setProperty cannot weaken this.
        this.securityPolicy = PluginSecurityPolicy.captureAtStartup();
        ensurePluginsDir();
        loadState();
        // 安装 URL 协议处理器门禁工厂，防止恶意插件通过 URL.setURLStreamHandlerFactory
        // 劫持全局 HTTP/HTTPS 流量（包括宿主 DownloadManager 的下载请求）。
        // 工厂返回 null 让 JDK 使用默认处理器，仅占用槽位阻止后续覆盖。
        // 限制：特权反射仍可清空/替换 URL.factory；每次加载插件前会 best-effort 复查。
        installUrlStreamHandlerGuard();
        // URL rewrite hooks are applied only on plugin-initiated DownloadsApi/HttpApi
        // paths — never wired into the host DownloadManager pipeline.
    }

    /**
     * Occupies {@link java.net.URL#setURLStreamHandlerFactory} so plugins cannot install
     * a hostile factory. Returns {@code null} handlers → JDK built-ins.
     * <p>
     * <b>Limit:</b> without a SecurityManager, privileged reflection can still null/replace
     * the private {@code URL.factory} field. We re-assert our guard before plugin loads.
     */
    private static final class UrlStreamHandlerGuard implements java.net.URLStreamHandlerFactory {
        static final UrlStreamHandlerGuard INSTANCE = new UrlStreamHandlerGuard();
        @Override
        public java.net.URLStreamHandler createURLStreamHandler(String protocol) {
            return null;
        }
    }

    /** 门禁标志：确保只安装一次 URLStreamHandlerFactory */
    private static volatile boolean urlGuardInstalled = false;

    private static void installUrlStreamHandlerGuard() {
        if (urlGuardInstalled) return;
        synchronized (PluginManager.class) {
            if (urlGuardInstalled) return;
            try {
                try {
                    java.net.URL.setURLStreamHandlerFactory(UrlStreamHandlerGuard.INSTANCE);
                } catch (Error alreadySet) {
                    // 已被其他模块设置：尝试确认/恢复我们的门禁
                    reassertUrlStreamHandlerGuardLocked();
                }
                urlGuardInstalled = true;
            } catch (Exception e) {
                System.err.println("[PluginManager] URL stream handler guard install failed: "
                        + e.getMessage());
                urlGuardInstalled = true; // avoid retry storms
            }
        }
    }

    /** Best-effort: if reflection cleared {@code URL.factory}, put our guard back. */
    private static void ensureUrlStreamHandlerGuard() {
        installUrlStreamHandlerGuard();
        synchronized (PluginManager.class) {
            reassertUrlStreamHandlerGuardLocked();
        }
    }

    private static void reassertUrlStreamHandlerGuardLocked() {
        try {
            java.lang.reflect.Field factoryField = java.net.URL.class.getDeclaredField("factory");
            factoryField.setAccessible(true);
            Object current = factoryField.get(null);
            if (current == UrlStreamHandlerGuard.INSTANCE) {
                return;
            }
            if (current == null) {
                // factory was cleared via reflection — restore our guard without going through
                // setURLStreamHandlerFactory (which throws if it believes factory was set).
                factoryField.set(null, UrlStreamHandlerGuard.INSTANCE);
                System.err.println("[PluginManager] SECURITY: restored URLStreamHandlerFactory guard "
                        + "(field had been cleared)");
                return;
            }
            if (!(current instanceof UrlStreamHandlerGuard)) {
                System.err.println("[PluginManager] SECURITY: URLStreamHandlerFactory is not PMCL guard ("
                        + current.getClass().getName() + "); cannot safely replace — "
                        + "privileged reflection may have hijacked URL handling");
            }
        } catch (Exception e) {
            // Module / access restrictions: documented limit — cannot harden further here.
        }
    }

    private void ensurePluginsDir() {
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            System.err.println("[PluginManager] Failed to create plugins dir: " + e.getMessage());
        }
    }

    // ==================== State Persistence ====================

    @SuppressWarnings("unchecked")
    private synchronized void loadState() {
        // M26 修复：先清理旧状态，确保 loadState 二次调用行为一致。
        // 否则若 JSON 缺少某字段或解析失败，会残留前一次的数据，导致状态不一致。
        // 使用 clear()+putAll() 而非 reassign，避免外部持有旧引用导致 stale read。
        enabledState.clear();
        pluginConfigs.clear();
        try {
            if (Files.exists(stateFile)) {
                String json = Files.readString(stateFile, java.nio.charset.StandardCharsets.UTF_8);
                Map<String, Object> state = gson.fromJson(json, Map.class);
                if (state != null) {
                    Object enabled = state.get("enabled");
                    if (enabled instanceof Map) {
                        enabledState.putAll((Map<String, Boolean>) enabled);
                    }
                    Object configs = state.get("configs");
                    if (configs instanceof Map) {
                        pluginConfigs.putAll((Map<String, Map<String, String>>) configs);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[PluginManager] Failed to load state: " + e.getMessage());
        }
    }

    private synchronized void saveState() {
        try {
            Map<String, Object> state = new HashMap<>();
            state.put("enabled", enabledState);
            state.put("configs", pluginConfigs);
            // 原子写入：防止 JVM 崩溃导致插件状态文件损坏
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(state), java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.move(tmp, stateFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[PluginManager] Failed to save state: " + e.getMessage());
        }
    }

    // ==================== Discovery & Loading ====================

    /**
     * Scan the plugins directory and load all plugin JARs and .ppk packages.
     * Already-loaded plugins are skipped. Disabled plugins are loaded but not enabled.
     */
    public void discoverAndLoadAll() {
        ensurePluginsDir();
        synchronized (this) {
            lastDiscoveryErrors.clear();
        }
        File[] files = pluginsDir.toFile().listFiles((dir, name) ->
                (name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") || name.toLowerCase(java.util.Locale.ROOT).endsWith(".ppk")) && !name.equals(STATE_FILE));
        if (files == null) return;
        for (File file : files) {
            try {
                if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".ppk")) {
                    loadPluginPackage(file.toPath());
                } else {
                    loadPlugin(file.toPath());
                }
            } catch (Exception e) {
                String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
                synchronized (this) {
                    lastDiscoveryErrors.add(file.getName() + " — " + detail);
                }
                System.err.println("[PluginManager] Failed to load " + file.getName() + ": " + e.getMessage());
            }
        }
        // Auto-enable outside any PluginManager monitor so onEnable ThreadGroup
        // workers can re-enter registration APIs without deadlock.
        List<String> toEnable = new ArrayList<>();
        synchronized (this) {
            for (PluginEntry entry : loadedPlugins.values()) {
                if (entry.getState() == PluginState.LOADED && isEnabled(entry.getInfo().getId())) {
                    toEnable.add(entry.getInfo().getId());
                }
            }
        }
        for (String id : toEnable) {
            try {
                enablePlugin(id);
            } catch (Exception e) {
                synchronized (this) {
                    lastDiscoveryErrors.add(id + " (enable) — " + e.getMessage());
                }
                System.err.println("[PluginManager] Failed to enable " + id + ": " + e.getMessage());
            }
        }
    }

    /**
     * Load a plugin from a JAR file.
     * @return the PluginInfo of the loaded plugin
     * @throws Exception if loading fails
     */
    public PluginInfo loadPlugin(Path jarPath) throws Exception {
        // M25 修复：校验 jarPath 必须在 pluginsDir 下。
        // 防止插件或未来代码被诱导加载任意路径（如系统目录、临时目录）的 JAR，
        // 规避"插件从非受控位置加载"的安全风险。
        // 注意：installFromPath 会先将源 JAR 复制到 pluginsDir，再调用 loadPlugin(target)，
        // 因此该检查不影响正常安装流程。
        Path normalizedJar = jarPath.normalize();
        Path normalizedPlugins = pluginsDir.normalize();
        if (!normalizedJar.startsWith(normalizedPlugins)) {
            throw new IllegalArgumentException(
                    "Plugin JAR must be inside plugins directory (" + normalizedPlugins +
                    "), got: " + normalizedJar +
                    ". Use installFromPath() to install from external locations.");
        }

        final PluginInfo info;
        final PmclPlugin plugin;
        synchronized (this) {
            // Parse descriptor
            info = parseDescriptor(jarPath);
            info.validate();

            // Idempotent: discover/scan may revisit an already-loaded JAR
            PluginEntry existing = loadedPlugins.get(info.getId());
            if (existing != null) {
                return existing.getInfo();
            }

            // Check dependencies — all must be loaded before this plugin can load
            for (String dep : info.getDependencies()) {
                if (!loadedPlugins.containsKey(dep)) {
                    throw new IllegalStateException(
                            "Plugin '" + info.getId() + "' requires dependency '" + dep +
                            "' which is not loaded. Install/load it first.");
                }
            }

            // ===== Branch: External Runtime vs JVM =====
            if (info.getExternalRuntime() != null || info.getEmbed() != null) {
                // --- External runtime / window-embed path ---
                loadExternalRuntimePlugin(info, jarPath);
                return info;
            }

            // Create classloader with PMCL classloader as parent — 使用隔离 ClassLoader
            // 阻止插件直接加载 com.pmcl.core.* 内部类，强制走 getService
            URL[] urls = {jarPath.toUri().toURL()};
            PluginIsolatingClassLoader classLoader = new PluginIsolatingClassLoader(
                    info.getId(), urls, getClass().getClassLoader());

            // Load main class — 异常路径关闭 classLoader 防止 jar 句柄泄漏
            PmclPlugin instance;
            try {
                Class<?> mainClass = classLoader.loadClass(info.getMainClass());
                if (!PmclPlugin.class.isAssignableFrom(mainClass)) {
                    classLoader.close();
                    throw new ClassCastException("Main class " + info.getMainClass() +
                            " does not implement PmclPlugin");
                }
                instance = (PmclPlugin) mainClass.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                try { classLoader.close(); } catch (Exception ignored) {}
                throw t;
            }

            PluginContextImpl ctx = new PluginContextImpl(this, info.getId());
            PluginEntry entry = new PluginEntry(info, instance, ctx, classLoader, jarPath);
            entry.setState(PluginState.LOADED);
            loadedPlugins.put(info.getId(), entry);
            plugin = instance;

            System.out.println("[PluginManager] Loaded plugin: " + info.getId() + " v" + info.getVersion());
            bumpRevision();
        }

        // onLoad outside the monitor: runs in plugin ThreadGroup (spawn+join) and may
        // re-enter PluginManager via registration APIs.
        try {
            runInPlugin(info.getId(), plugin::onLoad);
        } catch (Exception e) {
            System.err.println("[PluginManager] onLoad failed for " + info.getId() + ": " + e.getMessage());
            synchronized (this) {
                PluginEntry entry = loadedPlugins.get(info.getId());
                if (entry != null) entry.setState(PluginState.FAILED);
            }
            fireEvent(new PluginErrorEvent(info.getId(), e));
        }
        fireEvent(new PluginLoadedEvent(info.getId()));
        return info;
    }

    /**
     * Enable a loaded plugin (calls onEnable).
     */
    public void enablePlugin(String pluginId) {
        final PluginEntry entry;
        synchronized (this) {
            entry = loadedPlugins.get(pluginId);
            if (entry == null) throw new IllegalStateException("Plugin not loaded: " + pluginId);
            if (entry.getState() == PluginState.ENABLED) return;
            if (entry.getState() != PluginState.LOADED && entry.getState() != PluginState.DISABLED)
                throw new IllegalStateException("Plugin not in loadable state: " + pluginId + " (" + entry.getState() + ")");
        }

        try {
            // onEnable runs on current thread for external (not inside runInPlugin ThreadGroup)
            if (entry.getClassLoader() == null) {
                // External runtime: call onEnable directly (ProcessBridge starts in it)
                entry.getPlugin().onEnable(entry.getContext());
            } else {
                runInPlugin(pluginId, () -> entry.getPlugin().onEnable(entry.getContext()));
            }
        } catch (Exception e) {
            System.err.println("[PluginManager] Failed to enable " + pluginId + ": " + e.getMessage());
            synchronized (this) {
                entry.setState(PluginState.FAILED);
            }
            fireEvent(new PluginErrorEvent(pluginId, e));
            return;
        }

        synchronized (this) {
            if (loadedPlugins.get(pluginId) != entry) return;
            entry.setState(PluginState.ENABLED);
            enabledState.put(pluginId, true);
            saveState();
            bumpRevision();
        }
        System.out.println("[PluginManager] Enabled plugin: " + pluginId);
        fireEvent(new PluginEnabledEvent(pluginId));
    }

    /**
     * Disable an enabled plugin (calls onDisable).
     */
    public void disablePlugin(String pluginId) {
        final PluginEntry entry;
        synchronized (this) {
            entry = loadedPlugins.get(pluginId);
            if (entry == null) return;
            if (entry.getState() != PluginState.ENABLED) return;
        }

        try {
            if (entry.getClassLoader() == null) {
                // External runtime: call onDisable directly
                entry.getPlugin().onDisable();
            } else {
                runInPlugin(pluginId, () -> entry.getPlugin().onDisable());
            }
        } catch (Exception e) {
            System.err.println("[PluginManager] onDisable failed for " + pluginId + ": " + e.getMessage());
        }

        synchronized (this) {
            if (loadedPlugins.get(pluginId) != entry) return;
            unregisterAllExtensions(pluginId);

            // Skip thread shutdown for external runtime plugins (no ThreadGroup)
            if (entry.getClassLoader() != null) {
                shutdownPluginThreads(entry);
            }

            entry.setState(PluginState.DISABLED);
            enabledState.put(pluginId, false);
            saveState();
            bumpRevision();
        }
        System.out.println("[PluginManager] Disabled plugin: " + pluginId);
        fireEvent(new PluginDisabledEvent(pluginId));
    }

    /**
     * Remove every host-side bookkeeping entry for a plugin. Idempotent and
     * call-site agnostic (used by both [disablePlugin] and [unloadPlugin])
     * so unload never depends on disable's state-machine having run.
     *
     * Must be invoked while holding the PluginManager monitor.
     */
    private void unregisterAllExtensions(String pluginId) {
        customCommands.remove(pluginId);
        customPages.remove(pluginId);
        customSettingsSections.remove(pluginId);
        customMenuActions.remove(pluginId);
        customStatusBarActions.remove(pluginId);
        customHomeCards.remove(pluginId);
        customThemePacks.remove(pluginId);
        navBadges.remove(pluginId);
        hiddenBuiltinNav.remove(pluginId);
        clearPluginStrings(pluginId, "");
        cancelAllPluginTasks(pluginId);
        eventListeners.removeIf(l -> l instanceof TrackedEventListener &&
                ((TrackedEventListener) l).pluginId.equals(pluginId));
        launchHooks.removeIf(h -> h instanceof TrackedLaunchHook &&
                ((TrackedLaunchHook) h).pluginId.equals(pluginId));
        urlRewriteHooks.removeIf(h -> h.pluginId.equals(pluginId));
    }

    /**
     * Unload a plugin completely (disable + remove from memory).
     */
    public void unloadPlugin(String pluginId) {
        disablePlugin(pluginId);
        PluginEntry entry;
        synchronized (this) {
            entry = loadedPlugins.remove(pluginId);
            if (entry != null) {
                // Ensure threads are torn down even if already DISABLED
                // (disablePlugin only runs the ENABLED → DISABLED path).
                // Skip thread shutdown for external runtime plugins (no ThreadGroup)
                if (entry.getClassLoader() != null) {
                    shutdownPluginThreads(entry);
                }
            }
            // 双保险：即使插件从未 enable / disablePlugin 因 state 提前返回，
            // 这一步也保证所有 host-side 注册表清空（监听器、钩子、命令、页面…）
            unregisterAllExtensions(pluginId);
            bumpRevision();
        }
        // External runtime plugins (e.g. .NET via ProcessBridge) have a null
        // classLoader — there is no isolated ClassLoader to close.
        if (entry != null && entry.getClassLoader() != null) {
            try {
                entry.getClassLoader().close();
            } catch (IOException e) {
                System.err.println("[PluginManager] Failed to close classloader for " + pluginId);
            }
            // 跟踪已关闭的 loader：之后可用 getUnreclaimedPluginLoaders()
            // 探测它是否真的被 GC 回收（未被回收 = 存在强引用泄漏）。
            leakDetector.track(pluginId, entry.getClassLoader());
        }
        System.out.println("[PluginManager] Unloaded plugin: " + pluginId);
    }

    /**
     * Disable all plugins and shut down the event executor. Idempotent.
     */
    public void close() {
        List<String> ids;
        synchronized (this) {
            ids = new ArrayList<>(loadedPlugins.keySet());
        }
        for (String id : ids) {
            try {
                unloadPlugin(id);
            } catch (Throwable e) {
                System.err.println("[PluginManager] unload on close failed for " + id + ": " + e.getMessage());
            }
        }
        synchronized (this) {
            try {
                eventExecutor.shutdownNow();
            } catch (Throwable ignored) {}
            try {
                pluginScheduler.shutdownNow();
            } catch (Throwable ignored) {}
            notifications.clear();
            dialogRequests.clear();
            filePickerRequests.clear();
            progressUpdates.clear();
            inputDialogRequests.clear();
            navigationHandler = null;
            launchRequestHandler = null;
            clipboardHandler = null;
            openUrlHandler = null;
            musicBridge = null;
            leakDetector.clear();
            bumpRevision();
        }
    }

    /**
     * Plugin ClassLoaders that have been closed on unload but have <em>not</em>
     * been reclaimed by the GC. A non-empty result means some plugin still holds
     * a strong reference path to its loader (e.g. a global AWT/Beans listener or
     * a JVM shutdown hook registered by the plugin and never removed).
     *
     * <p>Callers should invoke {@code System.gc()} first so the collector has a
     * chance to reclaim genuinely-unreachable loaders before this snapshot.
     *
     * @return unreclaimed plugin ids (empty list = no leaks detected)
     */
    public List<String> getUnreclaimedPluginLoaders() {
        return leakDetector.drainReclaimedAndReportUnreclaimed();
    }

    public void setNavigationHandler(Consumer<String> handler) {
        this.navigationHandler = handler;
    }

    public void setLaunchRequestHandler(Consumer<String> handler) {
        this.launchRequestHandler = handler;
    }

    public void setClipboardHandler(Consumer<String> handler) {
        this.clipboardHandler = handler;
    }

    public void setOpenUrlHandler(Consumer<String> handler) {
        this.openUrlHandler = handler;
    }

    public void setMusicBridge(MusicBridge bridge) {
        this.musicBridge = bridge;
    }

    MusicBridge getMusicBridge() {
        return musicBridge;
    }

    /** UI-owned music transport bridge for [com.pmcl.plugin.api.MusicApi]. */
    public interface MusicBridge {
        MusicPlaybackSummary nowPlaying();
        void pause();
        void resume();
        void stop();
        void playNext();
        void playPrevious();
        void setVolume(int volume);
    }

    Consumer<String> getNavigationHandler() { return navigationHandler; }

    Consumer<String> getLaunchRequestHandler() { return launchRequestHandler; }

    Consumer<String> getClipboardHandler() { return clipboardHandler; }

    Consumer<String> getOpenUrlHandler() { return openUrlHandler; }

    void offerNotification(PluginNotification n) {
        if (n == null) return;
        notifications.offer(n);
        while (notifications.size() > 100) notifications.poll();
        bumpRevision();
    }

    void offerDialog(PluginDialogRequest req) {
        if (req == null) return;
        dialogRequests.offer(req);
        while (dialogRequests.size() > 20) {
            PluginDialogRequest dropped = dialogRequests.poll();
            if (dropped != null && dropped.getOnResult() != null) {
                try { dropped.getOnResult().call(false); } catch (Throwable ignored) {}
            }
        }
        bumpRevision();
    }

    void offerFilePicker(PluginFilePickerRequest req) {
        if (req == null) return;
        filePickerRequests.offer(req);
        while (filePickerRequests.size() > 10) {
            PluginFilePickerRequest dropped = filePickerRequests.poll();
            if (dropped != null && dropped.getOnResult() != null) {
                try { dropped.getOnResult().call(null); } catch (Throwable ignored) {}
            }
        }
        bumpRevision();
    }

    void offerProgress(PluginProgressUpdate update) {
        if (update == null) return;
        progressUpdates.offer(update);
        while (progressUpdates.size() > 50) progressUpdates.poll();
        bumpRevision();
    }

    /** Drain pending plugin notifications for the host UI. */
    public List<PluginNotification> drainNotifications() {
        List<PluginNotification> out = new ArrayList<>();
        PluginNotification n;
        while ((n = notifications.poll()) != null) out.add(n);
        return out;
    }

    /** Drain pending dialog requests for the host UI. */
    public List<PluginDialogRequest> drainDialogs() {
        List<PluginDialogRequest> out = new ArrayList<>();
        PluginDialogRequest d;
        while ((d = dialogRequests.poll()) != null) out.add(d);
        return out;
    }

    /** Drain pending file picker requests for the host UI. */
    public List<PluginFilePickerRequest> drainFilePickers() {
        List<PluginFilePickerRequest> out = new ArrayList<>();
        PluginFilePickerRequest r;
        while ((r = filePickerRequests.poll()) != null) out.add(r);
        return out;
    }

    /** Drain pending input-dialog requests for the host UI. */
    public List<PluginInputDialogRequest> drainInputDialogs() {
        List<PluginInputDialogRequest> out = new ArrayList<>();
        PluginInputDialogRequest r;
        while ((r = inputDialogRequests.poll()) != null) out.add(r);
        return out;
    }

    void offerInputDialog(PluginInputDialogRequest req) {
        if (req == null) return;
        inputDialogRequests.offer(req);
        while (inputDialogRequests.size() > 20) {
            PluginInputDialogRequest dropped = inputDialogRequests.poll();
            if (dropped != null && dropped.getOnResult() != null) {
                try { dropped.getOnResult().call(null); } catch (Throwable ignored) {}
            }
        }
        bumpRevision();
    }

    /** Drain pending progress updates for the host UI. */
    public List<PluginProgressUpdate> drainProgressUpdates() {
        List<PluginProgressUpdate> out = new ArrayList<>();
        PluginProgressUpdate u;
        while ((u = progressUpdates.poll()) != null) out.add(u);
        return out;
    }

    public synchronized List<PluginMenuAction> getCustomMenuActions() {
        List<PluginMenuAction> all = new ArrayList<>();
        for (List<PluginMenuAction> acts : customMenuActions.values()) {
            all.addAll(acts);
        }
        return all;
    }

    public synchronized List<PluginStatusBarAction> getCustomStatusBarActions() {
        List<PluginStatusBarAction> all = new ArrayList<>();
        for (List<PluginStatusBarAction> acts : customStatusBarActions.values()) {
            all.addAll(acts);
        }
        return all;
    }

    public synchronized List<HomeCard> getCustomHomeCards() {
        List<HomeCard> all = new ArrayList<>();
        for (List<HomeCard> cards : customHomeCards.values()) {
            all.addAll(cards);
        }
        all.sort(java.util.Comparator.comparingInt(HomeCard::getOrder));
        return all;
    }

    public synchronized List<NavBadge> getNavBadges() {
        List<NavBadge> out = new ArrayList<>();
        for (Map<String, String> map : navBadges.values()) {
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    out.add(new NavBadge(e.getKey(), e.getValue()));
                }
            }
        }
        return out;
    }

    public synchronized java.util.Set<String> getHiddenBuiltinNavRoutes() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (java.util.Set<String> set : hiddenBuiltinNav.values()) {
            out.addAll(set);
        }
        return out;
    }

    synchronized void setNavBadge(String pluginId, String target, String text) {
        if (text == null || text.isBlank()) {
            clearNavBadge(pluginId, target);
            return;
        }
        navBadges.computeIfAbsent(pluginId, k -> new HashMap<>()).put(target, text.trim());
        bumpRevision();
    }

    synchronized void clearNavBadge(String pluginId, String target) {
        Map<String, String> map = navBadges.get(pluginId);
        if (map != null) {
            map.remove(target);
            if (map.isEmpty()) navBadges.remove(pluginId);
            bumpRevision();
        }
    }

    synchronized void hideBuiltinNav(String pluginId, String route) {
        hiddenBuiltinNav.computeIfAbsent(pluginId, k -> new java.util.HashSet<>())
                .add(route.trim().toLowerCase(java.util.Locale.ROOT));
        bumpRevision();
    }

    synchronized void showBuiltinNav(String pluginId, String route) {
        java.util.Set<String> set = hiddenBuiltinNav.get(pluginId);
        if (set != null) {
            set.remove(route.trim().toLowerCase(java.util.Locale.ROOT));
            if (set.isEmpty()) hiddenBuiltinNav.remove(pluginId);
            bumpRevision();
        }
    }

    synchronized void registerPluginStrings(String pluginId, String language, Map<String, String> strings) {
        com.pmcl.core.i18n.I18n.putPluginStrings(language, strings);
        Map<String, java.util.Set<String>> byLang =
                pluginStringKeys.computeIfAbsent(pluginId, k -> new HashMap<>());
        java.util.Set<String> keys = byLang.computeIfAbsent(language, k -> new java.util.HashSet<>());
        keys.addAll(strings.keySet());
    }

    synchronized void clearPluginStrings(String pluginId, String language) {
        Map<String, java.util.Set<String>> byLang = pluginStringKeys.get(pluginId);
        if (byLang == null) return;
        if (language == null || language.isBlank()) {
            for (Map.Entry<String, java.util.Set<String>> e : byLang.entrySet()) {
                com.pmcl.core.i18n.I18n.removePluginStrings(e.getKey(), e.getValue());
            }
            pluginStringKeys.remove(pluginId);
        } else {
            java.util.Set<String> keys = byLang.remove(language);
            if (keys != null) com.pmcl.core.i18n.I18n.removePluginStrings(language, keys);
            if (byLang.isEmpty()) pluginStringKeys.remove(pluginId);
        }
    }

    private static final int MAX_TASKS_PER_PLUGIN = 16;
    private static final long MIN_REPEAT_PERIOD_MS = 250L;

    synchronized String schedulePluginTask(String pluginId, long delayMs, long periodMs, Runnable task) {
        Map<String, java.util.concurrent.ScheduledFuture<?>> existing =
                scheduledTasks.computeIfAbsent(pluginId, k -> new HashMap<>());
        if (existing.size() >= MAX_TASKS_PER_PLUGIN) {
            throw new IllegalStateException("Plugin '" + pluginId + "' exceeded scheduled task limit ("
                    + MAX_TASKS_PER_PLUGIN + ")");
        }
        long delay = Math.max(0L, delayMs);
        long period = periodMs;
        if (period > 0) period = Math.max(MIN_REPEAT_PERIOD_MS, period);
        String id = java.util.UUID.randomUUID().toString();
        Runnable wrapped = () -> {
            try {
                runInPlugin(pluginId, task);
            } catch (Throwable t) {
                System.err.println("[Plugin:" + pluginId + "] scheduled task error: " + t.getMessage());
            }
        };
        java.util.concurrent.ScheduledFuture<?> future;
        if (period > 0) {
            future = pluginScheduler.scheduleAtFixedRate(wrapped, delay, period,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            future = pluginScheduler.schedule(wrapped, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        existing.put(id, future);
        return id;
    }

    synchronized void cancelPluginTask(String pluginId, String taskId) {
        Map<String, java.util.concurrent.ScheduledFuture<?>> map = scheduledTasks.get(pluginId);
        if (map == null) return;
        java.util.concurrent.ScheduledFuture<?> f = map.remove(taskId);
        if (f != null) f.cancel(true);
        if (map.isEmpty()) scheduledTasks.remove(pluginId);
    }

    synchronized void cancelAllPluginTasks(String pluginId) {
        Map<String, java.util.concurrent.ScheduledFuture<?>> map = scheduledTasks.remove(pluginId);
        if (map == null) return;
        for (java.util.concurrent.ScheduledFuture<?> f : map.values()) {
            f.cancel(true);
        }
    }

    /** Last plugin cancel reason from beforeLaunch (may be null). */
    public String getLastLaunchCancelReason() {
        return lastLaunchCancelReason;
    }

    Map<String, String> getPluginConfigMap(String pluginId) {
        return pluginConfigs.get(pluginId);
    }

    void setPluginConfigValue(String pluginId, String key, String value) {
        synchronized (this) {
            pluginConfigs.computeIfAbsent(pluginId, k -> new HashMap<>()).put(key, value);
            saveState();
        }
    }

    /**
     * Execute plugin code on a worker belonging to the plugin's {@link ThreadGroup}
     * so {@code new Thread(...)} inherits that group and can be interrupted on unload.
     */
    void runInPlugin(String pluginId, Runnable task) {
        if (task == null) return;
        PluginEntry entry = loadedPlugins.get(pluginId);
        if (entry == null || entry.threads().isDestroyed()) {
            // 插件已卸载：丢弃任务而非内联执行，避免 use-after-unload 和错误 CCL
            System.err.println("[PluginManager] Dropping task for unloaded plugin: " + pluginId);
            return;
        }
        entry.threads().run(task);
    }

    <T> T callInPlugin(String pluginId, java.util.concurrent.Callable<T> task) {
        if (task == null) throw new NullPointerException("task");
        PluginEntry entry = loadedPlugins.get(pluginId);
        if (entry == null || entry.threads().isDestroyed()) {
            // 插件已卸载：拒绝执行而非内联调用
            throw new IllegalStateException("Plugin unloaded: " + pluginId);
        }
        return entry.threads().call(task);
    }

    Thread newPluginThread(String pluginId, String name, Runnable task) {
        PluginEntry entry = loadedPlugins.get(pluginId);
        if (entry == null || entry.threads().isDestroyed()) {
            throw new IllegalStateException("Plugin not loaded or thread group destroyed: " + pluginId);
        }
        return entry.threads().newThread(name, task);
    }

    java.util.concurrent.ThreadFactory pluginThreadFactory(String pluginId) {
        PluginEntry entry = loadedPlugins.get(pluginId);
        if (entry == null || entry.threads().isDestroyed()) {
            throw new IllegalStateException("Plugin not loaded or thread group destroyed: " + pluginId);
        }
        return entry.threads().threadFactory("pmcl-plugin-" + pluginId + "-");
    }

    private void shutdownPluginThreads(PluginEntry entry) {
        if (entry == null) return;
        try {
            entry.threads().shutdown(PLUGIN_THREAD_SHUTDOWN_WAIT_MS);
        } catch (Throwable t) {
            System.err.println("[PluginManager] Failed to shut down threads for "
                    + entry.getInfo().getId() + ": " + t.getMessage());
        }
    }

    /**
     * Reload a plugin (unload + load + enable if previously enabled).
     * <p>
     * Must NOT hold the PluginManager monitor across {@link #enablePlugin}/{@link #unloadPlugin}:
     * those call into the plugin ThreadGroup (spawn+join), and plugin code re-enters
     * {@code synchronized (manager)} for registration APIs — holding the lock here deadlocks
     * the host (UI freezes on reload).
     */
    public void reloadPlugin(String pluginId) throws Exception {
        final boolean wasEnabled;
        final Path sourcePath;
        final boolean wasPackage;
        synchronized (this) {
            PluginEntry entry = loadedPlugins.get(pluginId);
            if (entry == null) throw new IllegalStateException("Plugin not loaded: " + pluginId);
            wasEnabled = entry.getState() == PluginState.ENABLED;
            sourcePath = entry.getJarPath();
            wasPackage = entry.isPackage();
        }
        unloadPlugin(pluginId);
        if (wasPackage) {
            loadPluginPackage(sourcePath);
        } else {
            loadPlugin(sourcePath);
        }
        if (wasEnabled) enablePlugin(pluginId);
    }

    // ==================== Install / Uninstall ====================

    /**
     * Install a plugin from a local JAR file.
     * Copies the JAR to ~/.pmcl/plugins/ and loads it.
     */
    public PluginInfo installFromPath(Path sourceJar) throws Exception {
        PluginInfo info = parseDescriptor(sourceJar);
        info.validate();

        Path target = pluginsDir.resolve(info.getId() + ".jar");
        Files.copy(sourceJar, target, StandardCopyOption.REPLACE_EXISTING);

        // If already loaded, unload first (must not hold manager lock — see reloadPlugin)
        boolean alreadyLoaded;
        synchronized (this) {
            alreadyLoaded = loadedPlugins.containsKey(info.getId());
        }
        if (alreadyLoaded) {
            unloadPlugin(info.getId());
        }

        loadPlugin(target);
        enablePlugin(info.getId());
        return info;
    }

    /**
     * Install a plugin from a URL.
     * Downloads the JAR first, then installs.
     * <p>
     * S4+M69 安全修复：URL 必须通过 SSRF 校验，禁止指向内网/回环/链路本地地址，
     * 防止用户被诱导从内部服务下载恶意插件。
     */
    public PluginInfo installFromUrl(String url) throws Exception {
        String ssrfError = com.pmcl.core.util.SsrfChecker.validate(url);
        if (ssrfError != null) {
            throw new IllegalArgumentException("Plugin URL rejected (SSRF protection): " + ssrfError);
        }
        Path tempFile = Files.createTempFile("pmcl-plugin-", ".jar");
        try {
            System.out.println("[PluginManager] Downloading plugin from: " + url);
            core.downloads().downloadToSsrfChecked(url, tempFile);
            return installFromPath(tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // ==================== Plugin Package (.ppk) Support ====================

    /**
     * Load a plugin from a .ppk package file.
     * Extracts the package to ~/.pmcl/plugins/&lt;pluginId&gt;/ and loads the main class.
     * Does NOT copy the .ppk file to the plugins directory (use [installFromPackage] for that).
     *
     * @param ppkPath Path to the .ppk file
     * @return the PluginInfo of the loaded plugin
     * @throws Exception if loading fails
     */
    public PluginInfo loadPluginPackage(Path ppkPath) throws Exception {
        // C2: .ppk 与 JAR 相同——必须 jarsigner 验签 + 可信指纹；禁止「仅解压即入 classpath」
        verifyPluginArchive(ppkPath, true);

        final PluginInfo info;
        final PmclPlugin plugin;
        final int ktCount;
        final int javaCount;
        synchronized (this) {
            // Parse and validate the package manifest
            PluginPackage pkg = PluginPackageParser.parse(ppkPath);
            info = pkg.getInfo();
            ktCount = pkg.getKotlinSources().size();
            javaCount = pkg.getJavaSources().size();

            // Idempotent: discover/scan may revisit an already-loaded .ppk
            PluginEntry existing = loadedPlugins.get(info.getId());
            if (existing != null) {
                return existing.getInfo();
            }

            // Check dependencies
            for (String dep : info.getDependencies()) {
                if (!loadedPlugins.containsKey(dep)) {
                    throw new IllegalStateException(
                            "Plugin '" + info.getId() + "' requires dependency '" + dep +
                            "' which is not loaded. Install/load it first.");
                }
            }

            // Extract the package to a per-plugin directory
            Path packageDir = PluginPackageBuilder.getPackageDir(pluginsDir, info.getId());
            PluginPackageBuilder.extract(ppkPath, packageDir);

            // External runtime / window-embed plugins skip classloader setup
            if (info.getExternalRuntime() != null || info.getEmbed() != null) {
                loadExternalRuntimePlugin(info, packageDir);
                return info;
            }

            // Validate runtime structure (must have classes/)
            PluginPackageBuilder.validateRuntimeStructure(packageDir);

            // Create classloader from classes/ + lib/*.jar — 使用隔离 ClassLoader
            PluginIsolatingClassLoader classLoader = PluginPackageBuilder.createClassLoader(
                    packageDir, pkg, getClass().getClassLoader());

            PmclPlugin instance;
            try {
                Class<?> mainClass = classLoader.loadClass(info.getMainClass());
                if (!PmclPlugin.class.isAssignableFrom(mainClass)) {
                    classLoader.close();
                    throw new ClassCastException("Main class " + info.getMainClass() +
                            " does not implement PmclPlugin");
                }
                instance = (PmclPlugin) mainClass.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                try { classLoader.close(); } catch (Exception ignored) {}
                throw t;
            }

            PluginContextImpl ctx = new PluginContextImpl(this, info.getId());
            PluginEntry entry = new PluginEntry(info, instance, ctx, classLoader, ppkPath, true);
            entry.setState(PluginState.LOADED);
            loadedPlugins.put(info.getId(), entry);
            plugin = instance;

            System.out.println("[PluginManager] Loaded plugin package: " + info.getId() + " v" + info.getVersion() +
                    " (" + ktCount + " kt, " + javaCount + " java files)");
            bumpRevision();
        }

        try {
            runInPlugin(info.getId(), plugin::onLoad);
        } catch (Exception e) {
            System.err.println("[PluginManager] onLoad failed for " + info.getId() + ": " + e.getMessage());
            synchronized (this) {
                PluginEntry entry = loadedPlugins.get(info.getId());
                if (entry != null) entry.setState(PluginState.FAILED);
            }
            fireEvent(new PluginErrorEvent(info.getId(), e));
        }
        fireEvent(new PluginLoadedEvent(info.getId()));
        return info;
    }

    /**
     * Install a plugin from a .ppk package file.
     * Copies the .ppk to ~/.pmcl/plugins/&lt;pluginId&gt;.ppk, extracts, and loads.
     *
     * @param ppkPath Path to the .ppk file
     * @return the PluginInfo of the installed plugin
     * @throws Exception if installation fails
     */
    public PluginInfo installFromPackage(Path ppkPath) throws Exception {
        // Parse to get the plugin ID for naming the target file
        PluginPackage pkg = PluginPackageParser.parse(ppkPath);
        PluginInfo info = pkg.getInfo();

        // Copy .ppk to plugins dir
        Path targetPpk = pluginsDir.resolve(info.getId() + ".ppk");
        Files.copy(ppkPath, targetPpk, StandardCopyOption.REPLACE_EXISTING);

        // If already loaded, unload first (must not hold manager lock — see reloadPlugin)
        boolean alreadyLoaded;
        synchronized (this) {
            alreadyLoaded = loadedPlugins.containsKey(info.getId());
        }
        if (alreadyLoaded) {
            unloadPlugin(info.getId());
        }

        // Load from the copied .ppk
        loadPluginPackage(targetPpk);
        enablePlugin(info.getId());
        return info;
    }

    /**
     * Install a plugin package from a URL.
     * Downloads the .ppk first, then installs.
     * <p>
     * S4+M69 安全修复：URL 必须通过 SSRF 校验，禁止指向内网/回环/链路本地地址。
     */
    public PluginInfo installFromPackageUrl(String url) throws Exception {
        String ssrfError = com.pmcl.core.util.SsrfChecker.validate(url);
        if (ssrfError != null) {
            throw new IllegalArgumentException("Plugin package URL rejected (SSRF protection): " + ssrfError);
        }
        Path tempFile = Files.createTempFile("pmcl-plugin-", ".ppk");
        try {
            System.out.println("[PluginManager] Downloading plugin package from: " + url);
            core.downloads().downloadToSsrfChecked(url, tempFile);
            return installFromPackage(tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Uninstall a plugin (unload + delete JAR/.ppk + delete extracted files).
     * <p>
     * M27 修复：默认保留用户数据目录（plugins/&lt;id&gt;/data/），避免卸载后重新安装
     * 同 id 插件时丢失配置/存档/缓存。如需彻底清除，调用 [uninstallPlugin(id, false)]。
     *
     * @param pluginId plugin id
     */
    public void uninstallPlugin(String pluginId) throws IOException {
        uninstallPlugin(pluginId, true);
    }

    /**
     * Uninstall a plugin with control over user data preservation.
     *
     * @param pluginId plugin id
     * @param keepUserData true to preserve plugins/&lt;id&gt;/data/, false to delete everything
     */
    public void uninstallPlugin(String pluginId, boolean keepUserData) throws IOException {
        // unload/onDisable must run without holding the manager monitor (see reloadPlugin)
        unloadPlugin(pluginId);
        // Delete the source file (could be .jar or .ppk)
        Path jar = pluginsDir.resolve(pluginId + ".jar");
        Path ppk = pluginsDir.resolve(pluginId + ".ppk");
        Files.deleteIfExists(jar);
        Files.deleteIfExists(ppk);
        // M27 修复：删除解压的包文件，但可选保留 data/ 目录（用户数据）。
        Path pluginDir = pluginsDir.resolve(pluginId);
        Path dataDir = pluginDir.resolve("data");
        if (Files.exists(pluginDir)) {
            if (keepUserData && Files.exists(dataDir)) {
                // 保留 data/：删除 pluginDir 下除 data/ 外的所有内容
                try (var stream = Files.walk(pluginDir)) {
                    stream.filter(p -> !p.equals(pluginDir))                          // 不删 pluginDir 本身
                          .filter(p -> !p.equals(dataDir) && !p.startsWith(dataDir))  // 保留 data/ 及其内容
                          .sorted((a, b) -> b.compareTo(a))                            // 反序：子先于父
                          .forEach(p -> {
                              try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                          });
                }
                // pluginDir 仍包含 data/，保留它
            } else {
                // 不保留数据：删除整个 pluginDir
                deleteDirectory(pluginDir);
            }
        }
        enabledState.remove(pluginId);
        pluginConfigs.remove(pluginId);
        saveState();
        String dataNote = (keepUserData && Files.exists(dataDir))
                ? " (user data preserved at " + dataDir + ")"
                : "";
        System.out.println("[PluginManager] Uninstalled plugin: " + pluginId + dataNote);
        bumpRevision();
    }

    // ==================== Query ====================

    public synchronized List<PluginEntry> getLoadedPlugins() {
        return Collections.unmodifiableList(new ArrayList<>(loadedPlugins.values()));
    }

    /** Failures from the most recent {@link #discoverAndLoadAll()} (empty if none). */
    public synchronized List<String> getLastDiscoveryErrors() {
        return Collections.unmodifiableList(new ArrayList<>(lastDiscoveryErrors));
    }

    public synchronized PluginEntry getPlugin(String pluginId) {
        return loadedPlugins.get(pluginId);
    }

    public synchronized boolean isLoaded(String pluginId) {
        return loadedPlugins.containsKey(pluginId);
    }

    public synchronized boolean isEnabled(String pluginId) {
        return enabledState.getOrDefault(pluginId, false); // default disabled for security
    }

    /** Revision counter for detecting structural changes (GUI polls this). */
    public long getRevision() {
        return revision;
    }

    /** Increment revision (call after any structural change). */
    private void bumpRevision() {
        revision++;
    }

    public synchronized List<RegisteredCommand> getCustomCommands() {
        List<RegisteredCommand> all = new ArrayList<>();
        for (List<RegisteredCommand> cmds : customCommands.values()) {
            all.addAll(cmds);
        }
        return all;
    }

    public synchronized List<RegisteredPage> getCustomPages() {
        List<RegisteredPage> all = new ArrayList<>();
        for (List<RegisteredPage> pages : customPages.values()) {
            all.addAll(pages);
        }
        return all;
    }

    /** Settings sections registered by enabled plugins. */
    public synchronized List<RegisteredPage> getCustomSettingsSections() {
        List<RegisteredPage> all = new ArrayList<>();
        for (List<RegisteredPage> sections : customSettingsSections.values()) {
            all.addAll(sections);
        }
        return all;
    }

    /**
     * Get all theme packs registered by enabled plugins.
     * Used by Settings UI to populate the theme picker.
     */
    public synchronized List<com.pmcl.plugin.ThemePack> getCustomThemePacks() {
        List<com.pmcl.plugin.ThemePack> all = new ArrayList<>();
        for (List<com.pmcl.plugin.ThemePack> packs : customThemePacks.values()) {
            all.addAll(packs);
        }
        return all;
    }

    /**
     * Find a registered theme pack by ID.
     * Returns null if not found or the owning plugin is disabled.
     */
    public synchronized com.pmcl.plugin.ThemePack findThemePack(String packId) {
        for (List<com.pmcl.plugin.ThemePack> packs : customThemePacks.values()) {
            for (com.pmcl.plugin.ThemePack pack : packs) {
                if (pack.getId().equals(packId)) return pack;
            }
        }
        return null;
    }

    /**
     * Get the plugin ID that owns a theme pack.
     * Returns null if the pack is not found.
     */
    public synchronized String getThemePackOwner(String packId) {
        for (Map.Entry<String, List<com.pmcl.plugin.ThemePack>> entry : customThemePacks.entrySet()) {
            for (com.pmcl.plugin.ThemePack pack : entry.getValue()) {
                if (pack.getId().equals(packId)) return entry.getKey();
            }
        }
        return null;
    }

    public List<LaunchHook> getLaunchHooks() {
        return Collections.unmodifiableList(launchHooks);
    }

    // ==================== Event System ====================

    public void fireEvent(PmclEvent event) {
        // M24 修复：异步派发——快照 listeners，提交到线程池，避免慢 listener 阻塞调用方。
        if (eventExecutor.isShutdown()) return;
        for (EventListener listener : eventListeners) {
            try {
                eventExecutor.submit(() -> {
                    try {
                        if (listener instanceof TrackedEventListener tracked) {
                            runInPlugin(tracked.pluginId, () -> tracked.delegate.onEvent(event));
                        } else {
                            listener.onEvent(event);
                        }
                    } catch (Exception e) {
                        System.err.println("[PluginManager] Event listener error: " + e.getMessage());
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Executor already shutting down
            }
        }
    }

    // ==================== Launch Hooks ====================

    public boolean beforeLaunch(String versionId, String accountName) {
        lastLaunchCancelReason = null;
        for (LaunchHook hook : launchHooks) {
            try {
                if (!hook.beforeLaunch(versionId, accountName)) {
                    String reason = null;
                    try {
                        reason = hook.cancelReason();
                    } catch (Throwable ignored) {}
                    if (reason == null || reason.isBlank()) {
                        reason = "Launch cancelled by plugin hook";
                    }
                    lastLaunchCancelReason = reason;
                    System.out.println("[PluginManager] Launch cancelled: " + reason);
                    return false;
                }
            } catch (Exception e) {
                // fail-closed：钩子异常不得被当成「放行」
                lastLaunchCancelReason = "Launch hook error: " + e.getMessage();
                System.err.println("[PluginManager] Launch hook error (abort launch): " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

    public void afterLaunch(String versionId, int exitCode) {
        for (LaunchHook hook : launchHooks) {
            try {
                hook.afterLaunch(versionId, exitCode);
            } catch (Exception e) {
                System.err.println("[PluginManager] Launch hook error: " + e.getMessage());
            }
        }
    }

    /**
     * Append plugin-contributed JVM / game args onto an already-built profile.
     * Blank entries and args containing control characters are skipped.
     */
    public void applyLaunchContributions(com.pmcl.core.launch.LaunchProfile profile) {
        if (profile == null) return;
        String versionId = profile.getVersionId() != null ? profile.getVersionId() : "";
        String accountName = profile.getPlayerName() != null ? profile.getPlayerName() : "Player";
        for (LaunchHook hook : launchHooks) {
            try {
                List<String> jvm = hook.contributeJvmArgs(versionId, accountName);
                if (jvm != null) {
                    for (String arg : jvm) {
                        if (isSafeLaunchArg(arg)) profile.addJvmArg(arg.trim());
                    }
                }
            } catch (Exception e) {
                System.err.println("[PluginManager] contributeJvmArgs error: " + e.getMessage());
            }
            try {
                List<String> game = hook.contributeGameArgs(versionId, accountName);
                if (game != null) {
                    for (String arg : game) {
                        if (isSafeLaunchArg(arg)) profile.addGameArg(arg.trim());
                    }
                }
            } catch (Exception e) {
                System.err.println("[PluginManager] contributeGameArgs error: " + e.getMessage());
            }
            try {
                Map<String, String> env = hook.contributeEnv(versionId, accountName);
                if (env != null) {
                    for (Map.Entry<String, String> e : env.entrySet()) {
                        if (isSafeEnvKey(e.getKey())) {
                            profile.putEnv(e.getKey().trim(), e.getValue() != null ? e.getValue() : "");
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[PluginManager] contributeEnv error: " + e.getMessage());
            }
            try {
                List<String> jars = hook.contributeClasspathJars(versionId, accountName);
                if (jars != null) {
                    String ownerId = (hook instanceof TrackedLaunchHook)
                            ? ((TrackedLaunchHook) hook).pluginId : null;
                    for (String jar : jars) {
                        if (jar == null || jar.isBlank()) continue;
                        String raw = jar.trim();
                        // Reject obvious traversal / UNC / absolute escapes before resolve
                        if (raw.indexOf('\0') >= 0 || raw.contains("..")) {
                            System.err.println("[PluginManager] Rejected classpath jar (path traversal): " + raw);
                            continue;
                        }
                        Path p;
                        try {
                            p = Paths.get(raw).toAbsolutePath().normalize();
                        } catch (Exception ex) {
                            System.err.println("[PluginManager] Rejected classpath jar (bad path): " + raw);
                            continue;
                        }
                        if (!Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                        if (Files.isSymbolicLink(p) || !isPathUnderPluginDir(ownerId, p)) {
                            System.err.println("[PluginManager] Rejected classpath jar outside plugin dir: " + p);
                            continue;
                        }
                        profile.addClasspath(p);
                    }
                }
            } catch (Exception e) {
                System.err.println("[PluginManager] contributeClasspathJars error: " + e.getMessage());
            }
            try {
                // SECURITY: Java agents receive Instrumentation and bypass plugin classloader
                // isolation. Never apply contributeJavaAgents — same policy as blocking
                // -javaagent in plugin JVM args (isSafePluginJvmArg).
                List<String> agents = hook.contributeJavaAgents(versionId, accountName);
                if (agents != null && !agents.isEmpty()) {
                    String ownerId = (hook instanceof TrackedLaunchHook)
                            ? ((TrackedLaunchHook) hook).pluginId : "?";
                    System.err.println("[PluginManager] SECURITY: rejected "
                            + agents.size() + " javaagent contribution(s) from plugin '"
                            + ownerId + "' (agents are not allowed — would bypass sandbox)");
                }
            } catch (Exception e) {
                System.err.println("[PluginManager] contributeJavaAgents error: " + e.getMessage());
            }
        }
    }

    private static boolean isSafeEnvKey(String key) {
        if (key == null || key.isBlank()) return false;
        String k = key.trim();
        if (k.contains("=") || k.contains("\0") || k.contains("\n") || k.contains("\r")) return false;
        // Allowlist only — prevents CLASSPATH / JAVA_HOME / BASH_ENV style injection
        return k.matches("PMCL_PLUGIN_[A-Z0-9_]{1,64}");
    }

    String applyUrlRewrites(String url) {
        if (url == null || url.isBlank() || urlRewriteHooks.isEmpty()) return url;
        String current = url;
        for (TrackedUrlRewriteHook hook : urlRewriteHooks) {
            try {
                String next = hook.delegate.rewrite(current);
                if (next != null && !next.isBlank() && !next.equals(current)) {
                    // Post-rewrite SSRF gate: reject private/link-local targets
                    String ssrf = com.pmcl.core.util.SsrfChecker.validate(next);
                    if (ssrf != null) {
                        System.err.println("[PluginManager] UrlRewriteHook SSRF blocked ("
                                + hook.pluginId + "): " + ssrf);
                        continue;
                    }
                    fireEvent(new UrlRewrittenEvent(current, next, hook.pluginId));
                    current = next;
                }
            } catch (Exception e) {
                System.err.println("[PluginManager] UrlRewriteHook error (" + hook.pluginId + "): " + e.getMessage());
            }
        }
        return current;
    }

    /**
     * SSRF + optional host allowlist ({@code -Dpmcl.plugins.httpAllowHosts}) for HttpApi.
     * @return error message, or null if allowed
     */
    String validatePluginHttpUrl(String url) {
        String ssrf = com.pmcl.core.util.SsrfChecker.validate(url);
        if (ssrf != null) return ssrf;
        Set<String> allow = securityPolicy.httpAllowHosts;
        if (allow == null || allow.isEmpty()) return null;
        try {
            String host = new java.net.URL(url).getHost();
            if (host == null || host.isBlank()) return "URL host is missing";
            String h = host.trim().toLowerCase(java.util.Locale.ROOT);
            if (!allow.contains(h)) {
                return "Host '" + h + "' not in -Dpmcl.plugins.httpAllowHosts allowlist";
            }
        } catch (Exception e) {
            return "Malformed URL: " + e.getMessage();
        }
        return null;
    }

    /** Throws if plugin HTTP rate limit (60/min) is exceeded. */
    void acquirePluginHttpPermit() {
        long now = System.currentTimeMillis();
        synchronized (httpRateLock) {
            if (httpRateWindowStartMs == 0L || now - httpRateWindowStartMs >= 60_000L) {
                httpRateWindowStartMs = now;
                httpRateWindowCount = 0;
            }
            httpRateWindowCount++;
            if (httpRateWindowCount > HTTP_RATE_LIMIT_PER_MINUTE) {
                throw new RuntimeException("Plugin HTTP rate limit exceeded ("
                        + HTTP_RATE_LIMIT_PER_MINUTE + "/min)");
            }
        }
    }

    private boolean isPathUnderPluginDir(String pluginId, Path absPath) {
        if (pluginId == null || pluginId.isBlank() || absPath == null) return false;
        // Only allow jars under ~/.pmcl/plugins/<pluginId>/ (package + data); reject traversal.
        Path base = pluginsDir.resolve(pluginId).toAbsolutePath().normalize();
        return PluginPathSandbox.isUnderPluginData(absPath, base);
    }

    private static boolean isSafeLaunchArg(String arg) {
        return isSafePluginJvmArg(arg);
    }

    /**
     * Shared JVM-arg policy for LaunchHook contributions and SettingsApi custom args.
     * Blocks agent / bootclasspath / OnError shell hooks and similar RCE vectors.
     */
    static boolean isSafePluginJvmArg(String arg) {
        if (arg == null) return false;
        String t = arg.trim();
        if (t.isEmpty() || t.length() > 2048) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
        }
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("-javaagent")
                || lower.startsWith("-agentpath")
                || lower.startsWith("-agentlib")
                || lower.startsWith("-xbootclasspath")
                || lower.startsWith("-xx:onerror")
                || lower.startsWith("-xx:onoutofmemoryerror")
                || lower.startsWith("-xx:runpath")
                || lower.startsWith("-xx:startflightrecording")
                || lower.startsWith("-xx:+startflightrecording")
                || lower.startsWith("-xx:flightrecorderoptions")
                || lower.startsWith("-xx:+flightrecorder")
                || lower.startsWith("-xx:-flightrecorder")
                || lower.startsWith("-xx:vmoptionsfile")
                || lower.startsWith("-xx:flags")
                || lower.startsWith("-xx:errorfile")
                || lower.startsWith("-xx:heapdumppath")
                || lower.startsWith("-xx:+heapdumponoutofmemoryerror")
                || lower.startsWith("-xx:logflags")
                || lower.startsWith("-xx:replaydatafile")
                || lower.startsWith("-xx:sharedarchivefile")
                || lower.startsWith("-xx:archivesclassesatexit")
                || lower.contains("startflightrecording")
                || lower.contains("java.security.manager")
                || lower.startsWith("-djava.class.path")
                || lower.startsWith("-djava.library.path")
                || lower.startsWith("-djava.home")
                || lower.startsWith("-djava.agent")
                || lower.startsWith("-dcom.sun.management")
                || lower.startsWith("-djdk.attach")
                || lower.startsWith("-djdk.instrument")
                || lower.startsWith("--module-path")
                || lower.startsWith("--upgrade-module-path")
                || lower.startsWith("--add-opens")
                || lower.startsWith("--add-exports")
                || lower.startsWith("--add-modules")
                || lower.startsWith("--patch-module")
                || lower.equals("-p")
                || lower.startsWith("-p=")) {
            return false;
        }
        return true;
    }

    // ==================== Descriptor Parsing ====================

    private PluginInfo parseDescriptor(Path jarPath) throws Exception {
        ensureUrlStreamHandlerGuard();
        // M28 修复：启用 JAR 签名校验。
        // - new JarFile(file, true) 开启验签：读取任何 signed entry 时自动校验
        //   若 JAR 被签名且 entry 被篡改 → getInputStream() 抛 SecurityException
        // - 后续 classLoader.loadClass() 读取 .class entry 时也会触发验签
        // - 若 JAR 未签名：允许加载（向后兼容现有插件生态），但记录警告
        // - 若 JAR 已签名但验签失败：SecurityException 阻止加载被重打包的恶意插件
        try (JarFile jar = new JarFile(jarPath.toFile(), true)) {
            // 检测 JAR 是否包含签名块（META-INF/*.SF / *.RSA / *.DSA / *.EC）
            boolean isSigned = false;
            try (var entryStream = jar.stream()) {
                isSigned = entryStream.anyMatch(e -> {
                    String n = e.getName();
                    return n.startsWith("META-INF/") &&
                            (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"));
                });
            }
            if (!isSigned) {
                // 默认要求签名；仅启动时冻结的 allowUnsigned 可放行（C3：运行时 setProperty 无效）
                if (!securityPolicy.allowUnsignedJars) {
                    throw new SecurityException("Plugin JAR is not signed: " + jarPath
                            + " (start with -Dpmcl.plugins.allowUnsigned=true to allow)");
                }
                System.err.println("[PluginManager] WARNING: loading unsigned plugin JAR (" +
                        jarPath + ") — integrity cannot be verified against tampering.");
            }

            JarEntry entry = jar.getJarEntry(PluginInfo.PROPERTIES_PATH);
            if (entry == null) {
                throw new IllegalArgumentException(
                        "Missing " + PluginInfo.PROPERTIES_PATH + " in plugin JAR. " +
                        "A plugin JAR must contain this descriptor file at exactly this path " +
                        "(case-sensitive). See PluginInfo docs for format specification.");
            }
            Properties props = new Properties();
            // M21 修复：Properties.load(InputStream) 默认 ISO-8859-1，中文插件名/描述会乱码。
            // 改用 Reader + UTF-8，与 .ppk 包内 META-INF/pmcl-plugin.properties 的实际编码一致。
            // M28：getInputStream 在 verify=true 模式下会自动验签当前 entry
            try (InputStream is = jar.getInputStream(entry)) {
                props.load(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
            }
            // 签名通过 ≠ 信任：所有关键 entry（含全部 .class）的 CodeSigner 必须命中可信指纹
            if (isSigned) {
                assertAllSignedEntries(jar, jarPath, false);
            }

            // Read required fields — must be present and non-blank
            String id = props.getProperty(PluginInfo.KEY_ID);
            String name = props.getProperty(PluginInfo.KEY_NAME);
            String version = props.getProperty(PluginInfo.KEY_VERSION);
            String author = props.getProperty(PluginInfo.KEY_AUTHOR);
            String description = props.getProperty(PluginInfo.KEY_DESCRIPTION);
            String apiVersion = props.getProperty(PluginInfo.KEY_API_VERSION);
            String mainClass = props.getProperty(PluginInfo.KEY_MAIN_CLASS);

            // Validate presence and non-blankness with specific error messages
            if (id == null || id.isBlank()) {
                throw missingRequiredField(PluginInfo.KEY_ID);
            }
            if (name == null || name.isBlank()) {
                throw missingRequiredField(PluginInfo.KEY_NAME);
            }
            if (version == null || version.isBlank()) {
                throw missingRequiredField(PluginInfo.KEY_VERSION);
            }
            if (author == null || author.isBlank()) {
                throw missingRequiredField(PluginInfo.KEY_AUTHOR);
            }
            if (description == null || description.isBlank()) {
                throw missingRequiredField(PluginInfo.KEY_DESCRIPTION);
            }
            if (apiVersion == null || apiVersion.isBlank()) {
                throw missingRequiredField(PluginInfo.KEY_API_VERSION);
            }
            if (mainClass == null || mainClass.isBlank()) {
                throw missingRequiredField(PluginInfo.KEY_MAIN_CLASS);
            }

            // Read optional fields — if present, must be non-blank
            String depsStr = props.getProperty(PluginInfo.KEY_DEPENDENCIES, "");
            if (depsStr != null && depsStr.isBlank()) {
                // Treat blank as absent
                depsStr = "";
            }
            List<String> dependencies = depsStr.isEmpty() ? Collections.emptyList() :
                    Arrays.stream(depsStr.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());

            String website = props.getProperty(PluginInfo.KEY_WEBSITE, "");
            if (website != null && website.isBlank()) website = "";

            String license = props.getProperty(PluginInfo.KEY_LICENSE, "");
            if (license != null && license.isBlank()) license = "";

            // Read permissions (optional) — comma-separated list of PluginPermission names
            String permsStr = props.getProperty(PluginInfo.KEY_PERMISSIONS, "");
            if (permsStr != null && permsStr.isBlank()) permsStr = "";
            List<String> permissions = permsStr.isEmpty() ? Collections.emptyList() :
                    Arrays.stream(permsStr.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
            for (String p : permissions) {
                if (PluginPermission.parseOrNull(p) == null) {
                    throw new IllegalArgumentException(
                            "Unknown plugin permission '" + p + "' in " + jarPath
                                    + ". Allowed: " + PluginPermission.names());
                }
            }
            // Normalize to enum canonical names
            permissions = permissions.stream()
                    .map(p -> PluginPermission.parseOrNull(p).name())
                    .collect(Collectors.toList());

            // ===== External runtime fields (v1.7+) =====
            String externalRuntime = props.getProperty(PluginInfo.KEY_EXTERNAL_RUNTIME, "");
            if (externalRuntime != null && externalRuntime.isBlank()) externalRuntime = null;
            String externalEntry = props.getProperty(PluginInfo.KEY_EXTERNAL_ENTRY, "");
            if (externalEntry != null && externalEntry.isBlank()) externalEntry = null;
            String externalRestart = props.getProperty(PluginInfo.KEY_EXTERNAL_RESTART, "on-failure");
            if (externalRestart == null || externalRestart.isBlank()) externalRestart = "on-failure";
            // 嵌入模式（可选）：embed=web 表示外部进程提供本地 Web UI，由 PMCL 内嵌 WebView 承载
            String embed = props.getProperty(PluginInfo.KEY_EMBED, "");
            if (embed != null && embed.isBlank()) embed = null;

            return new PluginInfo(id, name, version, author, description, apiVersion, mainClass,
                    dependencies, website, license, permissions,
                    externalRuntime, externalEntry, externalRestart, embed);
        }
    }

    /**
     * Load an external runtime plugin (.NET / Python / Node.js).
     * Removed: ExternalRuntimeBridge, RuntimeDetection, NativeDockBridge, ProcessBridge
     * were non-embed dead-ends and have been deleted.
     */
    private void loadExternalRuntimePlugin(PluginInfo info, Path jarOrDirPath) throws Exception {
        throw new UnsupportedOperationException(
            "External runtime plugin '" + info.getId() + "' cannot be loaded: "
            + "bridge implementations (ExternalRuntimeBridge / RuntimeDetection / "
            + "NativeDockBridge) have been removed.");
    }

    /** Extract a JAR file to a directory. */
    private static void extractJar(Path jarPath, Path destDir) throws IOException {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(jarPath)))) {
            java.util.zip.ZipEntry ze;
            byte[] buf = new byte[8192];
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.isDirectory()) continue;
                // security: prevent zip slip
                Path outPath = destDir.resolve(ze.getName()).normalize();
                if (!outPath.startsWith(destDir)) {
                    throw new IOException("Zip slip detected: " + ze.getName());
                }
                Files.createDirectories(outPath.getParent());
                try (java.io.OutputStream os = Files.newOutputStream(outPath)) {
                    int len;
                    while ((len = zis.read(buf)) > 0) os.write(buf, 0, len);
                }
                zis.closeEntry();
            }
        }
    }

    /** Recursively delete a directory. */
    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    private static IllegalArgumentException missingRequiredField(String fieldKey) {
        return new IllegalArgumentException(
                "Missing or blank required field '" + fieldKey + "' in " +
                PluginInfo.PROPERTIES_PATH + ". Required fields: " +
                "plugin.id, plugin.name, plugin.version, plugin.author, " +
                "plugin.description, plugin.api-version, plugin.main-class");
    }

    /**
     * 签名完整性通过后，再校验签名者证书指纹是否在可信列表中。
     * <ul>
     *   <li>可信列表：启动时冻结的 {@code -Dpmcl.plugins.trustedFingerprints}
     *       以及 {@code ~/.pmcl/plugins/trusted-signers.txt}</li>
     *   <li>列表非空：必须命中，否则拒绝</li>
     *   <li>列表为空：默认拒绝；仅当启动时 {@code allowAnySigner=true} 才放行</li>
     *   <li>运行时 {@code System.setProperty} 无法降级本策略（C3）</li>
     * </ul>
     *
     * @param warnIfEmptyAllowlist when true, log a one-shot warning if accepting any signer
     * @return true if a empty-allowlist warning was emitted (so callers can suppress repeats)
     */
    private boolean assertTrustedPluginSigner(JarEntry entry, Path jarPath, boolean warnIfEmptyAllowlist)
            throws Exception {
        if (securityPolicy.allowAnySigner) {
            return false;
        }
        java.security.CodeSigner[] signers = entry.getCodeSigners();
        if (signers == null || signers.length == 0) {
            throw new SecurityException("Plugin JAR claims signature but entry has no CodeSigner: "
                    + entry.getName() + " in " + jarPath);
        }
        Set<String> present = new java.util.LinkedHashSet<>();
        for (java.security.CodeSigner signer : signers) {
            if (signer.getSignerCertPath() == null) continue;
            for (java.security.cert.Certificate cert : signer.getSignerCertPath().getCertificates()) {
                present.add(sha256Fingerprint(cert.getEncoded()));
            }
        }
        if (present.isEmpty()) {
            throw new SecurityException("Plugin JAR has CodeSigners but no certificates: "
                    + entry.getName() + " in " + jarPath);
        }
        Set<String> trusted = securityPolicy.loadTrustedFingerprints(pluginsDir);
        if (!trusted.isEmpty()) {
            boolean hit = false;
            for (String fp : present) {
                if (trusted.contains(fp)) { hit = true; break; }
            }
            if (!hit) {
                throw new SecurityException("Plugin signer not in trusted fingerprint list: "
                        + entry.getName() + " in " + jarPath
                        + " (signers=" + present + "; configure -Dpmcl.plugins.trustedFingerprints=... "
                        + "or ~/.pmcl/plugins/trusted-signers.txt before launch)");
            }
            return false;
        }
        if (securityPolicy.requireTrustedSigner) {
            throw new SecurityException("No trusted plugin signer fingerprints configured; refusing "
                    + jarPath + " (set -Dpmcl.plugins.trustedFingerprints=... before launch, or "
                    + "-Dpmcl.plugins.allowAnySigner=true for development)");
        }
        if (warnIfEmptyAllowlist) {
            System.err.println("[PluginManager] WARNING: no trusted signer allowlist configured; "
                    + "accepting any valid signature for " + jarPath
                    + " (fingerprints=" + present + "). Pin with -Dpmcl.plugins.trustedFingerprints.");
            return true;
        }
        return false;
    }

    /**
     * Verify a .ppk as a jarsigner-signed ZIP before extract/classpath use (C2).
     * Requires signature blocks and CodeSigners on classes/**, lib/*.jar, plugin.xml, etc.
     */
    private void verifyPluginArchive(Path archivePath, boolean packageArchive) throws Exception {
        ensureUrlStreamHandlerGuard();
        try (JarFile jar = new JarFile(archivePath.toFile(), true)) {
            boolean isSigned = false;
            try (var entryStream = jar.stream()) {
                isSigned = entryStream.anyMatch(e -> {
                    String n = e.getName();
                    return n.startsWith("META-INF/") &&
                            (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"));
                });
            }
            if (!isSigned) {
                if (packageArchive) {
                    // C2: 无「allowUnsignedPackages 跳过验签」后门；.ppk 必须签名
                    throw new SecurityException("Unsigned .ppk package blocked: " + archivePath
                            + " (sign with jarsigner; cover classes/**, lib/*.jar, and plugin.xml)");
                }
                if (!securityPolicy.allowUnsignedJars) {
                    throw new SecurityException("Unsigned plugin JAR blocked: " + archivePath
                            + " (start with -Dpmcl.plugins.allowUnsigned=true to allow)");
                }
                System.err.println("[PluginManager] WARNING: loading unsigned JAR (" + archivePath
                        + ") — no publisher authenticity check.");
                return;
            }
            // Checks CodeSigners present AND trusted fingerprints on every critical entry
            assertAllSignedEntries(jar, archivePath, packageArchive);
        }
    }

    /**
     * 已签名 JAR/.ppk：完整读取非 META-INF entry 以触发摘要校验，
     * 并要求关键路径带 CodeSigner（防「只签描述符」或未签的 lib/*.jar）。
     * 对每个必须签名的 entry 校验 CodeSigner 指纹命中可信列表（H1：不限于 descriptor）。
     *
     * @param packageArchive .ppk 时额外要求 plugin.xml、classes/**、lib/*.jar 均被签名
     */
    private void assertAllSignedEntries(JarFile jar, Path jarPath, boolean packageArchive)
            throws Exception {
        final int maxEntries = 50_000;
        int count = 0;
        boolean warnedEmpty = false;
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry e = entries.nextElement();
            if (e.isDirectory()) continue;
            if (++count > maxEntries) {
                throw new SecurityException("Plugin JAR entry count exceeds " + maxEntries + ": " + jarPath);
            }
            String name = e.getName();
            if (name.startsWith("META-INF/")) continue;
            try (InputStream in = jar.getInputStream(e)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            boolean mustBeSigned = name.endsWith(".class")
                    || name.equals(PluginInfo.PROPERTIES_PATH)
                    || name.equals("plugin.xml")
                    || (packageArchive && name.startsWith("classes/"))
                    || (packageArchive && name.startsWith("lib/") && name.endsWith(".jar"));
            if (mustBeSigned) {
                java.security.CodeSigner[] signers = e.getCodeSigners();
                if (signers == null || signers.length == 0) {
                    throw new SecurityException("Signed plugin archive has unsigned entry: "
                            + name + " in " + jarPath);
                }
                // H1: every critical entry's CodeSigners must match trusted fingerprints
                if (assertTrustedPluginSigner(e, jarPath, !warnedEmpty)) {
                    warnedEmpty = true;
                }
            }
        }
    }

    private static String sha256Fingerprint(byte[] encoded) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] dig = md.digest(encoded);
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // ==================== Utility ====================

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }

    // ==================== Inner Classes ====================

    /** Plugin state enum */
    public enum PluginState {
        LOADED, ENABLED, DISABLED, FAILED
    }

    private static final long PLUGIN_THREAD_SHUTDOWN_WAIT_MS = 2_000L;

    /** A loaded plugin entry.
     *  M19 修复：所有字段 private，仅通过 getter 暴露只读视图；
     *  state 转换通过包级 setState 方法，强制走 PluginManager 的状态机。 */
    public static class PluginEntry {
        private final PluginInfo info;
        private final PmclPlugin plugin;
        private PluginContextImpl context;
        private final PluginIsolatingClassLoader classLoader;
        private final Path jarPath;
        /** Whether this plugin was loaded from a .ppk package (true) or a single .jar (false). */
        private final boolean isPackage;
        private final PluginThreadTracker threads;
        private volatile PluginState state;

        PluginEntry(PluginInfo info, PmclPlugin plugin, PluginContextImpl context,
                    PluginIsolatingClassLoader classLoader, Path jarPath) {
            this(info, plugin, context, classLoader, jarPath, false);
        }



        PluginEntry(PluginInfo info, PmclPlugin plugin, PluginContextImpl context,
                    PluginIsolatingClassLoader classLoader, Path jarPath, boolean isPackage) {
            this.info = info;
            this.plugin = plugin;
            this.context = context;
            this.classLoader = classLoader;
            this.jarPath = jarPath;
            this.isPackage = isPackage;
            this.threads = new PluginThreadTracker(info.getId());
            this.threads.setContextClassLoader(classLoader);
            this.state = PluginState.LOADED;
        }

        public PluginInfo getInfo() { return info; }
        public PmclPlugin getPlugin() { return plugin; }
        public PluginContextImpl getContext() { return context; }

        public PluginIsolatingClassLoader getClassLoader() { return classLoader; }
        public Path getJarPath() { return jarPath; }
        public boolean isPackage() { return isPackage; }
        public PluginState getState() { return state; }
        PluginThreadTracker threads() { return threads; }

        /** 包级状态转换方法：仅 PluginManager 可调用，确保状态机一致性 */
        void setState(PluginState newState) {
            this.state = newState;
        }
    }

    /** A registered custom command */
    public static class RegisteredCommand {
        public final String pluginId;
        public final String name;
        public final String description;
        public final CommandHandler handler;

        RegisteredCommand(String pluginId, String name, String description, CommandHandler handler) {
            this.pluginId = pluginId;
            this.name = name;
            this.description = description;
            this.handler = handler;
        }
    }

    /** A registered custom page */
    public static class RegisteredPage {
        public final String pluginId;
        public final String id;
        public final String title;
        public final ComposableContent content;

        RegisteredPage(String pluginId, String id, String title, ComposableContent content) {
            this.pluginId = pluginId;
            this.id = id;
            this.title = title;
            this.content = content;
        }
    }

    // Tracking wrappers for cleanup
    private static class TrackedEventListener implements EventListener {
        final String pluginId;
        private final EventListener delegate;
        TrackedEventListener(String pluginId, EventListener delegate) {
            this.pluginId = pluginId;
            this.delegate = delegate;
        }
        @Override
        public void onEvent(PmclEvent event) {
            // Prefer fireEvent path (already runInPlugin); direct calls still go through group.
            delegate.onEvent(event);
        }
    }

    private static class TrackedLaunchHook implements LaunchHook {
        final String pluginId;
        private final LaunchHook delegate;
        private final PluginManager manager;
        TrackedLaunchHook(PluginManager manager, String pluginId, LaunchHook delegate) {
            this.manager = manager;
            this.pluginId = pluginId;
            this.delegate = delegate;
        }
        @Override
        public boolean beforeLaunch(String versionId, String accountName) {
            return manager.callInPlugin(pluginId, () -> delegate.beforeLaunch(versionId, accountName));
        }
        @Override
        public String cancelReason() {
            return manager.callInPlugin(pluginId, delegate::cancelReason);
        }
        @Override
        public List<String> contributeJvmArgs(String versionId, String accountName) {
            return manager.callInPlugin(pluginId, () -> delegate.contributeJvmArgs(versionId, accountName));
        }
        @Override
        public List<String> contributeGameArgs(String versionId, String accountName) {
            return manager.callInPlugin(pluginId, () -> delegate.contributeGameArgs(versionId, accountName));
        }
        @Override
        public Map<String, String> contributeEnv(String versionId, String accountName) {
            return manager.callInPlugin(pluginId, () -> delegate.contributeEnv(versionId, accountName));
        }
        @Override
        public List<String> contributeClasspathJars(String versionId, String accountName) {
            return manager.callInPlugin(pluginId, () -> delegate.contributeClasspathJars(versionId, accountName));
        }
        @Override
        public List<String> contributeJavaAgents(String versionId, String accountName) {
            return manager.callInPlugin(pluginId, () -> delegate.contributeJavaAgents(versionId, accountName));
        }
        @Override
        public void afterLaunch(String versionId, int exitCode) {
            manager.runInPlugin(pluginId, () -> delegate.afterLaunch(versionId, exitCode));
        }
    }

    private static class TrackedUrlRewriteHook {
        final String pluginId;
        final UrlRewriteHook delegate;
        TrackedUrlRewriteHook(String pluginId, UrlRewriteHook delegate) {
            this.pluginId = pluginId;
            this.delegate = delegate;
        }
    }

    // ==================== PluginContext Implementation ====================

    // M20 修复：改为静态内部类，避免持有外部 PluginManager 引用导致的 GC 障碍；
    // 通过构造函数显式注入 manager。
    public static class PluginContextImpl implements PluginContext {
        private final PluginManager manager;
        private final String pluginId;

        PluginContextImpl(PluginManager manager, String pluginId) {
            this.manager = manager;
            this.pluginId = pluginId;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getService(Class<T> type) {
            if (type == null) return null;
            // Core types are never exposed — typed plugin APIs only
            if (type == LauncherCore.class || type.getName().startsWith("com.pmcl.core.")) {
                throw new SecurityException(
                        "Plugin '" + pluginId + "' cannot access " + type.getName()
                                + " via getService; use typed plugin APIs (VersionsApi, AccountsApi, …).");
            }
            if (type == VersionsApi.class) return (T) versions();
            if (type == InstancesApi.class) return (T) instances();
            if (type == AccountsApi.class) return (T) accounts();
            if (type == LaunchApi.class) return (T) launch();
            if (type == DownloadsApi.class) return (T) downloads();
            if (type == com.pmcl.plugin.api.DownloadQueueApi.class) return (T) downloadQueue();
            if (type == ModsApi.class) return (T) mods();
            if (type == com.pmcl.plugin.api.ModMarketApi.class) return (T) modMarket();
            if (type == SettingsApi.class) return (T) settings();
            if (type == UiApi.class) return (T) ui();
            if (type == NewsApi.class) return (T) news();
            if (type == I18nApi.class) return (T) i18n();
            if (type == ModpackApi.class) return (T) modpacks();
            if (type == com.pmcl.plugin.api.GameContentApi.class) return (T) gameContent();
            if (type == GameProcessApi.class) return (T) gameProcess();
            if (type == LoaderVersionsApi.class) return (T) loaderVersions();
            if (type == com.pmcl.plugin.api.RoomsApi.class) return (T) rooms();
            if (type == com.pmcl.plugin.api.ServersApi.class) return (T) servers();
            if (type == com.pmcl.plugin.api.JavaRuntimesApi.class) return (T) javaRuntimes();
            if (type == com.pmcl.plugin.api.NbtApi.class) return (T) nbt();
            if (type == com.pmcl.plugin.api.CrashLogsApi.class) return (T) crashLogs();
            if (type == com.pmcl.plugin.api.MusicApi.class) return (T) music();
            if (type == com.pmcl.plugin.api.StatsApi.class) return (T) stats();
            if (type == FilesystemApi.class) return (T) filesystem();
            if (type == SchedulerApi.class) return (T) scheduler();
            if (type == PluginsApi.class) return (T) plugins();
            if (type == HttpApi.class) return (T) http();
            return null;
        }

        @Override
        public VersionsApi versions() {
            return PluginApiFacades.versions(manager.core, this::requirePermission);
        }

        @Override
        public InstancesApi instances() {
            return PluginApiFacades.instances(manager.core, manager, pluginId, this::requirePermission);
        }

        @Override
        public AccountsApi accounts() {
            return PluginApiFacades.accounts(manager.core, manager, this::requirePermission);
        }

        @Override
        public LaunchApi launch() {
            return PluginApiFacades.launch(manager.core, manager, this::requirePermission);
        }

        @Override
        public LoaderVersionsApi loaderVersions() {
            return PluginApiFacades.loaderVersions(manager.core, this::requirePermission);
        }

        @Override
        public DownloadsApi downloads() {
            return PluginApiFacades.downloads(manager.core, manager, pluginId, getDataDir(), this::requirePermission);
        }

        @Override
        public com.pmcl.plugin.api.DownloadQueueApi downloadQueue() {
            return PluginApiFacades.downloadQueue(manager.core, this::requirePermission);
        }

        @Override
        public ModsApi mods() {
            return PluginApiFacades.mods(manager.core, manager, this::requirePermission);
        }

        @Override
        public com.pmcl.plugin.api.ModMarketApi modMarket() {
            return PluginApiFacades.modMarket(manager.core, this::requirePermission);
        }

        @Override
        public ModpackApi modpacks() {
            return PluginApiFacades.modpacks(manager.core, this::requirePermission);
        }

        @Override
        public com.pmcl.plugin.api.GameContentApi gameContent() {
            return PluginApiFacades.gameContent(manager.core, this::requirePermission);
        }

        public GameProcessApi gameProcess() {
            return PluginApiFacades.gameProcess(manager.core, this::requirePermission);
        }

        @Override
        public com.pmcl.plugin.api.RoomsApi rooms() {
            return PluginApiFacades.rooms(manager.core, this::requirePermission);
        }

        @Override
        public com.pmcl.plugin.api.ServersApi servers() {
            return PluginApiFacades.servers(manager.core, this::requirePermission);
        }

        @Override
        public com.pmcl.plugin.api.JavaRuntimesApi javaRuntimes() {
            return PluginApiFacades.javaRuntimes(manager.core, this::requirePermission);
        }

        @Override
        public com.pmcl.plugin.api.NbtApi nbt() {
            return PluginApiFacades.nbt(manager.core, getDataDir(), this::requirePermission);
        }

        @Override
        public com.pmcl.plugin.api.CrashLogsApi crashLogs() {
            return PluginApiFacades.crashLogs(manager.core, this::requirePermission);
        }

        @Override
        public com.pmcl.plugin.api.MusicApi music() {
            return PluginApiFacades.music(manager, this::requirePermission);
        }

        @Override
        public com.pmcl.plugin.api.StatsApi stats() {
            return PluginApiFacades.stats(manager.core, this::requirePermission);
        }

        @Override
        public NewsApi news() {
            return PluginApiFacades.news(manager.core, this::requirePermission);
        }

        @Override
        public I18nApi i18n() {
            return PluginApiFacades.i18n(manager.core, manager, pluginId);
        }

        @Override
        public SettingsApi settings() {
            return PluginApiFacades.settings(manager.core, manager, pluginId, this::requirePermission);
        }

        @Override
        public UiApi ui() {
            return PluginApiFacades.ui(manager, pluginId);
        }

        @Override
        public FilesystemApi filesystem() {
            return PluginApiFacades.filesystem(manager.core, pluginId, getDataDir(), this::requirePermission);
        }

        @Override
        public SchedulerApi scheduler() {
            return PluginApiFacades.scheduler(manager, pluginId);
        }

        @Override
        public PluginsApi plugins() {
            return PluginApiFacades.plugins(manager, this::requirePermission);
        }

        @Override
        public HttpApi http() {
            return PluginApiFacades.http(manager.core, manager, this::requirePermission);
        }

        private void requirePermission(String requiredPermission) {
            PluginEntry entry = manager.loadedPlugins.get(pluginId);
            List<String> perms = (entry != null) ? entry.getInfo().getPermissions() : Collections.emptyList();
            if (!perms.contains(requiredPermission)) {
                System.err.println("[PluginManager] SECURITY: plugin '" + pluginId
                        + "' lacks permission " + requiredPermission);
                throw new SecurityException(
                        "Plugin '" + pluginId + "' lacks required permission '" + requiredPermission
                                + "'. Declare it via 'plugin.permissions=...' in the descriptor.");
            }
        }

        @Override
        public Path getDataDir() {
            Path dir = manager.pluginsDir.resolve(pluginId).resolve("data");
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                System.err.println("[Plugin:" + pluginId + "] Failed to create data dir: " + e.getMessage());
            }
            return dir;
        }

        @Override
        public String getConfig(String key) {
            synchronized (manager) {
                Map<String, String> cfg = manager.pluginConfigs.get(pluginId);
                return cfg != null ? cfg.get(key) : null;
            }
        }

        @Override
        public void setConfig(String key, String value) {
            synchronized (manager) {
                manager.pluginConfigs.computeIfAbsent(pluginId, k -> new HashMap<>()).put(key, value);
                manager.saveState();
            }
        }

        @Override
        public void info(String message) {
            System.out.println("[Plugin:" + pluginId + "] " + message);
        }

        @Override
        public void warn(String message) {
            System.out.println("[Plugin:" + pluginId + " WARN] " + message);
        }

        @Override
        public void error(String message, Throwable throwable) {
            System.err.println("[Plugin:" + pluginId + " ERROR] " + message);
            if (throwable != null) throwable.printStackTrace(System.err);
        }

        @Override
        public Thread newThread(String name, Runnable task) {
            if (task == null) throw new NullPointerException("task");
            return manager.newPluginThread(pluginId, name != null ? name : "", task);
        }

        @Override
        public java.util.concurrent.ThreadFactory threadFactory() {
            return manager.pluginThreadFactory(pluginId);
        }

        @Override
        public void registerCommand(String name, String description, CommandHandler handler) {
            // Strict validation
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Command name must not be null or blank (plugin: " + pluginId + ")");
            }
            if (!PluginInfo.isValidCommandName(name)) {
                throw new IllegalArgumentException(
                        "Invalid command name '" + name + "' in plugin '" + pluginId + "': " +
                        "must be 1-32 chars, lowercase alphanumeric + hyphens, " +
                        "start with a letter, end with alphanumeric, no consecutive hyphens");
            }
            if (RESERVED_COMMAND_NAMES.contains(name.toLowerCase())) {
                throw new IllegalArgumentException(
                        "Command name '" + name + "' in plugin '" + pluginId + "' is reserved " +
                        "and cannot be used (clashes with a built-in PMCL command)");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException(
                        "Command description must not be null or blank (command: " + name + ", plugin: " + pluginId + ")");
            }
            if (handler == null) {
                throw new NullPointerException("Command handler must not be null (command: " + name + ", plugin: " + pluginId + ")");
            }
            synchronized (manager) {
                // Check for duplicate command name within the same plugin
                List<RegisteredCommand> existing = manager.customCommands.get(pluginId);
                if (existing != null) {
                    for (RegisteredCommand c : existing) {
                        if (c.name.equals(name)) {
                            throw new IllegalStateException(
                                    "Duplicate command name '" + name + "' in plugin '" + pluginId + "'");
                        }
                    }
                }
                CommandHandler gated = args -> manager.callInPlugin(pluginId, () -> handler.execute(args));
                RegisteredCommand cmd = new RegisteredCommand(pluginId, name, description, gated);
                manager.customCommands.computeIfAbsent(pluginId, k -> new ArrayList<>()).add(cmd);
            }
        }

        @Override
        public void registerPage(String id, String title, ComposableContent content) {
            // Strict validation
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Page id must not be null or blank (plugin: " + pluginId + ")");
            }
            if (!PluginInfo.isValidCommandName(id)) {
                throw new IllegalArgumentException(
                        "Invalid page id '" + id + "' in plugin '" + pluginId + "': " +
                        "must be 1-32 chars, lowercase alphanumeric + hyphens, " +
                        "start with a letter, end with alphanumeric, no consecutive hyphens");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Page title must not be null or blank (page: " + id + ", plugin: " + pluginId + ")");
            }
            if (content == null) {
                throw new NullPointerException("Page content must not be null (page: " + id + ", plugin: " + pluginId + ")");
            }
            synchronized (manager) {
                // Check for duplicate page id within the same plugin
                List<RegisteredPage> existingPages = manager.customPages.get(pluginId);
                if (existingPages != null) {
                    for (RegisteredPage p : existingPages) {
                        if (p.id.equals(id)) {
                            throw new IllegalStateException(
                                    "Duplicate page id '" + id + "' in plugin '" + pluginId + "'");
                        }
                    }
                }
                RegisteredPage page = new RegisteredPage(pluginId, id, title, content);
                manager.customPages.computeIfAbsent(pluginId, k -> new ArrayList<>()).add(page);
                manager.bumpRevision();
            }
        }

        @Override
        public void registerJavaFxPage(String id, String title, com.pmcl.plugin.JavaFxContent content) {
            if (content == null) {
                throw new NullPointerException(
                        "JavaFX page content must not be null (page: " + id + ", plugin: " + pluginId + ")");
            }
            // 嵌入引擎在 :ui，经 plugin-api 注册表解耦取用（同 WebViewPageFactories 模式）；
            // 无 UI 宿主（headless CLI）时降级为错误占位页，由 SafePluginPage 渲染。
            com.pmcl.plugin.JavaFxPageFactory factory = com.pmcl.plugin.JavaFxPageFactories.get();
            com.pmcl.plugin.ComposableContent embeddable;
            if (factory != null) {
                embeddable = manager.callInPlugin(pluginId, () -> factory.create(content));
            } else {
                embeddable = com.pmcl.plugin.JavaFxPageFactories.unavailableContent();
            }
            registerPage(id, title, embeddable);
        }

        @Override
        public void registerSettingsSection(String id, String title, ComposableContent content) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Settings section id must not be blank (plugin: " + pluginId + ")");
            }
            if (!PluginInfo.isValidCommandName(id)) {
                throw new IllegalArgumentException(
                        "Invalid settings section id '" + id + "' in plugin '" + pluginId + "'");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException(
                        "Settings section title must not be blank (section: " + id + ", plugin: " + pluginId + ")");
            }
            if (content == null) {
                throw new NullPointerException("Settings section content must not be null");
            }
            synchronized (manager) {
                List<RegisteredPage> existing = manager.customSettingsSections.get(pluginId);
                if (existing != null) {
                    for (RegisteredPage p : existing) {
                        if (p.id.equals(id)) {
                            throw new IllegalStateException(
                                    "Duplicate settings section id '" + id + "' in plugin '" + pluginId + "'");
                        }
                    }
                }
                RegisteredPage section = new RegisteredPage(pluginId, id, title, content);
                manager.customSettingsSections.computeIfAbsent(pluginId, k -> new ArrayList<>()).add(section);
                manager.bumpRevision();
            }
        }

        @Override
        public void registerMenuAction(String id, String title, String description,
                                       List<String> keywords, ActionHandler handler) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Menu action id must not be blank (plugin: " + pluginId + ")");
            }
            if (!PluginInfo.isValidCommandName(id)) {
                throw new IllegalArgumentException("Invalid menu action id '" + id + "' in plugin '" + pluginId + "'");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Menu action title must not be blank");
            }
            if (handler == null) {
                throw new NullPointerException("Menu action handler must not be null");
            }
            List<String> cleanKeywords = sanitizeKeywords(keywords);
            synchronized (manager) {
                List<PluginMenuAction> existing = manager.customMenuActions.get(pluginId);
                if (existing != null) {
                    for (PluginMenuAction a : existing) {
                        if (a.getId().equals(id)) {
                            throw new IllegalStateException(
                                    "Duplicate menu action id '" + id + "' in plugin '" + pluginId + "'");
                        }
                    }
                }
                ActionHandler gated = () -> manager.runInPlugin(pluginId, handler::run);
                PluginMenuAction action = new PluginMenuAction(
                        pluginId, id, title,
                        description != null ? description : "",
                        cleanKeywords,
                        gated);
                manager.customMenuActions.computeIfAbsent(pluginId, k -> new ArrayList<>()).add(action);
                manager.bumpRevision();
            }
        }

        /** Trim keywords, drop blanks, cap count (16) and per-entry length (32) to prevent abuse. */
        private static List<String> sanitizeKeywords(List<String> keywords) {
            if (keywords == null || keywords.isEmpty()) return List.of();
            List<String> out = new ArrayList<>();
            for (String k : keywords) {
                if (k == null) continue;
                String trimmed = k.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.length() > 32) trimmed = trimmed.substring(0, 32);
                out.add(trimmed);
                if (out.size() >= 16) break;
            }
            return out.isEmpty() ? List.of() : List.copyOf(out);
        }

        @Override
        public void registerStatusBarAction(String id, String title, String description, ActionHandler handler) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Status bar action id must not be blank (plugin: " + pluginId + ")");
            }
            if (!PluginInfo.isValidCommandName(id)) {
                throw new IllegalArgumentException("Invalid status bar action id '" + id + "' in plugin '" + pluginId + "'");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Status bar action title must not be blank");
            }
            if (handler == null) {
                throw new NullPointerException("Status bar action handler must not be null");
            }
            synchronized (manager) {
                List<PluginStatusBarAction> existing = manager.customStatusBarActions.get(pluginId);
                if (existing != null) {
                    for (PluginStatusBarAction a : existing) {
                        if (a.getId().equals(id)) {
                            throw new IllegalStateException(
                                    "Duplicate status bar action id '" + id + "' in plugin '" + pluginId + "'");
                        }
                    }
                }
                ActionHandler gated = () -> manager.runInPlugin(pluginId, handler::run);
                PluginStatusBarAction action = new PluginStatusBarAction(
                        pluginId, id, title,
                        description != null ? description : "",
                        gated);
                manager.customStatusBarActions.computeIfAbsent(pluginId, k -> new ArrayList<>()).add(action);
                manager.bumpRevision();
            }
        }

        @Override
        public void registerHomeCard(HomeCard card) {
            if (card == null) throw new NullPointerException("HomeCard must not be null");
            if (card.getId() == null || card.getId().isBlank()) {
                throw new IllegalArgumentException("HomeCard.id must not be blank");
            }
            if (!PluginInfo.isValidCommandName(card.getId())) {
                throw new IllegalArgumentException("Invalid HomeCard id '" + card.getId() + "'");
            }
            if (card.getTitle() == null || card.getTitle().isBlank()) {
                throw new IllegalArgumentException("HomeCard.title must not be blank");
            }
            if (card.getContent() == null) {
                throw new NullPointerException("HomeCard.content must not be null");
            }
            synchronized (manager) {
                List<HomeCard> existing = manager.customHomeCards.get(pluginId);
                if (existing != null) {
                    for (HomeCard c : existing) {
                        if (c.getId().equals(card.getId())) {
                            throw new IllegalStateException(
                                    "Duplicate home card id '" + card.getId() + "' in plugin '" + pluginId + "'");
                        }
                    }
                }
                manager.customHomeCards.computeIfAbsent(pluginId, k -> new ArrayList<>()).add(card);
                manager.bumpRevision();
            }
        }

        @Override
        public void registerUrlRewriteHook(UrlRewriteHook hook) {
            requirePermission("NETWORK");
            if (hook == null) throw new NullPointerException("UrlRewriteHook must not be null");
            manager.urlRewriteHooks.add(new TrackedUrlRewriteHook(pluginId, hook));
        }

        @Override
        public void registerThemePack(ThemePack pack) {
            if (pack == null) {
                throw new NullPointerException("ThemePack must not be null (plugin: " + pluginId + ")");
            }
            // pack.id / pack.name 已在 ThemePack 构造时由 init{} 校验
            synchronized (manager) {
                // Check for duplicate theme pack id globally (across all plugins)
                String existingOwner = manager.getThemePackOwner(pack.getId());
                if (existingOwner != null) {
                    throw new IllegalStateException(
                            "Duplicate theme pack id '" + pack.getId() + "' from plugin '" + pluginId +
                            "': already registered by plugin '" + existingOwner + "'");
                }
                manager.customThemePacks.computeIfAbsent(pluginId, k -> new ArrayList<>()).add(pack);
            }
        }

        @Override
        public void registerLaunchHook(LaunchHook hook) {
            requirePermission("CONTROL_LAUNCH");
            if (hook == null) throw new NullPointerException("LaunchHook must not be null");
            manager.launchHooks.add(new TrackedLaunchHook(manager, pluginId, hook));
        }

        @Override
        public void addEventListener(EventListener listener) {
            manager.eventListeners.add(new TrackedEventListener(pluginId, listener));
        }

        @Override
        public void fireEvent(PmclEvent event) {
            manager.fireEvent(event);
        }
    }
}
