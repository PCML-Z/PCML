package com.pmcl.core.plugin;

import com.pmcl.core.LauncherCore;
import com.pmcl.core.auth.Account;
import com.pmcl.core.auth.AccountStore;
import com.pmcl.core.instance.InstanceInfo;
import com.pmcl.core.mods.ModMeta;
import com.pmcl.core.mods.ModScanner;
import com.pmcl.core.version.VersionManager;
import com.pmcl.plugin.AccountAddedEvent;
import com.pmcl.plugin.AccountRemovedEvent;
import com.pmcl.plugin.AccountSelectedEvent;
import com.pmcl.plugin.DownloadCompletedEvent;
import com.pmcl.plugin.InstanceCreatedEvent;
import com.pmcl.plugin.InstanceDeletedEvent;
import com.pmcl.plugin.ModToggledEvent;
import com.pmcl.plugin.NavigationEvent;
import com.pmcl.plugin.SettingsChangedEvent;
import com.pmcl.plugin.ThemeChangedEvent;
import com.pmcl.plugin.api.AccountSummary;
import com.pmcl.plugin.api.AccountsApi;
import com.pmcl.plugin.api.DownloadsApi;
import com.pmcl.plugin.api.FilesystemApi;
import com.pmcl.plugin.api.HttpApi;
import com.pmcl.plugin.api.HttpResponseSummary;
import com.pmcl.plugin.api.InstanceSummary;
import com.pmcl.plugin.api.InstancesApi;
import com.pmcl.plugin.api.LaunchApi;
import com.pmcl.plugin.api.ModSummary;
import com.pmcl.plugin.api.ModsApi;
import com.pmcl.plugin.api.NotificationLevel;
import com.pmcl.plugin.api.PluginInfoSummary;
import com.pmcl.plugin.api.PluginNotification;
import com.pmcl.plugin.api.PluginsApi;
import com.pmcl.plugin.api.RemoteVersionSummary;
import com.pmcl.plugin.api.SchedulerApi;
import com.pmcl.plugin.api.SettingsApi;
import com.pmcl.plugin.api.UiApi;
import com.pmcl.plugin.api.VersionSummary;
import com.pmcl.plugin.api.VersionsApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Stable typed API implementations for plugins (no core types leak into plugin-api).
 */
final class PluginApiFacades {

    private PluginApiFacades() {}

