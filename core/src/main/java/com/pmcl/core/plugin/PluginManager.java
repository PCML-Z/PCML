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
import java.net.URLClassLoader;
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
        try {
            if (Files.exists(stateFile)) {
                String json = Files.readString(stateFile, java.nio.charset.StandardCharsets.UTF_8);
                Map<String, Object> state = gson.fromJson(json, Map.class);
                if (state != null) {
                    Object enabled = state.get("enabled");
                    if (enabled instanceof Map) {
                        enabledState = new HashMap<>((Map<String, Boolean>) enabled);
                    }
                    Object configs = state.get("configs");
                    if (configs instanceof Map) {
                        pluginConfigs = (Map<String, Map<String, String>>) configs;
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
            if (entry.state == PluginState.LOADED && isEnabled(entry.info.getId())) {
                enablePlugin(entry.info.getId());
            }
        }
    }

    /**
     * Load a plugin from a JAR file.
     * @return the PluginInfo of the loaded plugin
     * @throws Exception if loading fails
     */
    public synchronized PluginInfo loadPlugin(Path jarPath) throws Exception {
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

        // Create classloader with PMCL classloader as parent
        URL[] urls = {jarPath.toUri().toURL()};
        URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader());

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
        PluginContextImpl ctx = new PluginContextImpl(info.getId());

        PluginEntry entry = new PluginEntry(info, plugin, ctx, classLoader, jarPath);
        entry.state = PluginState.LOADED;
        loadedPlugins.put(info.getId(), entry);

        // Call onLoad
        try {
            plugin.onLoad();
        } catch (Exception e) {
            System.err.println("[PluginManager] onLoad failed for " + info.getId() + ": " + e.getMessage());
            entry.state = PluginState.FAILED;
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
        if (entry.state == PluginState.ENABLED) return;
        if (entry.state != PluginState.LOADED && entry.state != PluginState.DISABLED)
            throw new IllegalStateException("Plugin not in loadable state: " + pluginId + " (" + entry.state + ")");

        try {
            entry.plugin.onEnable(entry.context);
            entry.state = PluginState.ENABLED;
            enabledState.put(pluginId, true);
            saveState();
            System.out.println("[PluginManager] Enabled plugin: " + pluginId);
            bumpRevision();
            fireEvent(new PluginEnabledEvent(pluginId));
        } catch (Exception e) {
            System.err.println("[PluginManager] Failed to enable " + pluginId + ": " + e.getMessage());
            entry.state = PluginState.FAILED;
            fireEvent(new PluginErrorEvent(pluginId, e));
        }
    }

    /**
     * Disable an enabled plugin (calls onDisable).
     */
    public synchronized void disablePlugin(String pluginId) {
        PluginEntry entry = loadedPlugins.get(pluginId);
        if (entry == null) return;
        if (entry.state != PluginState.ENABLED) return;

        try {
            entry.plugin.onDisable();
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

        entry.state = PluginState.DISABLED;
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
                entry.classLoader.close();
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
        boolean wasEnabled = entry.state == PluginState.ENABLED;
        Path sourcePath = entry.jarPath;
        boolean wasPackage = entry.isPackage;
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
     */
    public PluginInfo installFromUrl(String url) throws Exception {
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

        // Create classloader from classes/ + lib/*.jar
        URLClassLoader classLoader = PluginPackageBuilder.createClassLoader(packageDir, pkg, getClass().getClassLoader());

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
        PluginContextImpl ctx = new PluginContextImpl(info.getId());

        PluginEntry entry = new PluginEntry(info, plugin, ctx, classLoader, ppkPath, true);
        entry.state = PluginState.LOADED;
        loadedPlugins.put(info.getId(), entry);

        // Call onLoad
        try {
            plugin.onLoad();
        } catch (Exception e) {
            System.err.println("[PluginManager] onLoad failed for " + info.getId() + ": " + e.getMessage());
            entry.state = PluginState.FAILED;
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
     */
    public PluginInfo installFromPackageUrl(String url) throws Exception {
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
     * Uninstall a plugin (unload + delete JAR/.ppk + delete extracted dir + delete data).
     */
    public synchronized void uninstallPlugin(String pluginId) throws IOException {
        unloadPlugin(pluginId);
        // Delete the source file (could be .jar or .ppk)
        Path jar = pluginsDir.resolve(pluginId + ".jar");
        Path ppk = pluginsDir.resolve(pluginId + ".ppk");
        Files.deleteIfExists(jar);
        Files.deleteIfExists(ppk);
        // Delete the extracted package directory (for .ppk plugins)
        Path dataDir = pluginsDir.resolve(pluginId);
        deleteDirectory(dataDir);
        enabledState.remove(pluginId);
        pluginConfigs.remove(pluginId);
        saveState();
        System.out.println("[PluginManager] Uninstalled plugin: " + pluginId);
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
        for (EventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                System.err.println("[PluginManager] Event listener error: " + e.getMessage());
            }
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
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(PluginInfo.PROPERTIES_PATH);
            if (entry == null) {
                throw new IllegalArgumentException(
                        "Missing " + PluginInfo.PROPERTIES_PATH + " in plugin JAR. " +
                        "A plugin JAR must contain this descriptor file at exactly this path " +
                        "(case-sensitive). See PluginInfo docs for format specification.");
            }
            Properties props = new Properties();
            try (InputStream is = jar.getInputStream(entry)) {
                props.load(is);
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

            return new PluginInfo(id, name, version, author, description, apiVersion, mainClass,
                    dependencies, website, license);
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

    /** A loaded plugin entry */
    public static class PluginEntry {
        public final PluginInfo info;
        public final PmclPlugin plugin;
        public final PluginContextImpl context;
        public final URLClassLoader classLoader;
        public final Path jarPath;
        /** Whether this plugin was loaded from a .ppk package (true) or a single .jar (false). */
        public final boolean isPackage;
        public PluginState state;

        PluginEntry(PluginInfo info, PmclPlugin plugin, PluginContextImpl context,
                    URLClassLoader classLoader, Path jarPath) {
            this(info, plugin, context, classLoader, jarPath, false);
        }

        PluginEntry(PluginInfo info, PmclPlugin plugin, PluginContextImpl context,
                    URLClassLoader classLoader, Path jarPath, boolean isPackage) {
            this.info = info;
            this.plugin = plugin;
            this.context = context;
            this.classLoader = classLoader;
            this.jarPath = jarPath;
            this.isPackage = isPackage;
            this.state = PluginState.LOADED;
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

    public class PluginContextImpl implements PluginContext {
        private final String pluginId;

        PluginContextImpl(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getService(Class<T> type) {
            if (type == LauncherCore.class) return (T) core;
            if (type == com.pmcl.core.preferences.Preferences.class) return (T) core.getPreferences();
            if (type == com.pmcl.core.version.VersionManager.class) return (T) core.versions();
            if (type == com.pmcl.core.download.DownloadManager.class) return (T) core.downloads();
            if (type == com.pmcl.core.launch.LaunchManager.class) return (T) core.launch();
            if (type == com.pmcl.core.multiplayer.MultiplayerManager.class) return (T) core.multiplayer();
            if (type == com.pmcl.core.auth.AuthService.class) return (T) core.auth();
            if (type == com.pmcl.core.install.VersionInstaller.class) return (T) core.install();
            if (type == com.pmcl.core.modloader.ModLoaderManager.class) return (T) core.modLoaders();
            if (type == com.pmcl.core.market.ModMarketManager.class) return (T) core.modMarket();
            if (type == com.pmcl.core.mods.ModManager.class) return (T) core.modManager();
            if (type == com.pmcl.core.news.NewsClient.class) return (T) core.news();
            if (type == com.pmcl.core.migration.MigrationManager.class) return (T) core.migration();
            if (type == com.pmcl.core.runtime.RuntimeManager.class) return (T) core.runtime();
            if (type == com.pmcl.core.runtime.JavaRuntimeDownloader.class) return (T) core.javaDownloader();
            if (type == com.pmcl.core.update.SelfUpdater.class) return (T) core.selfUpdater();
            if (type == com.pmcl.core.gamecontent.WorldManager.class) return (T) core.worlds();
            if (type == com.pmcl.core.gamecontent.ScreenshotManager.class) return (T) core.screenshots();
            if (type == com.pmcl.core.gamecontent.ResourcePackManager.class) return (T) core.resourcePacks();
            if (type == com.pmcl.core.gamecontent.ShaderPackManager.class) return (T) core.shaderPacks();
            if (type == com.pmcl.core.gamecontent.DatapackManager.class) return (T) core.datapacks();
            if (type == com.pmcl.core.install.IntegrityChecker.class) return (T) core.integrity();
            if (type == com.pmcl.core.launch.CrashAnalyzer.class) return (T) core.crashAnalyzer();
            if (type == com.pmcl.core.launch.ProcessMonitor.class) return (T) core.processMonitor();
            if (type == com.pmcl.core.launch.LaunchProfileBuilder.class) return (T) core.profileBuilder();
            return null;
        }

        @Override
        public Path getDataDir() {
            Path dir = pluginsDir.resolve(pluginId).resolve("data");
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                System.err.println("[Plugin:" + pluginId + "] Failed to create data dir: " + e.getMessage());
            }
            return dir;
        }

        @Override
        public String getConfig(String key) {
            synchronized (PluginManager.this) {
                Map<String, String> cfg = pluginConfigs.get(pluginId);
                return cfg != null ? cfg.get(key) : null;
            }
        }

        @Override
        public void setConfig(String key, String value) {
            synchronized (PluginManager.this) {
                pluginConfigs.computeIfAbsent(pluginId, k -> new HashMap<>()).put(key, value);
                saveState();
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
            synchronized (PluginManager.this) {
                // Check for duplicate command name within the same plugin
                List<RegisteredCommand> existing = customCommands.get(pluginId);
                if (existing != null) {
                    for (RegisteredCommand c : existing) {
                        if (c.name.equals(name)) {
                            throw new IllegalStateException(
                                    "Duplicate command name '" + name + "' in plugin '" + pluginId + "'");
                        }
                    }
                }
                RegisteredCommand cmd = new RegisteredCommand(pluginId, name, description, handler);
                customCommands.computeIfAbsent(pluginId, k -> new ArrayList<>()).add(cmd);
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
            synchronized (PluginManager.this) {
                // Check for duplicate page id within the same plugin
                List<RegisteredPage> existingPages = customPages.get(pluginId);
                if (existingPages != null) {
                    for (RegisteredPage p : existingPages) {
                        if (p.id.equals(id)) {
                            throw new IllegalStateException(
                                    "Duplicate page id '" + id + "' in plugin '" + pluginId + "'");
                        }
                    }
                }
                RegisteredPage page = new RegisteredPage(pluginId, id, title, content);
                customPages.computeIfAbsent(pluginId, k -> new ArrayList<>()).add(page);
            }
        }

        @Override
        public void registerLaunchHook(LaunchHook hook) {
            launchHooks.add(new TrackedLaunchHook(pluginId, hook));
        }

        @Override
        public void addEventListener(EventListener listener) {
            eventListeners.add(new TrackedEventListener(pluginId, listener));
        }

        @Override
        public void fireEvent(PmclEvent event) {
            PluginManager.this.fireEvent(event);
        }
    }
}
