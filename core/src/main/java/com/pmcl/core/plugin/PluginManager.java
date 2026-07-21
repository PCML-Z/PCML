package com.pmcl.core.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.pmcl.core.LauncherCore;
import com.pmcl.plugin.ComposableContent;
import com.pmcl.plugin.CommandHandler;
import com.pmcl.plugin.EventListener;
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
import java.util.concurrent.CopyOnWriteArrayList;
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

    // Revision counter — incremented on any structural change (load/unload/enable/disable).
    // GUI polls this to detect when plugin pages need refreshing.
    private volatile long revision = 0;

    // Loaded plugin entries (pluginId -> entry)
    private final Map<String, PluginEntry> loadedPlugins = new LinkedHashMap<>();
    // Registered custom commands (pluginId -> list of commands)
    private final Map<String, List<RegisteredCommand>> customCommands = new HashMap<>();
    // Registered pages (pluginId -> list of pages)
    private final Map<String, List<RegisteredPage>> customPages = new HashMap<>();
    // Event listeners
    private final List<EventListener> eventListeners = new CopyOnWriteArrayList<>();
    // Launch hooks
    private final List<LaunchHook> launchHooks = new CopyOnWriteArrayList<>();
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
        ensurePluginsDir();
        loadState();
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
    private void loadState() {
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
    public synchronized void discoverAndLoadAll() {
        ensurePluginsDir();
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
                System.err.println("[PluginManager] Failed to load " + file.getName() + ": " + e.getMessage());
            }
        }
        // Auto-enable plugins that were previously enabled
        for (PluginEntry entry : new ArrayList<>(loadedPlugins.values())) {
            if (entry.getState() == PluginState.LOADED && isEnabled(entry.getInfo().getId())) {
                enablePlugin(entry.getInfo().getId());
            }
        }
    }

    /**
     * Load a plugin from a JAR file.
     * @return the PluginInfo of the loaded plugin
     * @throws Exception if loading fails
     */
    public synchronized PluginInfo loadPlugin(Path jarPath) throws Exception {
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

        // Parse descriptor
        PluginInfo info = parseDescriptor(jarPath);
        info.validate();

        // Check for duplicate
        if (loadedPlugins.containsKey(info.getId())) {
            throw new IllegalStateException("Plugin already loaded: " + info.getId());
        }

        // Check dependencies — all must be loaded before this plugin can load
        for (String dep : info.getDependencies()) {
            if (!loadedPlugins.containsKey(dep)) {
                throw new IllegalStateException(
                        "Plugin '" + info.getId() + "' requires dependency '" + dep +
                        "' which is not loaded. Install/load it first.");
            }
        }

        // Create classloader with PMCL classloader as parent — 使用隔离 ClassLoader
        // 阻止插件直接加载 com.pmcl.core.* 内部类，强制走 getService
        URL[] urls = {jarPath.toUri().toURL()};
        PluginIsolatingClassLoader classLoader = new PluginIsolatingClassLoader(
                info.getId(), urls, getClass().getClassLoader());

        // Load main class — 异常路径关闭 classLoader 防止 jar 句柄泄漏
        PmclPlugin plugin;
        try {
            Class<?> mainClass = classLoader.loadClass(info.getMainClass());
            if (!PmclPlugin.class.isAssignableFrom(mainClass)) {
                classLoader.close();
                throw new ClassCastException("Main class " + info.getMainClass() +
                        " does not implement PmclPlugin");
            }
            plugin = (PmclPlugin) mainClass.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            try { classLoader.close(); } catch (Exception ignored) {}
            throw t;
        }

        // Create context
        PluginContextImpl ctx = new PluginContextImpl(this, info.getId());

        PluginEntry entry = new PluginEntry(info, plugin, ctx, classLoader, jarPath);
        entry.setState(PluginState.LOADED);
        loadedPlugins.put(info.getId(), entry);

        // Call onLoad
        try {
            plugin.onLoad();
        } catch (Exception e) {
            System.err.println("[PluginManager] onLoad failed for " + info.getId() + ": " + e.getMessage());
            entry.setState(PluginState.FAILED);
            fireEvent(new PluginErrorEvent(info.getId(), e));
        }

        System.out.println("[PluginManager] Loaded plugin: " + info.getId() + " v" + info.getVersion());
        bumpRevision();
        fireEvent(new PluginLoadedEvent(info.getId()));
        return info;
    }

    /**
     * Enable a loaded plugin (calls onEnable).
     */
    public synchronized void enablePlugin(String pluginId) {
        PluginEntry entry = loadedPlugins.get(pluginId);
        if (entry == null) throw new IllegalStateException("Plugin not loaded: " + pluginId);
        if (entry.getState() == PluginState.ENABLED) return;
        if (entry.getState() != PluginState.LOADED && entry.getState() != PluginState.DISABLED)
            throw new IllegalStateException("Plugin not in loadable state: " + pluginId + " (" + entry.getState() + ")");

        try {
            entry.getPlugin().onEnable(entry.getContext());
            entry.setState(PluginState.ENABLED);
            enabledState.put(pluginId, true);
            saveState();
            System.out.println("[PluginManager] Enabled plugin: " + pluginId);
            bumpRevision();
            fireEvent(new PluginEnabledEvent(pluginId));
        } catch (Exception e) {
            System.err.println("[PluginManager] Failed to enable " + pluginId + ": " + e.getMessage());
            entry.setState(PluginState.FAILED);
            fireEvent(new PluginErrorEvent(pluginId, e));
        }
    }

    /**
     * Disable an enabled plugin (calls onDisable).
     */
    public synchronized void disablePlugin(String pluginId) {
        PluginEntry entry = loadedPlugins.get(pluginId);
        if (entry == null) return;
        if (entry.getState() != PluginState.ENABLED) return;

        try {
            entry.getPlugin().onDisable();
        } catch (Exception e) {
            System.err.println("[PluginManager] onDisable failed for " + pluginId + ": " + e.getMessage());
        }

        // Unregister extensions
        customCommands.remove(pluginId);
        customPages.remove(pluginId);
        eventListeners.removeIf(l -> l instanceof TrackedEventListener &&
                ((TrackedEventListener) l).pluginId.equals(pluginId));
        launchHooks.removeIf(h -> h instanceof TrackedLaunchHook &&
                ((TrackedLaunchHook) h).pluginId.equals(pluginId));

        entry.setState(PluginState.DISABLED);
        enabledState.put(pluginId, false);
        saveState();
        System.out.println("[PluginManager] Disabled plugin: " + pluginId);
        bumpRevision();
        fireEvent(new PluginDisabledEvent(pluginId));
    }

    /**
     * Unload a plugin completely (disable + remove from memory).
     */
    public synchronized void unloadPlugin(String pluginId) {
        disablePlugin(pluginId);
        PluginEntry entry = loadedPlugins.remove(pluginId);
        if (entry != null) {
            try {
                entry.getClassLoader().close();
            } catch (IOException e) {
                System.err.println("[PluginManager] Failed to close classloader for " + pluginId);
            }
        }
        customCommands.remove(pluginId);
        customPages.remove(pluginId);
        System.out.println("[PluginManager] Unloaded plugin: " + pluginId);
        bumpRevision();
    }

    /**
     * Reload a plugin (unload + load + enable if previously enabled).
     */
    public synchronized void reloadPlugin(String pluginId) throws Exception {
        PluginEntry entry = loadedPlugins.get(pluginId);
        if (entry == null) throw new IllegalStateException("Plugin not loaded: " + pluginId);
        boolean wasEnabled = entry.getState() == PluginState.ENABLED;
        Path sourcePath = entry.getJarPath();
        boolean wasPackage = entry.isPackage();
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
    public synchronized PluginInfo installFromPath(Path sourceJar) throws Exception {
        PluginInfo info = parseDescriptor(sourceJar);
        info.validate();

        Path target = pluginsDir.resolve(info.getId() + ".jar");
        Files.copy(sourceJar, target, StandardCopyOption.REPLACE_EXISTING);

        // If already loaded, unload first
        if (loadedPlugins.containsKey(info.getId())) {
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
            core.downloads().downloadTo(url, tempFile);
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
    public synchronized PluginInfo loadPluginPackage(Path ppkPath) throws Exception {
        // Parse and validate the package manifest
        PluginPackage pkg = PluginPackageParser.parse(ppkPath);
        PluginInfo info = pkg.getInfo();
        // info is already validated inside PluginPackageParser.parseDocument

        // Check for duplicate
        if (loadedPlugins.containsKey(info.getId())) {
            throw new IllegalStateException("Plugin already loaded: " + info.getId());
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

        // Validate runtime structure (must have classes/)
        PluginPackageBuilder.validateRuntimeStructure(packageDir);

        // Create classloader from classes/ + lib/*.jar — 使用隔离 ClassLoader
        // 阻止插件直接加载 com.pmcl.core.* 内部类，强制走 getService
        PluginIsolatingClassLoader classLoader = PluginPackageBuilder.createClassLoader(
                packageDir, pkg, getClass().getClassLoader());

        // Load main class
        Class<?> mainClass = classLoader.loadClass(info.getMainClass());
        if (!PmclPlugin.class.isAssignableFrom(mainClass)) {
            classLoader.close();
            throw new ClassCastException("Main class " + info.getMainClass() +
                    " does not implement PmclPlugin");
        }

        // Instantiate
        PmclPlugin plugin = (PmclPlugin) mainClass.getDeclaredConstructor().newInstance();

        // Create context
        PluginContextImpl ctx = new PluginContextImpl(this, info.getId());

        PluginEntry entry = new PluginEntry(info, plugin, ctx, classLoader, ppkPath, true);
        entry.setState(PluginState.LOADED);
        loadedPlugins.put(info.getId(), entry);

        // Call onLoad
        try {
            plugin.onLoad();
        } catch (Exception e) {
            System.err.println("[PluginManager] onLoad failed for " + info.getId() + ": " + e.getMessage());
            entry.setState(PluginState.FAILED);
            fireEvent(new PluginErrorEvent(info.getId(), e));
        }

        System.out.println("[PluginManager] Loaded plugin package: " + info.getId() + " v" + info.getVersion() +
                " (" + pkg.getKotlinSources().size() + " kt, " + pkg.getJavaSources().size() + " java files)");
        bumpRevision();
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
    public synchronized PluginInfo installFromPackage(Path ppkPath) throws Exception {
        // Parse to get the plugin ID for naming the target file
        PluginPackage pkg = PluginPackageParser.parse(ppkPath);
        PluginInfo info = pkg.getInfo();

        // Copy .ppk to plugins dir
        Path targetPpk = pluginsDir.resolve(info.getId() + ".ppk");
        Files.copy(ppkPath, targetPpk, StandardCopyOption.REPLACE_EXISTING);

        // If already loaded, unload first
        if (loadedPlugins.containsKey(info.getId())) {
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
            core.downloads().downloadTo(url, tempFile);
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
    public synchronized void uninstallPlugin(String pluginId) throws IOException {
        uninstallPlugin(pluginId, true);
    }

    /**
     * Uninstall a plugin with control over user data preservation.
     *
     * @param pluginId plugin id
     * @param keepUserData true to preserve plugins/&lt;id&gt;/data/, false to delete everything
     */
    public synchronized void uninstallPlugin(String pluginId, boolean keepUserData) throws IOException {
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

    public List<LaunchHook> getLaunchHooks() {
        return Collections.unmodifiableList(launchHooks);
    }

    // ==================== Event System ====================

    public void fireEvent(PmclEvent event) {
        // M24 修复：异步派发——快照 listeners，提交到线程池，避免慢 listener 阻塞调用方。
        // 注意：同一 listener 可能收到乱序事件；若需顺序保证，应在 listener 内部加队列。
        // eventListeners 是 CopyOnWriteArrayList，迭代时自动快照，无需额外复制。
        for (EventListener listener : eventListeners) {
            eventExecutor.submit(() -> {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                    System.err.println("[PluginManager] Event listener error: " + e.getMessage());
                }
            });
        }
    }

    // ==================== Launch Hooks ====================

    public boolean beforeLaunch(String versionId, String accountName) {
        for (LaunchHook hook : launchHooks) {
            try {
                if (!hook.beforeLaunch(versionId, accountName)) {
                    System.out.println("[PluginManager] Launch cancelled by plugin hook");
                    return false;
                }
            } catch (Exception e) {
                System.err.println("[PluginManager] Launch hook error: " + e.getMessage());
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

    // ==================== Descriptor Parsing ====================

    private PluginInfo parseDescriptor(Path jarPath) throws Exception {
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
                System.err.println("[PluginManager] WARNING: plugin JAR is not signed (" +
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

            return new PluginInfo(id, name, version, author, description, apiVersion, mainClass,
                    dependencies, website, license, permissions);
        }
    }

    private static IllegalArgumentException missingRequiredField(String fieldKey) {
        return new IllegalArgumentException(
                "Missing or blank required field '" + fieldKey + "' in " +
                PluginInfo.PROPERTIES_PATH + ". Required fields: " +
                "plugin.id, plugin.name, plugin.version, plugin.author, " +
                "plugin.description, plugin.api-version, plugin.main-class");
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

    /** A loaded plugin entry.
     *  M19 修复：所有字段 private，仅通过 getter 暴露只读视图；
     *  state 转换通过包级 setState 方法，强制走 PluginManager 的状态机。 */
    public static class PluginEntry {
        private final PluginInfo info;
        private final PmclPlugin plugin;
        private final PluginContextImpl context;
        private final PluginIsolatingClassLoader classLoader;
        private final Path jarPath;
        /** Whether this plugin was loaded from a .ppk package (true) or a single .jar (false). */
        private final boolean isPackage;
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
            this.state = PluginState.LOADED;
        }

        public PluginInfo getInfo() { return info; }
        public PmclPlugin getPlugin() { return plugin; }
        public PluginContextImpl getContext() { return context; }
        public PluginIsolatingClassLoader getClassLoader() { return classLoader; }
        public Path getJarPath() { return jarPath; }
        public boolean isPackage() { return isPackage; }
        public PluginState getState() { return state; }

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
        public void onEvent(PmclEvent event) { delegate.onEvent(event); }
    }

    private static class TrackedLaunchHook implements LaunchHook {
        final String pluginId;
        private final LaunchHook delegate;
        TrackedLaunchHook(String pluginId, LaunchHook delegate) {
            this.pluginId = pluginId;
            this.delegate = delegate;
        }
        @Override
        public boolean beforeLaunch(String versionId, String accountName) {
            return delegate.beforeLaunch(versionId, accountName);
        }
        @Override
        public void afterLaunch(String versionId, int exitCode) {
            delegate.afterLaunch(versionId, exitCode);
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
            // 权限校验：敏感服务要求插件声明对应权限
            String requiredPermission = requiredPermissionFor(type);
            if (requiredPermission != null) {
                PluginEntry entry = manager.loadedPlugins.get(pluginId);
                List<String> perms = (entry != null) ? entry.getInfo().getPermissions() : java.util.Collections.emptyList();
                if (!perms.contains(requiredPermission)) {
                    System.err.println("[PluginManager] SECURITY: plugin '" + pluginId
                            + "' attempted to access " + type.getSimpleName()
                            + " without declaring permission " + requiredPermission);
                    throw new SecurityException(
                            "Plugin '" + pluginId + "' lacks required permission '" + requiredPermission
                            + "' to access " + type.getSimpleName()
                            + ". Declare it via 'plugin.permissions=" + requiredPermission
                            + "' in META-INF/pmcl-plugin.properties.");
                }
                // 审计日志：敏感服务访问记录
                System.err.println("[PluginManager] AUDIT: plugin '" + pluginId
                        + "' acquired " + type.getSimpleName()
                        + " (permission=" + requiredPermission + ")");
            }

            if (type == LauncherCore.class) return (T) manager.core;
            if (type == com.pmcl.core.preferences.Preferences.class) return (T) manager.core.getPreferences();
            if (type == com.pmcl.core.version.VersionManager.class) return (T) manager.core.versions();
            if (type == com.pmcl.core.download.DownloadManager.class) return (T) manager.core.downloads();
            if (type == com.pmcl.core.launch.LaunchManager.class) return (T) manager.core.launch();
            if (type == com.pmcl.core.multiplayer.MultiplayerManager.class) return (T) manager.core.multiplayer();
            if (type == com.pmcl.core.auth.AuthService.class) return (T) manager.core.auth();
            if (type == com.pmcl.core.install.VersionInstaller.class) return (T) manager.core.install();
            if (type == com.pmcl.core.modloader.ModLoaderManager.class) return (T) manager.core.modLoaders();
            if (type == com.pmcl.core.market.ModMarketManager.class) return (T) manager.core.modMarket();
            if (type == com.pmcl.core.mods.ModManager.class) return (T) manager.core.modManager();
            if (type == com.pmcl.core.news.NewsClient.class) return (T) manager.core.news();
            if (type == com.pmcl.core.migration.MigrationManager.class) return (T) manager.core.migration();
            if (type == com.pmcl.core.runtime.RuntimeManager.class) return (T) manager.core.runtime();
            if (type == com.pmcl.core.runtime.JavaRuntimeDownloader.class) return (T) manager.core.javaDownloader();
            if (type == com.pmcl.core.update.SelfUpdater.class) return (T) manager.core.selfUpdater();
            if (type == com.pmcl.core.gamecontent.WorldManager.class) return (T) manager.core.worlds();
            if (type == com.pmcl.core.gamecontent.ScreenshotManager.class) return (T) manager.core.screenshots();
            if (type == com.pmcl.core.gamecontent.ResourcePackManager.class) return (T) manager.core.resourcePacks();
            if (type == com.pmcl.core.gamecontent.ShaderPackManager.class) return (T) manager.core.shaderPacks();
            if (type == com.pmcl.core.gamecontent.DatapackManager.class) return (T) manager.core.datapacks();
            if (type == com.pmcl.core.install.IntegrityChecker.class) return (T) manager.core.integrity();
            if (type == com.pmcl.core.launch.CrashAnalyzer.class) return (T) manager.core.crashAnalyzer();
            if (type == com.pmcl.core.launch.ProcessMonitor.class) return (T) manager.core.processMonitor();
            if (type == com.pmcl.core.launch.LaunchProfileBuilder.class) return (T) manager.core.profileBuilder();
            return null;
        }

        /**
         * 返回访问给定服务类型所需的权限名称。返回 null 表示该服务无需特殊权限。
         * 与 PluginPermission 枚举中的常量名匹配。
         */
        private static String requiredPermissionFor(Class<?> type) {
            // 含 token / 账号凭据
            if (type == com.pmcl.core.auth.AuthService.class) return "READ_ACCOUNTS";
            // 可启动/停止 Minecraft 进程
            if (type == com.pmcl.core.launch.LaunchManager.class) return "CONTROL_LAUNCH";
            if (type == com.pmcl.core.launch.LaunchProfileBuilder.class) return "CONTROL_LAUNCH";
            // 可杀死进程
            if (type == com.pmcl.core.launch.ProcessMonitor.class) return "KILL_PROCESS";
            // 可替换启动器 JAR
            if (type == com.pmcl.core.update.SelfUpdater.class) return "SELF_UPDATE";
            // 其他服务（Preferences/VersionManager/DownloadManager 等）默认开放
            return null;
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
                RegisteredCommand cmd = new RegisteredCommand(pluginId, name, description, handler);
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
                throw new IllegalArgumentException(
                        "Page title must not be null or blank (page: " + id + ", plugin: " + pluginId + ")");
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
            }
        }

        @Override
        public void registerLaunchHook(LaunchHook hook) {
            manager.launchHooks.add(new TrackedLaunchHook(pluginId, hook));
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