    static VersionsApi versions(LauncherCore core, PermissionGate gate) {
        return new VersionsApi() {
            @Override
            public List<String> listLocalVersionIds() {
                return new ArrayList<>(core.versions().listLocalVersions());
            }

            @Override
            public List<VersionSummary> listLocalVersions() {
                List<VersionSummary> out = new ArrayList<>();
                for (VersionManager.LocalVersionInfo v : core.versions().scanAllLocalVersions()) {
                    out.add(new VersionSummary(
                            v.getId(),
                            v.isLaunchable(),
                            emptyToNull(v.getInheritsFrom()),
                            v.getLastModified()));
                }
                return out;
            }

            @Override
            public boolean isLaunchable(String versionId) {
                if (versionId == null || versionId.isBlank()) return false;
                for (VersionManager.LocalVersionInfo v : core.versions().scanAllLocalVersions()) {
                    if (versionId.equals(v.getId())) return v.isLaunchable();
                }
                return false;
            }

            @Override
            public String installVersion(String versionId) {
                gate.require("MANAGE_VERSIONS");
                if (versionId == null || versionId.isBlank()) {
                    throw new IllegalArgumentException("versionId is blank");
                }
                if (core.downloadQueue() != null) {
                    return core.downloadQueue().submitVersionInstall(versionId);
                }
                try {
                    core.install().install(versionId, null).join();
                    return "";
                } catch (Exception e) {
                    throw new RuntimeException("installVersion failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void deleteVersion(String versionId) {
                gate.require("MANAGE_VERSIONS");
                if (versionId == null || versionId.isBlank()) {
                    throw new IllegalArgumentException("versionId is blank");
                }
                if (versionId.contains("..") || versionId.contains("/") || versionId.contains("\\")) {
                    throw new IllegalArgumentException("invalid versionId");
                }
                Path dir = core.getConfig().getVersionsDir().resolve(versionId);
                if (!Files.isDirectory(dir)) {
                    throw new IllegalArgumentException("Version not found: " + versionId);
                }
                try {
                    deleteRecursively(dir);
                } catch (IOException e) {
                    throw new RuntimeException("deleteVersion failed: " + e.getMessage(), e);
                }
            }

            @Override
            public Path resolveVersionDir(String versionId) {
                if (versionId == null || versionId.isBlank()) {
                    throw new IllegalArgumentException("versionId is blank");
                }
                return core.getConfig().getVersionsDir().resolve(versionId);
            }

            @Override
            public List<RemoteVersionSummary> listRemoteVersions(int limit) {
                gate.require("NETWORK");
                try {
                    int lim = Math.max(1, Math.min(limit, 500));
                    List<com.pmcl.core.version.McVersion> remote =
                            core.versions().fetchRemoteVersions().join();
                    List<RemoteVersionSummary> out = new ArrayList<>();
                    if (remote != null) {
                        int n = 0;
                        for (var v : remote) {
                            if (n++ >= lim) break;
                            out.add(new RemoteVersionSummary(
                                    nz(v.getId()),
                                    nz(v.getType()),
                                    nz(v.getReleaseTime()),
                                    nz(v.getUrl())));
                        }
                    }
                    return out;
                } catch (Exception e) {
                    throw new RuntimeException("listRemoteVersions failed: " + e.getMessage(), e);
                }
            }
        };
    }

    static InstancesApi instances(LauncherCore core, PluginManager manager, String pluginId,
                                  PermissionGate gate) {
        return new InstancesApi() {
            @Override
            public List<InstanceSummary> listInstances() {
                List<InstanceSummary> out = new ArrayList<>();
                for (InstanceInfo info : core.instances().listInstances()) {
                    out.add(toSummary(info));
                }
                return out;
            }

            @Override
            public InstanceSummary getInstance(String instanceId) {
                for (InstanceInfo info : core.instances().listInstances()) {
                    if (Objects.equals(info.getInstanceId(), instanceId)) {
                        return toSummary(info);
                    }
                }
                return null;
            }

            @Override
            public String createInstance(String name, String baseVersionId, String loader, String loaderVersion) {
                gate.require("MANAGE_INSTANCES");
                try {
                    InstanceInfo created = core.instances().createInstance(
                            name, baseVersionId, loader, loaderVersion);
                    manager.fireEvent(new InstanceCreatedEvent(created.getInstanceId(), created.getName()));
                    return created.getInstanceId();
                } catch (Exception e) {
                    throw new RuntimeException("createInstance failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void renameInstance(String instanceId, String newName) {
                gate.require("MANAGE_INSTANCES");
                try {
                    core.instances().renameInstance(instanceId, newName);
                } catch (IOException e) {
                    throw new RuntimeException("renameInstance failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void deleteInstance(String instanceId) {
                gate.require("MANAGE_INSTANCES");
                try {
                    core.instances().deleteInstance(instanceId);
                    manager.fireEvent(new InstanceDeletedEvent(instanceId));
                } catch (IOException e) {
                    throw new RuntimeException("deleteInstance failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void setDescription(String instanceId, String description) {
                gate.require("MANAGE_INSTANCES");
                try {
                    InstanceInfo info = null;
                    for (InstanceInfo i : core.instances().listInstances()) {
                        if (Objects.equals(i.getInstanceId(), instanceId)) {
                            info = i;
                            break;
                        }
                    }
                    if (info == null) throw new IllegalArgumentException("Instance not found: " + instanceId);
                    info.setDescription(description);
                    core.instances().updateInstance(info);
                } catch (IOException e) {
                    throw new RuntimeException("setDescription failed: " + e.getMessage(), e);
                }
            }

            @Override
            public Path resolveInstanceDir(String instanceId) {
                if (instanceId == null || instanceId.isBlank()) {
                    throw new IllegalArgumentException("instanceId is blank");
                }
                return core.instances().resolveInstanceDir(instanceId);
            }
        };
    }

    static AccountsApi accounts(LauncherCore core, PluginManager manager, PermissionGate gate) {
        return new AccountsApi() {
            @Override
            public List<AccountSummary> listAccounts() {
                gate.require("READ_ACCOUNTS");
                return loadSummaries(core);
            }

            @Override
            public AccountSummary getSelectedAccount() {
                gate.require("READ_ACCOUNTS");
                for (AccountSummary a : loadSummaries(core)) {
                    if (a.getSelected()) return a;
                }
                return null;
            }

            @Override
            public void selectAccount(String uuid) {
                gate.require("WRITE_ACCOUNTS");
                if (uuid == null || uuid.isBlank()) {
                    throw new IllegalArgumentException("uuid is blank");
                }
                try {
                    Path file = accountsFile(core);
                    AccountStore store = core.auth().loadStore(file);
                    boolean found = false;
                    String username = "";
                    for (Account a : store.getAccounts()) {
                        if (uuid.equals(a.getUuid())) {
                            found = true;
                            username = a.getUsername();
                            break;
                        }
                    }
                    if (!found) throw new IllegalArgumentException("Account not found: " + uuid);
                    core.auth().saveStore(store.select(uuid), file);
                    manager.fireEvent(new AccountSelectedEvent(uuid, username));
                } catch (IOException e) {
                    throw new RuntimeException("selectAccount failed: " + e.getMessage(), e);
                }
            }

            @Override
            public String addOfflineAccount(String username) {
                gate.require("WRITE_ACCOUNTS");
                if (username == null || username.isBlank()) {
                    throw new IllegalArgumentException("username is blank");
                }
                try {
                    Account acc = core.auth().offline(username.trim());
                    Path file = accountsFile(core);
                    AccountStore store = core.auth().loadStore(file);
                    AccountStore next = store.upsert(acc);
                    core.auth().saveStore(next, file);
                    manager.fireEvent(new AccountAddedEvent(acc.getUuid(), acc.getUsername(), "OFFLINE"));
                    manager.fireEvent(new AccountSelectedEvent(acc.getUuid(), acc.getUsername()));
                    return acc.getUuid();
                } catch (IOException e) {
                    throw new RuntimeException("addOfflineAccount failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void removeAccount(String uuid) {
                gate.require("WRITE_ACCOUNTS");
                if (uuid == null || uuid.isBlank()) {
                    throw new IllegalArgumentException("uuid is blank");
                }
                try {
                    Path file = accountsFile(core);
                    AccountStore store = core.auth().loadStore(file);
                    core.auth().saveStore(store.remove(uuid), file);
                    manager.fireEvent(new AccountRemovedEvent(uuid));
                } catch (IOException e) {
                    throw new RuntimeException("removeAccount failed: " + e.getMessage(), e);
                }
            }
        };
    }

    static LaunchApi launch(LauncherCore core, PluginManager manager, PermissionGate gate) {
        return new LaunchApi() {
            @Override
            public String requestLaunch(String versionId) {
                gate.require("CONTROL_LAUNCH");
                if (versionId == null || versionId.isBlank()) {
                    return "versionId is blank";
                }
                Consumer<String> handler = manager.getLaunchRequestHandler();
                if (handler == null) {
                    return "Host UI has not registered a launch handler";
                }
                try {
                    handler.accept(versionId);
                    return null;
                } catch (Exception e) {
                    return e.getMessage() != null ? e.getMessage() : "launch request failed";
                }
            }

            @Override
            public String requestLaunchInstance(String instanceId) {
                gate.require("CONTROL_LAUNCH");
                if (instanceId == null || instanceId.isBlank()) {
                    return "instanceId is blank";
                }
                InstanceInfo info = null;
                for (InstanceInfo i : core.instances().listInstances()) {
                    if (Objects.equals(i.getInstanceId(), instanceId)) {
                        info = i;
                        break;
                    }
                }
                if (info == null) return "Instance not found: " + instanceId;
                String versionId = info.getBaseVersionId();
                if (versionId == null || versionId.isBlank()) {
                    return "Instance has no base version";
                }
                return requestLaunch(versionId);
            }

            @Override
            public void killAllGames() {
                gate.require("CONTROL_LAUNCH");
                gate.require("KILL_PROCESS");
                core.launch().killAllProcesses();
            }

            @Override
            public boolean isGameRunning() {
                return core.launch().hasActiveProcesses();
            }

            @Override
            public int activeProcessCount() {
                return core.launch().activeProcessCount();
            }
        };
    }

    static DownloadsApi downloads(LauncherCore core, PluginManager manager, String pluginId,
                                  Path dataDir, PermissionGate gate) {
        return new DownloadsApi() {
            @Override
            public void downloadTo(String url, Path target) {
                downloadTo(url, target, null);
            }

            @Override
            public void downloadTo(String url, Path target, java.util.function.LongConsumer onProgress) {
                gate.require("NETWORK");
                Path absTarget = PluginPathSandbox.requireAccessible(
                        target, dataDir, core.getConfig().getWorkDir(), false);
                if (!PluginPathSandbox.isUnderPluginData(absTarget, dataDir)) {
                    throw new SecurityException("Download target must be under plugin data dir: " + dataDir);
                }
                String safeUrl = manager.applyUrlRewrites(url);
                try {
                    Files.createDirectories(absTarget.getParent());
                    core.downloads().downloadToSsrfChecked(safeUrl, absTarget,
                            onProgress != null ? onProgress::accept : null);
                    manager.fireEvent(new DownloadCompletedEvent(safeUrl, absTarget.toString(), true));
                } catch (Exception e) {
                    manager.fireEvent(new DownloadCompletedEvent(safeUrl,
                            absTarget.toString(), false));
                    throw new RuntimeException("download failed: " + e.getMessage(), e);
                }
            }

            @Override
            public String downloadString(String url) {
                gate.require("NETWORK");
                try {
                    String safeUrl = manager.applyUrlRewrites(url);
                    return core.downloads().downloadStringSsrfChecked(safeUrl);
                } catch (Exception e) {
                    throw new RuntimeException("downloadString failed: " + e.getMessage(), e);
                }
            }
        };
    }

    static ModsApi mods(LauncherCore core, PluginManager manager, PermissionGate gate) {
        return new ModsApi() {
            @Override
            public List<ModSummary> scanMods(Path modsDir) {
                gate.require("READ_MODS");
                if (modsDir == null || !Files.isDirectory(modsDir)) {
                    return Collections.emptyList();
                }
                Path work = core.getConfig().getWorkDir().toAbsolutePath().normalize();
                Path abs = modsDir.toAbsolutePath().normalize();
                if (!abs.startsWith(work)) {
                    throw new SecurityException("scanMods path must be under work dir: " + work);
                }
                PluginPathSandbox.denySensitive(work, abs);
                try {
                    List<ModSummary> out = new ArrayList<>();
                    for (ModMeta m : ModScanner.scanDirectory(abs)) {
                        out.add(new ModSummary(
                                m.getModId() != null ? m.getModId() : "",
                                m.getName() != null ? m.getName() : "",
                                m.getVersion() != null ? m.getVersion() : "",
                                m.getLoader() != null ? m.getLoader() : "unknown",
                                m.getJarFile() != null ? m.getJarFile() : "",
                                m.isDisabled()));
                    }
                    return out;
                } catch (IOException e) {
                    throw new RuntimeException("scanMods failed: " + e.getMessage(), e);
                }
            }

            @Override
            public Path resolveModsDir(String versionOrInstanceId) {
                var pref = core.getPreferences();
                Path work = core.getConfig().getWorkDir();
                if (pref.isVersionIsolation() && versionOrInstanceId != null
                        && !versionOrInstanceId.isBlank()) {
                    Path instanceMods = work.resolve("instances")
                            .resolve(versionOrInstanceId).resolve("mods");
                    if (Files.isDirectory(instanceMods)
                            || Files.isDirectory(work.resolve("instances").resolve(versionOrInstanceId))) {
                        return instanceMods;
                    }
                    return work.resolve("versions").resolve(versionOrInstanceId).resolve("mods");
                }
                Path mods = work.resolve("mods");
                if (versionOrInstanceId != null && !versionOrInstanceId.isBlank()) {
                    return mods.resolve(versionOrInstanceId);
                }
                return mods;
            }

            @Override
            public void enableMod(Path jarPath) {
                gate.require("MANAGE_MODS");
                Path safe = requireModJar(jarPath);
                try {
                    core.modManager().enableModAt(safe);
                    String name = safe.getFileName().toString();
                    manager.fireEvent(new ModToggledEvent(name, true));
                } catch (Exception e) {
                    throw new RuntimeException("enableMod failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void disableMod(Path jarPath) {
                gate.require("MANAGE_MODS");
                Path safe = requireModJar(jarPath);
                try {
                    core.modManager().disableModAt(safe);
                    String name = safe.getFileName().toString();
                    manager.fireEvent(new ModToggledEvent(name, false));
                } catch (Exception e) {
                    throw new RuntimeException("disableMod failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void deleteMod(Path jarPath) {
                gate.require("MANAGE_MODS");
                Path safe = requireModJar(jarPath);
                try {
                    core.modManager().deleteModAt(safe);
                } catch (Exception e) {
                    throw new RuntimeException("deleteMod failed: " + e.getMessage(), e);
                }
            }

            private Path requireModJar(Path jarPath) {
                Path work = core.getConfig().getWorkDir();
                if (!PluginPathSandbox.isModJarPath(jarPath, work)) {
                    throw new SecurityException("Mod path must be a .jar under workDir mods trees: " + jarPath);
                }
                return PluginPathSandbox.requireUnderWorkDir(jarPath, work);
            }
        };
    }

    static SettingsApi settings(LauncherCore core, PluginManager manager, String pluginId,
                                PermissionGate gate) {
        return new SettingsApi() {
            @Override public String getLanguage() { return core.getPreferences().getLanguage(); }
            @Override public String getThemePreset() { return core.getPreferences().getThemePreset(); }
            @Override public int getMaxMemoryMb() { return core.getPreferences().getMaxMemoryMb(); }
            @Override public int getMinMemoryMb() { return core.getPreferences().getMinMemoryMb(); }
            @Override public boolean isVersionIsolation() { return core.getPreferences().isVersionIsolation(); }
            @Override public boolean isDarkTheme() { return core.getPreferences().isUseDarkTheme(); }
            @Override public String getJavaPath() { return nz(core.getPreferences().getJavaPath()); }
            @Override public String getCustomJvmArgs() { return nz(core.getPreferences().getCustomJvmArgs()); }
            @Override public String getMirrorType() { return nz(core.getPreferences().getMirrorType()); }
            @Override public String getCustomMirrorBase() { return nz(core.getPreferences().getCustomMirrorBase()); }
            @Override public int getGameWindowWidth() { return core.getPreferences().getGameWindowWidth(); }
            @Override public int getGameWindowHeight() { return core.getPreferences().getGameWindowHeight(); }
            @Override public boolean isGameFullscreen() { return core.getPreferences().isGameFullscreen(); }
            @Override public int getDownloadThreads() { return core.getPreferences().getDownloadThreads(); }

            @Override
            public void setLanguage(String language) {
                gate.require("WRITE_SETTINGS");
                String lang = language != null ? language : "zh_CN";
                core.getPreferences().setLanguage(lang);
                core.applyLanguage(lang);
                manager.fireEvent(new SettingsChangedEvent("language", lang));
            }

            @Override
            public void setThemePreset(String preset) {
                gate.require("WRITE_SETTINGS");
                String p = preset != null ? preset : "default";
                core.getPreferences().setThemePreset(p);
                manager.fireEvent(new SettingsChangedEvent("themePreset", p));
                manager.fireEvent(new ThemeChangedEvent(p, nz(core.getPreferences().getCustomThemePackId())));
            }

            @Override
            public void setMaxMemoryMb(int mb) {
                gate.require("WRITE_SETTINGS");
                core.getPreferences().setMaxMemoryMb(mb);
                manager.fireEvent(new SettingsChangedEvent("maxMemoryMb", String.valueOf(mb)));
            }

            @Override
            public void setMinMemoryMb(int mb) {
                gate.require("WRITE_SETTINGS");
                core.getPreferences().setMinMemoryMb(mb);
                manager.fireEvent(new SettingsChangedEvent("minMemoryMb", String.valueOf(mb)));
            }

            @Override
            public void setVersionIsolation(boolean enabled) {
                gate.require("WRITE_SETTINGS");
                core.getPreferences().setVersionIsolation(enabled);
                manager.fireEvent(new SettingsChangedEvent("versionIsolation", String.valueOf(enabled)));
            }

            @Override
            public void setDarkTheme(boolean enabled) {
                gate.require("WRITE_SETTINGS");
                core.getPreferences().setUseDarkTheme(enabled);
                manager.fireEvent(new SettingsChangedEvent("darkTheme", String.valueOf(enabled)));
            }

            @Override
            public void setJavaPath(String path) {
                gate.require("WRITE_SETTINGS");
                String p = path != null ? path.trim() : "";
                if (!p.isEmpty()) {
                    Path javaPath = Path.of(p).toAbsolutePath().normalize();
                    Path work = core.getConfig().getWorkDir().toAbsolutePath().normalize();
                    Path runtimes = work.resolve("java").toAbsolutePath().normalize();
                    Path runtimesAlt = work.resolve("runtimes").toAbsolutePath().normalize();
                    boolean underManaged = javaPath.startsWith(runtimes) || javaPath.startsWith(runtimesAlt);
                    if (!underManaged) {
                        throw new SecurityException(
                                "setJavaPath only allows binaries under workDir/java or workDir/runtimes");
                    }
                    if (Files.exists(javaPath) && (Files.isSymbolicLink(javaPath)
                            || !Files.isExecutable(javaPath))) {
                        throw new SecurityException("setJavaPath rejected (symlink or non-executable): " + javaPath);
                    }
                    p = javaPath.toString();
                }
                core.getPreferences().setJavaPath(p);
                manager.fireEvent(new SettingsChangedEvent("javaPath", p));
            }

            @Override
            public void setCustomJvmArgs(String args) {
                gate.require("WRITE_SETTINGS");
                String sanitized = sanitizeHostJvmArgs(args);
                core.getPreferences().setCustomJvmArgs(sanitized);
                manager.fireEvent(new SettingsChangedEvent("customJvmArgs", sanitized));
            }

            @Override
            public void setMirrorType(String type) {
                gate.require("WRITE_SETTINGS");
                String t = type != null ? type.trim().toUpperCase(java.util.Locale.ROOT) : "OFFICIAL";
                if (!t.equals("OFFICIAL") && !t.equals("BMCLAPI") && !t.equals("CUSTOM")) {
                    throw new IllegalArgumentException("mirrorType must be OFFICIAL, BMCLAPI, or CUSTOM");
                }
                core.getPreferences().setMirrorType(t);
                core.applyNetworkPreferences();
                manager.fireEvent(new SettingsChangedEvent("mirrorType", t));
            }

            @Override
            public void setCustomMirrorBase(String base) {
                gate.require("WRITE_SETTINGS");
                String b = base != null ? base.trim() : "";
                if (!b.isEmpty()) {
                    if (!b.startsWith("https://")) {
                        throw new SecurityException("customMirrorBase must use https://");
                    }
                    String ssrf = com.pmcl.core.util.SsrfChecker.validate(b.endsWith("/") ? b : b + "/");
                    if (ssrf != null) {
                        throw new SecurityException("customMirrorBase SSRF blocked: " + ssrf);
                    }
                }
                core.getPreferences().setCustomMirrorBase(b);
                core.applyNetworkPreferences();
                manager.fireEvent(new SettingsChangedEvent("customMirrorBase", b));
            }

            @Override
            public void setGameWindowSize(int width, int height) {
                gate.require("WRITE_SETTINGS");
                core.getPreferences().setGameWindowWidth(width);
                core.getPreferences().setGameWindowHeight(height);
                manager.fireEvent(new SettingsChangedEvent("gameWindow", width + "x" + height));
            }

            @Override
            public void setGameFullscreen(boolean enabled) {
                gate.require("WRITE_SETTINGS");
                core.getPreferences().setGameFullscreen(enabled);
                manager.fireEvent(new SettingsChangedEvent("gameFullscreen", String.valueOf(enabled)));
            }

            @Override
            public void setDownloadThreads(int threads) {
                gate.require("WRITE_SETTINGS");
                core.getPreferences().setDownloadThreads(threads);
                manager.fireEvent(new SettingsChangedEvent("downloadThreads", String.valueOf(threads)));
            }

            @Override
            public String getPluginConfig(String key) {
                synchronized (manager) {
                    var cfg = manager.getPluginConfigMap(pluginId);
                    return cfg != null ? cfg.get(key) : null;
                }
            }

            @Override
            public void setPluginConfig(String key, String value) {
                manager.setPluginConfigValue(pluginId, key, value);
            }
        };
    }

    static UiApi ui(PluginManager manager, String pluginId) {
        return new UiApi() {
            @Override
            public void notify(String title, String message, NotificationLevel level) {
                manager.offerNotification(new PluginNotification(
                        pluginId,
                        title != null ? title : pluginId,
                        message != null ? message : "",
                        level != null ? level : NotificationLevel.INFO,
                        System.currentTimeMillis()));
            }

            @Override
            public void navigate(String target) {
                if (target == null || target.isBlank()) return;
                manager.fireEvent(new NavigationEvent(target));
                Consumer<String> handler = manager.getNavigationHandler();
                if (handler != null) {
                    try {
                        handler.accept(target);
                    } catch (Exception e) {
                        System.err.println("[Plugin:" + pluginId + "] navigate failed: " + e.getMessage());
                    }
                }
            }

            @Override
            public void copyToClipboard(String text) {
                Consumer<String> handler = manager.getClipboardHandler();
                if (handler != null) {
                    try {
                        handler.accept(text != null ? text : "");
                    } catch (Exception e) {
                        System.err.println("[Plugin:" + pluginId + "] clipboard failed: " + e.getMessage());
                    }
                } else {
                    throw new IllegalStateException("Host clipboard handler not registered");
                }
            }

            @Override
            public void showDialog(String title, String message,
                                   com.pmcl.plugin.api.DialogKind kind,
                                   String confirmLabel, String cancelLabel,
                                   com.pmcl.plugin.api.BooleanCallback onResult) {
                manager.offerDialog(new com.pmcl.plugin.api.PluginDialogRequest(
                        java.util.UUID.randomUUID().toString(),
                        pluginId,
                        title != null ? title : pluginId,
                        message != null ? message : "",
                        kind != null ? kind : com.pmcl.plugin.api.DialogKind.INFO,
                        confirmLabel != null ? confirmLabel : "OK",
                        cancelLabel != null ? cancelLabel : "Cancel",
                        onResult));
            }

            @Override
            public void openUrl(String url) {
                if (url == null || url.isBlank()) return;
                String u = url.trim();
                String lower = u.toLowerCase(java.util.Locale.ROOT);
                if (!(lower.startsWith("https://") || lower.startsWith("http://"))) {
                    throw new SecurityException("openUrl only allows http/https schemes");
                }
                String ssrf = com.pmcl.core.util.SsrfChecker.validate(u);
                if (ssrf != null) {
                    throw new SecurityException("openUrl SSRF blocked: " + ssrf);
                }
                Consumer<String> handler = manager.getOpenUrlHandler();
                if (handler != null) {
                    try {
                        handler.accept(u);
                    } catch (Exception e) {
                        System.err.println("[Plugin:" + pluginId + "] openUrl failed: " + e.getMessage());
                    }
                } else {
                    throw new IllegalStateException("Host openUrl handler not registered");
                }
            }

            @Override
            public void pickFile(String title, String filters, boolean save,
                                com.pmcl.plugin.api.PathCallback onResult) {
                if (onResult == null) throw new NullPointerException("onResult");
                manager.offerFilePicker(new com.pmcl.plugin.api.PluginFilePickerRequest(
                        java.util.UUID.randomUUID().toString(),
                        pluginId,
                        title != null ? title : "Select file",
                        save ? com.pmcl.plugin.api.FilePickerMode.SAVE_FILE
                                : com.pmcl.plugin.api.FilePickerMode.OPEN_FILE,
                        filters != null ? filters : "",
                        onResult));
            }

            @Override
            public void pickFolder(String title, com.pmcl.plugin.api.PathCallback onResult) {
                if (onResult == null) throw new NullPointerException("onResult");
                manager.offerFilePicker(new com.pmcl.plugin.api.PluginFilePickerRequest(
                        java.util.UUID.randomUUID().toString(),
                        pluginId,
                        title != null ? title : "Select folder",
                        com.pmcl.plugin.api.FilePickerMode.OPEN_FOLDER,
                        "",
                        onResult));
            }

            @Override
            public void showProgress(String id, String title, double progress) {
                String pid = (id != null && !id.isBlank()) ? id : "default";
                manager.offerProgress(new com.pmcl.plugin.api.PluginProgressUpdate(
                        pid,
                        pluginId,
                        title != null ? title : pluginId,
                        progress,
                        false));
            }

            @Override
            public void dismissProgress(String id) {
                String pid = (id != null && !id.isBlank()) ? id : "default";
                manager.offerProgress(new com.pmcl.plugin.api.PluginProgressUpdate(
                        pid, pluginId, "", 0, true));
            }

            @Override
            public void showInputDialog(String title, String message, String defaultValue,
                                        String confirmLabel, String cancelLabel,
                                        com.pmcl.plugin.api.PathCallback onResult) {
                if (onResult == null) throw new NullPointerException("onResult");
                manager.offerInputDialog(new com.pmcl.plugin.api.PluginInputDialogRequest(
                        java.util.UUID.randomUUID().toString(),
                        pluginId,
                        title != null ? title : pluginId,
                        message != null ? message : "",
                        defaultValue != null ? defaultValue : "",
                        confirmLabel != null ? confirmLabel : "OK",
                        cancelLabel != null ? cancelLabel : "Cancel",
                        onResult));
            }

            @Override
            public void setNavBadge(String target, String text) {
                if (target == null || target.isBlank()) return;
                manager.setNavBadge(pluginId, target, text != null ? text : "");
            }

            @Override
            public void clearNavBadge(String target) {
                if (target == null || target.isBlank()) return;
                manager.clearNavBadge(pluginId, target);
            }

            @Override
            public void hideBuiltinNav(String route) {
                if (route == null || route.isBlank()) return;
                String r = route.trim().toLowerCase(java.util.Locale.ROOT);
                if (r.equals("settings") || r.equals("plugins") || r.equals("accounts")) {
                    throw new SecurityException("Hiding security-critical nav route is not allowed: " + r);
                }
                manager.hideBuiltinNav(pluginId, route);
            }

            @Override
            public void showBuiltinNav(String route) {
                if (route == null || route.isBlank()) return;
                manager.showBuiltinNav(pluginId, route);
            }
        };
    }

    static com.pmcl.plugin.api.NewsApi news(LauncherCore core, PermissionGate gate) {
        return limit -> {
            gate.require("NETWORK");
            try {
                int lim = Math.max(1, Math.min(limit, 50));
                List<com.pmcl.core.news.NewsItem> items = core.news().fetch(lim).join();
                List<com.pmcl.plugin.api.NewsSummary> out = new ArrayList<>();
                if (items != null) {
                    for (com.pmcl.core.news.NewsItem n : items) {
                        out.add(new com.pmcl.plugin.api.NewsSummary(
                                n.getTitle() != null ? n.getTitle() : "",
                                n.getLink() != null ? n.getLink() : "",
                                n.getDescription() != null ? n.getDescription() : "",
                                n.getPubDate() != null ? n.getPubDate() : "",
                                n.getCategory() != null ? n.getCategory() : "",
                                n.getImageUrl() != null ? n.getImageUrl() : ""));
                    }
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException("fetchNews failed: " + e.getMessage(), e);
            }
        };
    }

    static com.pmcl.plugin.api.I18nApi i18n(LauncherCore core, PluginManager manager, String pluginId) {
        return new com.pmcl.plugin.api.I18nApi() {
            @Override
            public String currentLanguage() {
                return core.getPreferences().getLanguage();
            }

            @Override
            public String t(String key, Object... args) {
                return com.pmcl.core.i18n.I18n.t(key, args);
            }

            @Override
            public void registerStrings(String language, java.util.Map<String, String> strings) {
                if (language == null || language.isBlank() || strings == null || strings.isEmpty()) return;
                java.util.Map<String, String> filtered = new java.util.LinkedHashMap<>();
                String prefix = pluginId + ".";
                for (var e : strings.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) continue;
                    String key = e.getKey().trim();
                    if (!key.startsWith(prefix)) {
                        System.err.println("[Plugin:" + pluginId
                                + "] i18n key rejected (must start with '" + prefix + "'): " + key);
                        continue;
                    }
                    filtered.put(key, e.getValue() != null ? e.getValue() : "");
                }
                if (!filtered.isEmpty()) {
                    manager.registerPluginStrings(pluginId, language, filtered);
                }
            }

            @Override
            public void clearStrings(String language) {
                manager.clearPluginStrings(pluginId, language != null ? language : "");
            }
        };
    }

    static com.pmcl.plugin.api.ModpackApi modpacks(LauncherCore core, PermissionGate gate) {
        return new com.pmcl.plugin.api.ModpackApi() {
            @Override
            public List<com.pmcl.plugin.api.ModpackSummary> listInstalled() {
                List<com.pmcl.plugin.api.ModpackSummary> out = new ArrayList<>();
                for (var mp : core.modpacks().listInstalledModpacks()) {
                    out.add(new com.pmcl.plugin.api.ModpackSummary(
                            mp.name != null ? mp.name : "",
                            mp.gameVersion != null ? mp.gameVersion : "",
                            emptyToNull(mp.loader),
                            emptyToNull(mp.loaderVersion),
                            mp.instanceDir != null ? mp.instanceDir.toString() : "",
                            mp.modCount,
                            mp.source != null ? mp.source : ""));
                }
                return out;
            }

            @Override
            public String importModpack(Path file) {
                gate.require("MANAGE_MODPACKS");
                try {
                    core.modpacks().importModpack(file, null).join();
                    return file.getFileName().toString();
                } catch (Exception e) {
                    throw new RuntimeException("importModpack failed: " + e.getMessage(), e);
                }
            }
        };
    }

    static com.pmcl.plugin.api.GameContentApi gameContent(LauncherCore core, PermissionGate gate) {
        return new com.pmcl.plugin.api.GameContentApi() {
            @Override
            public List<com.pmcl.plugin.api.WorldSummary> listWorlds() {
                try {
                    List<com.pmcl.plugin.api.WorldSummary> out = new ArrayList<>();
                    for (var w : core.worlds().listWorlds()) {
                        out.add(new com.pmcl.plugin.api.WorldSummary(
                                w.getName(),
                                w.getDir().toString(),
                                w.getDisplayName() != null ? w.getDisplayName() : "",
                                w.getGameType(),
                                w.getDifficulty(),
                                w.isHardcore(),
                                w.getLastModified(),
                                w.getSource() != null ? w.getSource() : ""));
                    }
                    return out;
                } catch (Exception e) {
                    throw new RuntimeException("listWorlds failed: " + e.getMessage(), e);
                }
            }

            @Override
            public Path backupWorld(String worldName) {
                gate.require("MANAGE_GAME_CONTENT");
                try {
                    return core.worlds().backup(requireWorld(worldName));
                } catch (Exception e) {
                    throw new RuntimeException("backupWorld failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void importWorld(Path zipFile) {
                gate.require("MANAGE_GAME_CONTENT");
                try {
                    core.worlds().importWorld(zipFile);
                } catch (Exception e) {
                    throw new RuntimeException("importWorld failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void deleteWorld(String worldName) {
                gate.require("MANAGE_GAME_CONTENT");
                try {
                    core.worlds().delete(requireWorld(worldName));
                } catch (Exception e) {
                    throw new RuntimeException("deleteWorld failed: " + e.getMessage(), e);
                }
            }

            @Override
            public List<com.pmcl.plugin.api.PackSummary> listResourcePacks() {
                try {
                    List<com.pmcl.plugin.api.PackSummary> out = new ArrayList<>();
                    for (var p : core.resourcePacks().list()) {
                        out.add(new com.pmcl.plugin.api.PackSummary(
                                p.getName(),
                                p.getPath().toString(),
                                p.getDescription() != null ? p.getDescription() : "",
                                p.isDisabled(),
                                p.getSource() != null ? p.getSource() : "",
                                false));
                    }
                    return out;
                } catch (Exception e) {
                    throw new RuntimeException("listResourcePacks failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void enableResourcePack(String fileName) {
                gate.require("MANAGE_GAME_CONTENT");
                try {
                    core.resourcePacks().enable(fileName);
                } catch (Exception e) {
                    throw new RuntimeException("enableResourcePack failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void disableResourcePack(String fileName) {
                gate.require("MANAGE_GAME_CONTENT");
                try {
                    core.resourcePacks().disable(fileName);
                } catch (Exception e) {
                    throw new RuntimeException("disableResourcePack failed: " + e.getMessage(), e);
                }
            }

            @Override
            public List<com.pmcl.plugin.api.PackSummary> listShaderPacks() {
                try {
                    List<com.pmcl.plugin.api.PackSummary> out = new ArrayList<>();
                    for (var p : core.shaderPacks().list()) {
                        out.add(new com.pmcl.plugin.api.PackSummary(
                                p.getName(),
                                p.getPath().toString(),
                                "",
                                p.isDisabled(),
                                p.getSource() != null ? p.getSource() : "",
                                p.isActive()));
                    }
                    return out;
                } catch (Exception e) {
                    throw new RuntimeException("listShaderPacks failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void enableShaderPack(String fileName) {
                gate.require("MANAGE_GAME_CONTENT");
                try {
                    core.shaderPacks().enable(fileName);
                } catch (Exception e) {
                    throw new RuntimeException("enableShaderPack failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void disableShaderPack(String fileName) {
                gate.require("MANAGE_GAME_CONTENT");
                try {
                    core.shaderPacks().disable(fileName);
                } catch (Exception e) {
                    throw new RuntimeException("disableShaderPack failed: " + e.getMessage(), e);
                }
            }

            @Override
            public List<com.pmcl.plugin.api.PackSummary> listDatapacks(Path worldDir) {
                try {
                    List<com.pmcl.plugin.api.PackSummary> out = new ArrayList<>();
                    for (var p : core.datapacks().list(worldDir)) {
                        out.add(new com.pmcl.plugin.api.PackSummary(
                                p.getName(),
                                p.getPath().toString(),
                                p.getDescription() != null ? p.getDescription() : "",
                                p.isDisabled(),
                                "",
                                false));
                    }
                    return out;
                } catch (Exception e) {
                    throw new RuntimeException("listDatapacks failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void enableDatapack(Path worldDir, String fileName) {
                gate.require("MANAGE_GAME_CONTENT");
                try {
                    core.datapacks().enable(worldDir, fileName);
                } catch (Exception e) {
                    throw new RuntimeException("enableDatapack failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void disableDatapack(Path worldDir, String fileName) {
                gate.require("MANAGE_GAME_CONTENT");
                try {
                    core.datapacks().disable(worldDir, fileName);
                } catch (Exception e) {
                    throw new RuntimeException("disableDatapack failed: " + e.getMessage(), e);
                }
            }

            @Override
            public List<com.pmcl.plugin.api.ScreenshotSummary> listScreenshots() {
                try {
                    List<com.pmcl.plugin.api.ScreenshotSummary> out = new ArrayList<>();
                    for (var s : core.screenshots().list()) {
                        out.add(new com.pmcl.plugin.api.ScreenshotSummary(
                                s.getName(),
                                s.getPath().toString(),
                                s.getModified(),
                                s.getSource() != null ? s.getSource() : ""));
                    }
                    return out;
                } catch (Exception e) {
                    throw new RuntimeException("listScreenshots failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void deleteScreenshot(String fileName) {
                gate.require("MANAGE_GAME_CONTENT");
                try {
                    var shot = core.screenshots().list().stream()
                            .filter(s -> s.getName().equals(fileName))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Screenshot not found: " + fileName));
                    core.screenshots().delete(shot);
                } catch (Exception e) {
                    throw new RuntimeException("deleteScreenshot failed: " + e.getMessage(), e);
                }
            }

            private com.pmcl.core.gamecontent.WorldManager.WorldInfo requireWorld(String worldName)
                    throws IOException {
                if (worldName == null || worldName.isBlank()) {
                    throw new IllegalArgumentException("worldName is blank");
                }
                for (var w : core.worlds().listWorlds()) {
                    if (worldName.equals(w.getName())) return w;
                }
                throw new IllegalArgumentException("World not found: " + worldName);
            }
        };
    }

    static com.pmcl.plugin.api.ModMarketApi modMarket(LauncherCore core, PermissionGate gate) {
        return new com.pmcl.plugin.api.ModMarketApi() {
            @Override
            public List<com.pmcl.plugin.api.MarketProjectSummary> search(
                    String query, String gameVersion, String loader, int limit) {
                gate.require("NETWORK");
                try {
                    int lim = Math.max(1, Math.min(limit, 50));
                    List<com.pmcl.core.market.ModProject> projects = core.modMarket()
                            .search(query != null ? query : "", gameVersion, loader, lim)
                            .join();
                    List<com.pmcl.plugin.api.MarketProjectSummary> out = new ArrayList<>();
                    if (projects != null) {
                        for (var p : projects) {
                            out.add(new com.pmcl.plugin.api.MarketProjectSummary(
                                    nz(p.getSource()),
                                    nz(p.getId()),
                                    nz(p.getSlug()),
                                    nz(p.getName()),
                                    nz(p.getSummary()),
                                    nz(p.getAuthor()),
                                    p.getDownloadCount(),
                                    nz(p.getIconUrl()),
                                    nz(p.getWebsiteUrl())));
                        }
                    }
                    return out;
                } catch (Exception e) {
                    throw new RuntimeException("mod market search failed: " + e.getMessage(), e);
                }
            }

            @Override
            public List<com.pmcl.plugin.api.MarketFileSummary> listFiles(String source, String projectId) {
                gate.require("NETWORK");
                try {
                    var project = new com.pmcl.core.market.ModProject(
                            source, projectId, projectId, "", "", "", 0, "", "");
                    List<com.pmcl.core.market.ModFile> files = core.modMarket().listFiles(project).join();
                    List<com.pmcl.plugin.api.MarketFileSummary> out = new ArrayList<>();
                    if (files != null) {
                        for (var f : files) {
                            out.add(toMarketFile(f));
                        }
                    }
                    return out;
                } catch (Exception e) {
                    throw new RuntimeException("listFiles failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void install(String source, String projectId, String fileId,
                               String gameVersion, String versionOrInstanceId) {
                gate.require("NETWORK");
                gate.require("MANAGE_MODS");
                try {
                    var project = new com.pmcl.core.market.ModProject(
                            source, projectId, projectId, "", "", "", 0, "", "");
                    List<com.pmcl.core.market.ModFile> files = core.modMarket().listFiles(project).join();
                    com.pmcl.core.market.ModFile match = null;
                    if (files != null) {
                        for (var f : files) {
                            if (fileId != null && fileId.equals(f.getFileId())) {
                                match = f;
                                break;
                            }
                        }
                    }
                    if (match == null) {
                        throw new IllegalArgumentException("Market file not found: " + fileId);
                    }
                    core.modMarket().installMod(
                            match,
                            gameVersion,
                            versionOrInstanceId,
                            core.getPreferences(),
                            null).join();
                } catch (Exception e) {
                    throw new RuntimeException("mod install failed: " + e.getMessage(), e);
                }
            }

            private static com.pmcl.plugin.api.MarketFileSummary toMarketFile(
                    com.pmcl.core.market.ModFile f) {
                return new com.pmcl.plugin.api.MarketFileSummary(
                        nz(f.getSource()),
                        nz(f.getProjectId()),
                        nz(f.getFileId()),
                        nz(f.getFileName()),
                        f.getFileSize(),
                        nz(f.getDownloadUrl()),
                        f.getGameVersions() != null ? f.getGameVersions() : Collections.emptyList(),
                        f.getLoaders() != null ? f.getLoaders() : Collections.emptyList(),
                        nz(f.getReleaseType()));
            }
        };
    }

    static com.pmcl.plugin.api.StatsApi stats(LauncherCore core, PermissionGate gate) {
        return new com.pmcl.plugin.api.StatsApi() {
            @Override
            public com.pmcl.plugin.api.OverallStatsSummary overall(int recentDays) {
                gate.require("READ_STATS");
                int days = Math.max(1, Math.min(recentDays, 365));
                var o = core.playTimeTracker().getOverallStats(days);
                List<com.pmcl.plugin.api.VersionStatSummary> versions = new ArrayList<>();
                if (o.versions != null) {
                    for (var v : o.versions) {
                        versions.add(new com.pmcl.plugin.api.VersionStatSummary(
                                nz(v.version), v.totalDuration, v.sessionCount, v.lastPlayed));
                    }
                }
                return new com.pmcl.plugin.api.OverallStatsSummary(
                        o.totalDuration, o.totalSessions, versions);
            }

            @Override
            public List<com.pmcl.plugin.api.SessionSummary> sessions(int offset, int limit) {
                gate.require("READ_STATS");
                int off = Math.max(0, offset);
                int lim = Math.max(1, Math.min(limit, 200));
                List<com.pmcl.plugin.api.SessionSummary> out = new ArrayList<>();
                for (var s : core.playTimeTracker().getSessions(off, lim)) {
                    out.add(new com.pmcl.plugin.api.SessionSummary(
                            nz(s.version),
                            s.start,
                            s.end,
                            s.duration,
                            nz(s.instanceId),
                            nz(s.server),
                            nz(s.worldName)));
                }
                return out;
            }
        };
    }

    static com.pmcl.plugin.api.RoomsApi rooms(LauncherCore core, PermissionGate gate) {
        return new com.pmcl.plugin.api.RoomsApi() {
            @Override
            public com.pmcl.plugin.api.RoomStateSummary state() {
                var mp = requireMultiplayer(core);
                String invite = "";
                try {
                    invite = nz(mp.generateInvitation());
                } catch (Throwable ignored) {}
                return new com.pmcl.plugin.api.RoomStateSummary(
                        mp.getState() != null ? mp.getState().name() : "IDLE",
                        mp.getBackend() != null ? mp.getBackend().name() : "",
                        mp.isInRoom(),
                        mp.isBusy(),
                        nz(mp.getVirtualIp()),
                        nz(mp.getCurrentNetworkName()),
                        nz(mp.getCurrentRoomCode()),
                        invite,
                        nz(mp.getLastError()));
            }

            @Override
            public void createRoom() {
                gate.require("CONTROL_ROOMS");
                try {
                    requireMultiplayer(core).createRoom(null).join();
                } catch (Exception e) {
                    throw new RuntimeException("createRoom failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void joinRoom(String invitationCode) {
                gate.require("CONTROL_ROOMS");
                if (invitationCode == null || invitationCode.isBlank()) {
                    throw new IllegalArgumentException("invitationCode is blank");
                }
                try {
                    requireMultiplayer(core).joinRoom(invitationCode, null).join();
                } catch (Exception e) {
                    throw new RuntimeException("joinRoom failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void leaveRoom() {
                gate.require("CONTROL_ROOMS");
                requireMultiplayer(core).leaveRoom();
            }

            @Override
            public String invitation() {
                var mp = requireMultiplayer(core);
                if (!mp.isInRoom()) return null;
                String inv = mp.generateInvitation();
                return (inv == null || inv.isBlank()) ? null : inv;
            }

            @Override
            public String virtualIp() {
                String ip = requireMultiplayer(core).getVirtualIp();
                return (ip == null || ip.isBlank()) ? null : ip;
            }
        };
    }

    static com.pmcl.plugin.api.JavaRuntimesApi javaRuntimes(LauncherCore core, PermissionGate gate) {
        return new com.pmcl.plugin.api.JavaRuntimesApi() {
            @Override
            public List<String> listInstalled() {
                return new ArrayList<>(com.pmcl.core.launch.JavaRuntimeFinder.scanRuntimes(
                        core.getConfig().getRuntimesDir()));
            }

            @Override
            public List<com.pmcl.plugin.api.JavaRuntimeSummary> listAvailable(String type) {
                gate.require("NETWORK");
                try {
                    var rt = resolveRuntimeType(type);
                    List<com.pmcl.plugin.api.JavaRuntimeSummary> out = new ArrayList<>();
                    for (var e : core.javaDownloader().listRuntimes(rt).join()) {
                        out.add(new com.pmcl.plugin.api.JavaRuntimeSummary(
                                rt.name(),
                                nz(e.getName()),
                                nz(e.getVersion()),
                                e.getSize(),
                                nz(e.getSha1())));
                    }
                    return out;
                } catch (Exception e) {
                    throw new RuntimeException("listAvailable failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void install(String type, String version, java.util.function.DoubleConsumer onProgress) {
                gate.require("NETWORK");
                try {
                    var rt = resolveRuntimeType(type);
                    var entries = core.javaDownloader().listRuntimes(rt).join();
                    com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeEntry match = null;
                    if (entries != null) {
                        for (var e : entries) {
                            if (version != null && (version.equals(e.getVersion()) || version.equals(e.getName()))) {
                                match = e;
                                break;
                            }
                        }
                    }
                    if (match == null) {
                        throw new IllegalArgumentException("Runtime version not found: " + version);
                    }
                    final var entry = match;
                    core.javaDownloader().install(rt, entry, status -> {
                        if (onProgress != null) onProgress.accept(-1.0);
                    }).join();
                    if (onProgress != null) onProgress.accept(1.0);
                } catch (Exception e) {
                    throw new RuntimeException("java runtime install failed: " + e.getMessage(), e);
                }
            }

            @Override
            public com.pmcl.plugin.api.HostMetricsSummary hostMetrics() {
                var r = core.runtime();
                return new com.pmcl.plugin.api.HostMetricsSummary(
                        r.getAvailableMemoryMb(),
                        r.getTotalMemoryMb(),
                        r.getRecommendedMaxMemoryMb(),
                        r.getCpuLogicalCores(),
                        r.getCpuPhysicalCores(),
                        nz(r.getCpuName()),
                        nz(r.getOsName()),
                        nz(r.getPrimaryGpuName()));
            }
        };
    }

    static com.pmcl.plugin.api.DownloadQueueApi downloadQueue(LauncherCore core, PermissionGate gate) {
        return new com.pmcl.plugin.api.DownloadQueueApi() {
            @Override
            public com.pmcl.plugin.api.QueueSummaryDto summary() {
                var q = requireQueue(core);
                var s = q.getSummary();
                return new com.pmcl.plugin.api.QueueSummaryDto(
                        s.queued, s.running, s.paused, s.done, s.failed, s.cancelled,
                        s.totalBytes, s.completedBytes);
            }

            @Override
            public List<com.pmcl.plugin.api.QueueTaskSummary> listTasks() {
                List<com.pmcl.plugin.api.QueueTaskSummary> out = new ArrayList<>();
                for (var t : requireQueue(core).getTasks()) {
                    out.add(new com.pmcl.plugin.api.QueueTaskSummary(
                            nz(t.getId()),
                            nz(t.getName()),
                            t.getType() != null ? t.getType().name() : "",
                            t.getStatus() != null ? t.getStatus().name() : "",
                            t.getCompletedBytes(),
                            t.getTotalBytes(),
                            nz(t.getMessage()),
                            nz(t.getErrorMessage()),
                            t.progress()));
                }
                return out;
            }

            @Override
            public String enqueueVersionInstall(String versionId) {
                gate.require("MANAGE_VERSIONS");
                if (versionId == null || versionId.isBlank()) {
                    throw new IllegalArgumentException("versionId is blank");
                }
                return requireQueue(core).submitVersionInstall(versionId);
            }

            @Override
            public void pause(String taskId) {
                gate.require("MANAGE_VERSIONS");
                requireQueue(core).pause(taskId);
            }

            @Override
            public void resume(String taskId) {
                gate.require("MANAGE_VERSIONS");
                requireQueue(core).resume(taskId);
            }

            @Override
            public void cancel(String taskId) {
                gate.require("MANAGE_VERSIONS");
                requireQueue(core).cancel(taskId);
            }

            @Override
            public void clearFinished() {
                gate.require("MANAGE_VERSIONS");
                requireQueue(core).clearFinished();
            }
        };
    }

    static com.pmcl.plugin.api.NbtApi nbt(LauncherCore core, Path dataDir, PermissionGate gate) {
        return new com.pmcl.plugin.api.NbtApi() {
            @Override
            public String readSnbt(Path path) {
                Path safe = requireReadable(path);
                try {
                    var tag = com.pmcl.core.nbt.NbtReader.read(safe);
                    return tag != null ? tag.toSnbt() : "";
                } catch (Exception e) {
                    throw new RuntimeException("readSnbt failed: " + e.getMessage(), e);
                }
            }

            @Override
            public String getValue(Path path, String keyPath) {
                Path safe = requireReadable(path);
                try {
                    var tag = navigate(com.pmcl.core.nbt.NbtReader.read(safe), keyPath);
                    return tag != null ? tag.getValueString() : null;
                } catch (Exception e) {
                    throw new RuntimeException("getValue failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void setString(Path path, String keyPath, String value) {
                Path safe = requireWritable(path);
                try {
                    var meta = com.pmcl.core.nbt.NbtReader.readWithMeta(safe);
                    putScalar(meta.root, keyPath, new com.pmcl.core.nbt.NbtTag.StringTag(value != null ? value : ""));
                    com.pmcl.core.nbt.NbtWriter.write(meta.root, safe, meta.gzipped);
                } catch (Exception e) {
                    throw new RuntimeException("setString failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void setInt(Path path, String keyPath, int value) {
                Path safe = requireWritable(path);
                try {
                    var meta = com.pmcl.core.nbt.NbtReader.readWithMeta(safe);
                    putScalar(meta.root, keyPath, new com.pmcl.core.nbt.NbtTag.IntTag(value));
                    com.pmcl.core.nbt.NbtWriter.write(meta.root, safe, meta.gzipped);
                } catch (Exception e) {
                    throw new RuntimeException("setInt failed: " + e.getMessage(), e);
                }
            }

            @Override
            public boolean isNbtFile(Path path) {
                Path safe;
                try {
                    safe = requireReadable(path);
                } catch (SecurityException | IllegalArgumentException e) {
                    return false;
                }
                if (!Files.isRegularFile(safe)) return false;
                try {
                    com.pmcl.core.nbt.NbtReader.read(safe);
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            }

            private Path requireReadable(Path path) {
                if (path == null) throw new IllegalArgumentException("path is null");
                Path abs = path.toAbsolutePath().normalize();
                Path data = dataDir.toAbsolutePath().normalize();
                if (!abs.startsWith(data)) gate.require("FILESYSTEM");
                return PluginPathSandbox.requireAccessible(
                        path, dataDir, core.getConfig().getWorkDir(), true);
            }

            private Path requireWritable(Path path) {
                return requireReadable(path);
            }
        };
    }

    static com.pmcl.plugin.api.ServersApi servers(LauncherCore core, PermissionGate gate) {
        return new com.pmcl.plugin.api.ServersApi() {
            @Override
            public List<com.pmcl.plugin.api.ServerSummary> listFavorites() {
                List<com.pmcl.plugin.api.ServerSummary> out = new ArrayList<>();
                for (String[] row : core.getPreferences().getFavoriteServers()) {
                    if (row == null || row.length < 3) continue;
                    int port = 25565;
                    try { port = Integer.parseInt(row[2]); } catch (Exception ignored) {}
                    out.add(new com.pmcl.plugin.api.ServerSummary(
                            nz(row[0]), nz(row[1]), port));
                }
                return out;
            }

            @Override
            public void addFavorite(String name, String host, int port) {
                gate.require("MANAGE_SERVERS");
                core.getPreferences().addFavoriteServer(
                        name != null ? name : host,
                        host != null ? host : "",
                        port > 0 ? port : 25565);
            }

            @Override
            public void removeFavorite(int index) {
                gate.require("MANAGE_SERVERS");
                core.getPreferences().removeFavoriteServer(index);
            }

            @Override
            public void updateFavorite(int index, String name, String host, int port) {
                gate.require("MANAGE_SERVERS");
                core.getPreferences().updateFavoriteServer(
                        index,
                        name != null ? name : "",
                        host != null ? host : "",
                        port > 0 ? port : 25565);
            }

            @Override
            public com.pmcl.plugin.api.ServerPingResult ping(String host, int port) {
                gate.require("NETWORK");
                if (host == null || host.isBlank()) {
                    throw new IllegalArgumentException("host is blank");
                }
                String hostErr = com.pmcl.core.util.SsrfChecker.validateHostAllowingPrivateLan(host.trim());
                if (hostErr != null) {
                    throw new SecurityException("ping blocked: " + hostErr);
                }
                int p = port > 0 ? port : 25565;
                if (p < 1 || p > 65535) throw new IllegalArgumentException("invalid port: " + p);
                var st = com.pmcl.core.multiplayer.ServerPinger.pingFull(host.trim(), p);
                return new com.pmcl.plugin.api.ServerPingResult(
                        st.isOnline(),
                        st.getLatency(),
                        nz(st.getMotd()),
                        st.getOnlinePlayers(),
                        st.getMaxPlayers(),
                        nz(st.getVersionName()),
                        st.getProtocolVersion(),
                        nz(st.getError()));
            }

            @Override
            public String getDirectConnectHost() {
                return nz(core.getPreferences().getGameServerHost());
            }

            @Override
            public int getDirectConnectPort() {
                return core.getPreferences().getGameServerPort();
            }

            @Override
            public void setDirectConnect(String host, int port) {
                gate.require("MANAGE_SERVERS");
                core.getPreferences().setGameServerHost(host != null ? host : "");
                core.getPreferences().setGameServerPort(port > 0 ? port : 25565);
            }
        };
    }

    static com.pmcl.plugin.api.CrashLogsApi crashLogs(LauncherCore core, PermissionGate gate) {
        return new com.pmcl.plugin.api.CrashLogsApi() {
            @Override
            public List<com.pmcl.plugin.api.CrashReportSummary> listReports() {
                gate.require("READ_CRASH_LOGS");
                try {
                    List<com.pmcl.plugin.api.CrashReportSummary> out = new ArrayList<>();
                    for (var r : core.crashAnalyzer().scanReports(core.getConfig().getWorkDir())) {
                        out.add(toCrashSummary(r));
                    }
                    return out;
                } catch (Exception e) {
                    throw new RuntimeException("listReports failed: " + e.getMessage(), e);
                }
            }

            @Override
            public com.pmcl.plugin.api.CrashReportSummary analyzeText(String content) {
                gate.require("READ_CRASH_LOGS");
                var r = core.crashAnalyzer().analyze(content != null ? content : "", null);
                return toCrashSummary(r);
            }

            private static com.pmcl.plugin.api.CrashReportSummary toCrashSummary(
                    com.pmcl.core.launch.CrashAnalyzer.CrashReport r) {
                List<com.pmcl.plugin.api.CrashRecoverySummary> actions = new ArrayList<>();
                if (r.getRecoveryActions() != null) {
                    for (var a : r.getRecoveryActions()) {
                        actions.add(new com.pmcl.plugin.api.CrashRecoverySummary(
                                a.getType() != null ? a.getType().name() : "",
                                nz(a.getTitle()),
                                nz(a.getDescription())));
                    }
                }
                String content = r.getContent() != null ? r.getContent() : "";
                String preview = content.length() > 800 ? content.substring(0, 800) + "…" : content;
                return new com.pmcl.plugin.api.CrashReportSummary(
                        r.getFile() != null ? r.getFile().toString() : "",
                        r.getCauses() != null ? r.getCauses() : Collections.emptyList(),
                        r.getSuggestions() != null ? r.getSuggestions() : Collections.emptyList(),
                        actions,
                        preview);
            }
        };
    }

    static com.pmcl.plugin.api.MusicApi music(PluginManager manager, PermissionGate gate) {
        return new com.pmcl.plugin.api.MusicApi() {
            @Override
            public com.pmcl.plugin.api.MusicPlaybackSummary nowPlaying() {
                PluginManager.MusicBridge bridge = manager.getMusicBridge();
                if (bridge == null) {
                    return new com.pmcl.plugin.api.MusicPlaybackSummary(
                            "UNAVAILABLE", "", -1, 0, 0, 0);
                }
                return bridge.nowPlaying();
            }

            @Override
            public void pause() {
                gate.require("CONTROL_MUSIC");
                requireMusic(manager).pause();
            }

            @Override
            public void resume() {
                gate.require("CONTROL_MUSIC");
                requireMusic(manager).resume();
            }

            @Override
            public void stop() {
                gate.require("CONTROL_MUSIC");
                requireMusic(manager).stop();
            }

            @Override
            public void playNext() {
                gate.require("CONTROL_MUSIC");
                requireMusic(manager).playNext();
            }

            @Override
            public void playPrevious() {
                gate.require("CONTROL_MUSIC");
                requireMusic(manager).playPrevious();
            }

            @Override
            public void setVolume(int volume) {
                gate.require("CONTROL_MUSIC");
                requireMusic(manager).setVolume(volume);
            }
        };
    }

    static FilesystemApi filesystem(LauncherCore core, String pluginId, Path dataDir, PermissionGate gate) {
        return new FilesystemApi() {
            @Override
            public Path dataDir() { return dataDir; }

            @Override
            public Path workDir() {
                gate.require("FILESYSTEM");
                return core.getConfig().getWorkDir();
            }

            @Override
            public boolean exists(Path path) {
                Path p = requireReadable(path);
                return Files.exists(p);
            }

            @Override
            public boolean isDirectory(Path path) {
                Path p = requireReadable(path);
                return Files.isDirectory(p);
            }

            @Override
            public List<Path> list(Path path) {
                Path p = requireReadable(path);
                if (!Files.isDirectory(p)) return Collections.emptyList();
                try (var stream = Files.list(p)) {
                    return stream.collect(java.util.stream.Collectors.toList());
                } catch (IOException e) {
                    throw new RuntimeException("list failed: " + e.getMessage(), e);
                }
            }

            @Override
            public String readText(Path path) {
                Path p = requireReadable(path);
                try {
                    return Files.readString(p);
                } catch (IOException e) {
                    throw new RuntimeException("readText failed: " + e.getMessage(), e);
                }
            }

            @Override
            public byte[] readBytes(Path path) {
                Path p = requireReadable(path);
                try {
                    return Files.readAllBytes(p);
                } catch (IOException e) {
                    throw new RuntimeException("readBytes failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void writeText(Path path, String content) {
                Path p = requireWritable(path);
                try {
                    if (p.getParent() != null) Files.createDirectories(p.getParent());
                    Files.writeString(p, content != null ? content : "");
                } catch (IOException e) {
                    throw new RuntimeException("writeText failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void writeBytes(Path path, byte[] content) {
                Path p = requireWritable(path);
                try {
                    if (p.getParent() != null) Files.createDirectories(p.getParent());
                    Files.write(p, content != null ? content : new byte[0]);
                } catch (IOException e) {
                    throw new RuntimeException("writeBytes failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void createDirectories(Path path) {
                Path p = requireWritable(path);
                try {
                    Files.createDirectories(p);
                } catch (IOException e) {
                    throw new RuntimeException("createDirectories failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void delete(Path path) {
                Path p = requireWritable(path);
                try {
                    if (Files.isDirectory(p)) deleteRecursively(p);
                    else Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException("delete failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void copy(Path source, Path target) {
                Path src = requireReadable(source);
                Path dst = requireWritable(target);
                try {
                    if (dst.getParent() != null) Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException("copy failed: " + e.getMessage(), e);
                }
            }

            @Override
            public void move(Path source, Path target) {
                Path src = requireWritable(source);
                Path dst = requireWritable(target);
                try {
                    if (dst.getParent() != null) Files.createDirectories(dst.getParent());
                    Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException("move failed: " + e.getMessage(), e);
                }
            }

            @Override
            public Path resolveData(String relative) {
                if (relative == null) relative = "";
                Path resolved = dataDir.resolve(relative).normalize();
                Path base = dataDir.toAbsolutePath().normalize();
                if (!resolved.toAbsolutePath().normalize().startsWith(base)) {
                    throw new SecurityException("Path escapes plugin data dir");
                }
                return PluginPathSandbox.requireAccessible(
                        resolved, dataDir, core.getConfig().getWorkDir(), false);
            }

            private Path requireReadable(Path path) {
                if (path == null) throw new IllegalArgumentException("path is null");
                Path abs = path.toAbsolutePath().normalize();
                Path data = dataDir.toAbsolutePath().normalize();
                if (!abs.startsWith(data)) gate.require("FILESYSTEM");
                return PluginPathSandbox.requireAccessible(
                        path, dataDir, core.getConfig().getWorkDir(), true);
            }

            private Path requireWritable(Path path) {
                return requireReadable(path);
            }
        };
    }

    static SchedulerApi scheduler(PluginManager manager, String pluginId) {
        return new SchedulerApi() {
            @Override
            public String scheduleOnce(long delayMs, Runnable task) {
                if (task == null) throw new NullPointerException("task");
                return manager.schedulePluginTask(pluginId, Math.max(0, delayMs), -1, task);
            }

            @Override
            public String scheduleRepeating(long delayMs, long periodMs, Runnable task) {
                if (task == null) throw new NullPointerException("task");
                if (periodMs <= 0) throw new IllegalArgumentException("periodMs must be > 0");
                return manager.schedulePluginTask(pluginId, Math.max(0, delayMs), periodMs, task);
            }

            @Override
            public void cancel(String taskId) {
                manager.cancelPluginTask(pluginId, taskId);
            }

            @Override
            public void cancelAll() {
                manager.cancelAllPluginTasks(pluginId);
            }
        };
    }

    static PluginsApi plugins(PluginManager manager, PermissionGate gate) {
        return new PluginsApi() {
            @Override
            public List<PluginInfoSummary> listPlugins() {
                List<PluginInfoSummary> out = new ArrayList<>();
                for (var entry : manager.getLoadedPlugins()) {
                    out.add(toPluginSummary(manager, entry));
                }
                return out;
            }

            @Override
            public PluginInfoSummary getPlugin(String pluginId) {
                var entry = manager.getPlugin(pluginId);
                return entry != null ? toPluginSummary(manager, entry) : null;
            }

            @Override
            public void enablePlugin(String pluginId) {
                gate.require("MANAGE_PLUGINS");
                manager.enablePlugin(pluginId);
            }

            @Override
            public void disablePlugin(String pluginId) {
                gate.require("MANAGE_PLUGINS");
                manager.disablePlugin(pluginId);
            }

            @Override
            public void unloadPlugin(String pluginId) {
                gate.require("MANAGE_PLUGINS");
                manager.unloadPlugin(pluginId);
            }

            @Override
            public boolean isEnabled(String pluginId) {
                return manager.isEnabled(pluginId);
            }
        };
    }

    static HttpApi http(LauncherCore core, PluginManager manager, PermissionGate gate) {
        return (method, url, headers, body, timeoutMs) -> {
            gate.require("NETWORK");
            if (url == null || url.isBlank()) throw new IllegalArgumentException("url is blank");
            String safeUrl = manager.applyUrlRewrites(url);
            String err = com.pmcl.core.util.SsrfChecker.validate(safeUrl);
            if (err != null) throw new RuntimeException("SSRF blocked: " + err);
            String m = method != null ? method.trim().toUpperCase(java.util.Locale.ROOT) : "GET";
            long timeout = Math.max(1_000L, Math.min(timeoutMs > 0 ? timeoutMs : 30_000L, 120_000L));
            try {
                okhttp3.OkHttpClient client = core.downloads().httpClient().newBuilder()
                        .callTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .addNetworkInterceptor(chain -> {
                            String hop = chain.request().url().toString();
                            String hopErr = com.pmcl.core.util.SsrfChecker.validate(hop);
                            if (hopErr != null) throw new IOException("SSRF redirect blocked: " + hopErr);
                            return chain.proceed(chain.request());
                        })
                        .build();
                okhttp3.Request.Builder b = new okhttp3.Request.Builder().url(safeUrl);
                if (headers != null) {
                    for (var e : headers.entrySet()) {
                        if (e.getKey() == null || e.getKey().isBlank()) continue;
                        String k = e.getKey().trim();
                        if (k.equalsIgnoreCase("Host") || k.equalsIgnoreCase("Content-Length")) continue;
                        b.header(k, e.getValue() != null ? e.getValue() : "");
                    }
                }
                okhttp3.RequestBody reqBody = null;
                if (!"GET".equals(m) && !"HEAD".equals(m)) {
                    reqBody = okhttp3.RequestBody.create(
                            body != null ? body : "",
                            okhttp3.MediaType.parse("application/json; charset=utf-8"));
                }
                b.method(m, reqBody);
                try (okhttp3.Response resp = client.newCall(b.build()).execute()) {
                    String respBody = "";
                    if (resp.body() != null) {
                        respBody = resp.body().string();
                        if (respBody.length() > 2_000_000) {
                            respBody = respBody.substring(0, 2_000_000) + "…";
                        }
                    }
                    java.util.Map<String, String> hdrs = new java.util.LinkedHashMap<>();
                    for (String name : resp.headers().names()) {
                        hdrs.put(name, resp.header(name) != null ? resp.header(name) : "");
                    }
                    return new HttpResponseSummary(
                            resp.code(),
                            hdrs,
                            respBody,
                            resp.request().url().toString());
                }
            } catch (Exception e) {
                throw new RuntimeException("HTTP " + m + " failed: " + e.getMessage(), e);
            }
        };
    }

    // ---- helpers ----

    @FunctionalInterface
    interface PermissionGate {
        void require(String permission);
    }

    private static PluginInfoSummary toPluginSummary(PluginManager manager, PluginManager.PluginEntry entry) {
        var info = entry.getInfo();
        return new PluginInfoSummary(
                nz(info.getId()),
                nz(info.getName()),
                nz(info.getVersion()),
                nz(info.getAuthor()),
                nz(info.getDescription()),
                manager.isEnabled(info.getId()),
                entry.getState() != null ? entry.getState().name() : "UNKNOWN",
                info.getPermissions() != null ? new ArrayList<>(info.getPermissions()) : Collections.emptyList());
    }

    private static PluginManager.MusicBridge requireMusic(PluginManager manager) {
        PluginManager.MusicBridge bridge = manager.getMusicBridge();
        if (bridge == null) throw new IllegalStateException("Host music bridge not registered");
        return bridge;
    }

    private static com.pmcl.core.multiplayer.MultiplayerManager requireMultiplayer(LauncherCore core) {
        var mp = core.multiplayer();
        if (mp == null) throw new IllegalStateException("Multiplayer is not available");
        return mp;
    }

    private static com.pmcl.core.download.DownloadQueueManager requireQueue(LauncherCore core) {
        var q = core.downloadQueue();
        if (q == null) throw new IllegalStateException("Download queue is not available");
        return q;
    }

    private static com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType resolveRuntimeType(String type) {
        if (type == null || type.isBlank()) {
            return com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.JAVA_17;
        }
        String t = type.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.contains("8") || t.contains("alpha") || t.equals("legacy") || t.contains("jre-legacy")) {
            return com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.JAVA_8;
        }
        if (t.contains("21") || t.contains("delta")) {
            return com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.JAVA_21;
        }
        if (t.contains("17") || t.contains("gamma") || t.contains("beta")) {
            return com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.JAVA_17;
        }
        try {
            return com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.valueOf(type.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown Java runtime type: " + type);
        }
    }

    private static com.pmcl.core.nbt.NbtTag navigate(com.pmcl.core.nbt.NbtTag root, String keyPath) {
        if (root == null || keyPath == null || keyPath.isBlank()) return root;
        com.pmcl.core.nbt.NbtTag cur = unwrapRoot(root);
        for (String part : keyPath.split("/")) {
            if (part.isEmpty()) continue;
            if (!(cur instanceof com.pmcl.core.nbt.NbtTag.CompoundTag)) return null;
            cur = ((com.pmcl.core.nbt.NbtTag.CompoundTag) cur).get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    private static void putScalar(com.pmcl.core.nbt.NbtTag root, String keyPath, com.pmcl.core.nbt.NbtTag value) {
        if (keyPath == null || keyPath.isBlank()) {
            throw new IllegalArgumentException("keyPath is blank");
        }
        String[] parts = keyPath.split("/");
        List<String> keys = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) keys.add(p);
        }
        if (keys.isEmpty()) throw new IllegalArgumentException("keyPath is blank");
        com.pmcl.core.nbt.NbtTag.CompoundTag cur = asCompound(unwrapRoot(root));
        for (int i = 0; i < keys.size() - 1; i++) {
            String k = keys.get(i);
            com.pmcl.core.nbt.NbtTag child = cur.get(k);
            if (!(child instanceof com.pmcl.core.nbt.NbtTag.CompoundTag)) {
                child = new com.pmcl.core.nbt.NbtTag.CompoundTag();
                cur.put(k, child);
            }
            cur = (com.pmcl.core.nbt.NbtTag.CompoundTag) child;
        }
        cur.put(keys.get(keys.size() - 1), value);
    }

    private static com.pmcl.core.nbt.NbtTag unwrapRoot(com.pmcl.core.nbt.NbtTag root) {
        // Some level.dat files wrap payload in an unnamed outer compound with a single "" child.
        if (root instanceof com.pmcl.core.nbt.NbtTag.CompoundTag) {
            var c = (com.pmcl.core.nbt.NbtTag.CompoundTag) root;
            if (c.size() == 1 && c.contains("")) {
                var inner = c.get("");
                if (inner != null) return inner;
            }
        }
        return root;
    }

    private static com.pmcl.core.nbt.NbtTag.CompoundTag asCompound(com.pmcl.core.nbt.NbtTag tag) {
        if (tag instanceof com.pmcl.core.nbt.NbtTag.CompoundTag) {
            return (com.pmcl.core.nbt.NbtTag.CompoundTag) tag;
        }
        throw new IllegalArgumentException("NBT root is not a compound");
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    private static InstanceSummary toSummary(InstanceInfo info) {
        return new InstanceSummary(
                info.getInstanceId(),
                info.getName(),
                info.getBaseVersionId() != null ? info.getBaseVersionId() : "",
                emptyToNull(info.getLoader()),
                emptyToNull(info.getLoaderVersion()),
                info.getType() != null ? info.getType().name() : "CUSTOM",
                emptyToNull(info.getDescription()));
    }

    private static List<AccountSummary> loadSummaries(LauncherCore core) {
        Path file = accountsFile(core);
        try {
            AccountStore store = core.auth().loadStore(file);
            String selected = store.getSelectedUuid();
            List<AccountSummary> out = new ArrayList<>();
            for (Account a : store.getAccounts()) {
                out.add(new AccountSummary(
                        a.getUuid(),
                        a.getUsername(),
                        a.getType() != null ? a.getType().name() : "UNKNOWN",
                        selected != null && selected.equals(a.getUuid())));
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static Path accountsFile(LauncherCore core) {
        return core.getConfig().getWorkDir().resolve("accounts.json");
    }

    /** Strip JVM args that enable code loading / shell hooks when set via SettingsApi. */
    private static String sanitizeHostJvmArgs(String args) {
        if (args == null || args.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String raw : args.trim().split("\\s+")) {
            if (raw.isBlank()) continue;
            if (!PluginManager.isSafePluginJvmArg(raw)) {
                System.err.println("[PluginApi] Rejected host JVM arg from plugin: " + raw);
                continue;
            }
            if (out.length() > 0) out.append(' ');
            out.append(raw.trim());
        }
        return out.toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
