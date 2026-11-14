package com.pmcl.core.plugin;

import com.pmcl.core.LauncherCore;
import com.pmcl.core.backup.TimeMachine;
import com.pmcl.core.gamecontent.OfflineSkinLibrary;
import com.pmcl.core.gamecontent.RecordingManager;
import com.pmcl.core.install.IntegrityChecker;
import com.pmcl.core.kernel.KernelManager;
import com.pmcl.core.migration.MigrationManager;
import com.pmcl.core.modloader.ModLoader;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.stats.PlayTimeTracker;
import com.pmcl.core.web.WikiBrowser;
import com.pmcl.plugin.api.BackupChangeSummary;
import com.pmcl.plugin.api.BackupSettingsSummary;
import com.pmcl.plugin.api.BackupSummary;
import com.pmcl.plugin.api.BackupsApi;
import com.pmcl.plugin.api.IntegrityApi;
import com.pmcl.plugin.api.IntegritySummary;
import com.pmcl.plugin.api.KernelSummary;
import com.pmcl.plugin.api.KernelsApi;
import com.pmcl.plugin.api.LaunchPresetSummary;
import com.pmcl.plugin.api.LoadersApi;
import com.pmcl.plugin.api.MigrationApi;
import com.pmcl.plugin.api.MigrationSourceSummary;
import com.pmcl.plugin.api.PinsApi;
import com.pmcl.plugin.api.PlayApi;
import com.pmcl.plugin.api.PlayRecords;
import com.pmcl.plugin.api.PlaySlice;
import com.pmcl.plugin.api.PresetsApi;
import com.pmcl.plugin.api.RecordingSummary;
import com.pmcl.plugin.api.RecordingsApi;
import com.pmcl.plugin.api.SkinSummary;
import com.pmcl.plugin.api.SkinsApi;
import com.pmcl.plugin.api.VersionJavaApi;
import com.pmcl.plugin.api.WeekdayPlay;
import com.pmcl.plugin.api.WikiApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 给 Python 和助手用的启动器操作。令牌和 JVM 参数不放进返回值。 */
final class ToolboxFacades {

    private static final int SAMPLE = 30;
    private static final AtomicBoolean LOADER_INSTALL = new AtomicBoolean();

    private ToolboxFacades() {}

    static PinsApi pins(LauncherCore core, PluginApiFacades.PermissionGate gate) {
        return new PinsApi() {
            @Override public List<String> pinned() {
                return core.getPreferences().getPinnedVersions();
            }

            @Override public List<String> recent() {
                return core.getPreferences().getRecentVersions();
            }

            @Override public void pin(String versionId) {
                gate.require("WRITE_SETTINGS");
                requireText(versionId, "versionId");
                core.getPreferences().pinVersion(versionId.trim());
            }

            @Override public void unpin(String versionId) {
                gate.require("WRITE_SETTINGS");
                requireText(versionId, "versionId");
                core.getPreferences().unpinVersion(versionId.trim());
            }
        };
    }

    static IntegrityApi integrity(LauncherCore core) {
        return versionId -> {
            requireText(versionId, "versionId");
            try {
                IntegrityChecker.Result result = core.integrity().check(versionId.trim());
                return new IntegritySummary(
                        versionId.trim(),
                        result.isOk(),
                        result.getMissing().size(),
                        result.getHashMismatch().size(),
                        result.getOk().size(),
                        result.getUnverifiable().size(),
                        head(result.getMissing()),
                        head(result.getHashMismatch()),
                        "");
            } catch (IOException e) {
                return new IntegritySummary(versionId.trim(), false, 0, 0, 0, 0,
                        List.of(), List.of(), message(e));
            }
        };
    }

    static BackupsApi backups(LauncherCore core, PluginApiFacades.PermissionGate gate) {
        return new BackupsApi() {
            @Override public List<BackupSummary> listSnapshots() {
                List<BackupSummary> out = new ArrayList<>();
                for (TimeMachine.Snapshot snap : core.timeMachine().listSnapshots()) {
                    out.add(new BackupSummary(
                            snap.id(), snap.createdAt(), clip(snap.label(), 80), snap.files(),
                            snap.storedBytes(), snap.pinned(), clip(snap.note(), 240)));
                    if (out.size() >= 40) break;
                }
                return out;
            }

            @Override public BackupSettingsSummary settings() {
                TimeMachine.Settings settings = core.timeMachine().loadSettings();
                return new BackupSettingsSummary(
                        settings.schedule, settings.keep, settings.worlds, settings.mods,
                        settings.configs, settings.resourcepacks, settings.shaders,
                        settings.screenshots, settings.skins, settings.launcher);
            }

            @Override public long storedBytes() {
                return core.timeMachine().storedSize();
            }

            @Override public List<BackupChangeSummary> changes(String snapshotId) {
                requireText(snapshotId, "snapshotId");
                try {
                    List<BackupChangeSummary> out = new ArrayList<>();
                    for (TimeMachine.Entry entry : core.timeMachine().changes(snapshotId.trim())) {
                        out.add(new BackupChangeSummary(
                                entry.scope(), clip(entry.path(), 180), entry.size(), entry.change()));
                        if (out.size() >= 40) break;
                    }
                    return out;
                } catch (IOException e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }

            @Override public void setNote(String snapshotId, String note) {
                gate.require("WRITE_SETTINGS");
                requireText(snapshotId, "snapshotId");
                try {
                    core.timeMachine().setNote(snapshotId.trim(), clip(note == null ? "" : note, 240));
                } catch (IOException e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }

            @Override public void setPinned(String snapshotId, boolean pinned) {
                gate.require("WRITE_SETTINGS");
                requireText(snapshotId, "snapshotId");
                try {
                    core.timeMachine().setPinned(snapshotId.trim(), pinned);
                } catch (IOException e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }

            @Override public void deleteSnapshot(String snapshotId) {
                gate.require("MANAGE_GAME_CONTENT");
                requireText(snapshotId, "snapshotId");
                try {
                    core.timeMachine().deleteSnapshot(snapshotId.trim());
                } catch (IOException e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }

            @Override public int restore(String snapshotId) {
                gate.require("MANAGE_GAME_CONTENT");
                requireText(snapshotId, "snapshotId");
                try {
                    return core.timeMachine().restore(snapshotId.trim(), null, false, null);
                } catch (IOException e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }
        };
    }

    static MigrationApi migration(LauncherCore core) {
        return () -> {
            List<MigrationSourceSummary> out = new ArrayList<>();
            for (MigrationManager.Source source : core.migration().detectSources()) {
                String root = source.getGameRoot() == null ? "" : source.getGameRoot().toString();
                out.add(new MigrationSourceSummary(source.getName(), root, source.hasVersions()));
            }
            return out;
        };
    }

    static KernelsApi kernels(LauncherCore core, PluginApiFacades.PermissionGate gate) {
        return new KernelsApi() {
            @Override public List<KernelSummary> list() {
                List<KernelSummary> out = new ArrayList<>();
                for (KernelManager.Installed installed : core.kernels().list()) {
                    var manifest = installed.getManifest();
                    out.add(new KernelSummary(
                            manifest.getId(), manifest.getName(), manifest.getVersion(),
                            manifest.getRuntime(), installed.isEnabled(), installed.getHealth(),
                            clip(installed.getDetail(), 200)));
                }
                return out;
            }

            @Override public void setEnabled(String id, boolean enabled) {
                gate.require("MANAGE_PLUGINS");
                requireText(id, "id");
                try {
                    core.kernels().setEnabled(id.trim(), enabled);
                } catch (Exception e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }

            @Override public void remove(String id) {
                gate.require("MANAGE_PLUGINS");
                requireText(id, "id");
                try {
                    core.kernels().remove(id.trim());
                } catch (Exception e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }
        };
    }

    static SkinsApi skins(LauncherCore core, PluginApiFacades.PermissionGate gate) {
        return new SkinsApi() {
            @Override public List<SkinSummary> list() {
                try {
                    List<SkinSummary> out = new ArrayList<>();
                    for (OfflineSkinLibrary.Skin skin : core.offlineSkins().list()) {
                        out.add(skinOf(skin));
                    }
                    return out;
                } catch (IOException e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }

            @Override public SkinSummary importPng(String path, String name) {
                gate.require("MANAGE_GAME_CONTENT");
                Path file = underWork(core, path);
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                    throw new SecurityException("skin must be a png file under the launcher directory");
                }
                try {
                    return skinOf(core.offlineSkins().importPng(file, name));
                } catch (IOException e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }

            @Override public void delete(String id) {
                gate.require("MANAGE_GAME_CONTENT");
                requireText(id, "id");
                try {
                    core.offlineSkins().delete(id.trim());
                } catch (IOException e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }
        };
    }

    static RecordingsApi recordings(LauncherCore core, PluginApiFacades.PermissionGate gate) {
        return new RecordingsApi() {
            @Override public List<RecordingSummary> list() {
                try {
                    List<RecordingSummary> out = new ArrayList<>();
                    for (RecordingManager.Recording recording : core.recordings().list()) {
                        out.add(new RecordingSummary(
                                recording.getName(),
                                recording.getPath().toString(),
                                recording.getSize(),
                                recording.getModified(),
                                recording.getSource()));
                    }
                    return out;
                } catch (IOException e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }

            @Override public void delete(String name) {
                gate.require("MANAGE_GAME_CONTENT");
                requireText(name, "name");
                try {
                    Path root = core.recordings().getRecordingsDir().toAbsolutePath().normalize();
                    for (RecordingManager.Recording recording : core.recordings().list()) {
                        if (!recording.getName().equals(name.trim())) continue;
                        Path path = recording.getPath().toAbsolutePath().normalize();
                        if (!path.startsWith(root)) {
                            throw new SecurityException("recording is outside the recordings directory");
                        }
                        core.recordings().delete(recording);
                        return;
                    }
                } catch (IOException e) {
                    throw new IllegalArgumentException(message(e), e);
                }
                throw new IllegalArgumentException("没有这个录屏");
            }
        };
    }

    static PlayApi play(LauncherCore core, PluginApiFacades.PermissionGate gate) {
        return new PlayApi() {
            @Override public List<WeekdayPlay> weekdays(int recentDays) {
                gate.require("READ_STATS");
                List<WeekdayPlay> out = new ArrayList<>();
                for (PlayTimeTracker.WeekdayStat stat : core.playTimeTracker().getWeekdayDistribution(days(recentDays))) {
                    out.add(new WeekdayPlay(stat.dayOfWeek, stat.dayName, stat.totalDuration, stat.sessionCount));
                }
                return out;
            }

            @Override public PlayRecords records() {
                gate.require("READ_STATS");
                PlayTimeTracker.RecordsStat stat = core.playTimeTracker().getRecords();
                long longest = stat.longestSession == null ? 0 : stat.longestSession.duration;
                return new PlayRecords(
                        longest, stat.longestStreakDays, stat.currentStreakDays,
                        stat.firstPlayDate == null ? "" : stat.firstPlayDate,
                        stat.mostPlayedHour == null ? "" : stat.mostPlayedHour,
                        stat.totalDays);
            }

            @Override public List<PlaySlice> versions(int recentDays) {
                gate.require("READ_STATS");
                List<PlaySlice> out = new ArrayList<>();
                PlayTimeTracker.OverallStat overall = core.playTimeTracker().getOverallStats(days(recentDays));
                if (overall.versions != null) {
                    for (PlayTimeTracker.VersionStat stat : overall.versions) {
                        out.add(new PlaySlice(stat.version, stat.version, stat.totalDuration, stat.sessionCount));
                        if (out.size() >= 40) break;
                    }
                }
                return out;
            }

            @Override public List<PlaySlice> worlds() {
                gate.require("READ_STATS");
                return slices(core.playTimeTracker().getWorldBreakdown());
            }

            @Override public List<PlaySlice> servers() {
                gate.require("READ_STATS");
                return slices(core.playTimeTracker().getServerBreakdown());
            }

            @Override public List<PlaySlice> instances() {
                gate.require("READ_STATS");
                return slices(core.playTimeTracker().getInstanceBreakdown());
            }

            @Override public List<PlaySlice> mods(int top) {
                gate.require("READ_STATS");
                int limit = top <= 0 ? 10 : Math.min(top, 40);
                return slices(core.playTimeTracker().getModBreakdown(limit));
            }
        };
    }

    static LoadersApi loaders(LauncherCore core, PluginApiFacades.PermissionGate gate) {
        return new LoadersApi() {
            @Override public List<String> names() {
                List<String> out = new ArrayList<>();
                for (ModLoader loader : ModLoader.values()) {
                    if (loader.isInstallable()) out.add(loader.name());
                }
                return out;
            }

            @Override public String install(String loader, String gameVersion, String loaderVersion) {
                gate.require("MANAGE_VERSIONS");
                gate.require("NETWORK");
                ModLoader parsed = parseLoader(loader);
                requireText(gameVersion, "gameVersion");
                requireText(loaderVersion, "loaderVersion");
                if (!LOADER_INSTALL.compareAndSet(false, true)) return "busy";
                Thread thread = new Thread(() -> {
                    try {
                        core.modLoaders().get(parsed)
                                .install(gameVersion.trim(), loaderVersion.trim(), progress -> {})
                                .join();
                    } catch (Exception e) {
                        System.err.println("[pmcl] loader install failed: " + message(e));
                    } finally {
                        LOADER_INSTALL.set(false);
                    }
                }, "pmcl-loader-install");
                thread.setDaemon(true);
                thread.start();
                return "started";
            }
        };
    }

    static WikiApi wiki() {
        return new WikiApi() {
            @Override public String search(String query) {
                requireText(query, "query");
                return WikiBrowser.searchUrl(query.trim());
            }

            @Override public String minecraft(String query) {
                requireText(query, "query");
                return WikiBrowser.minecraftWikiSearchUrl(query.trim());
            }

            @Override public String modrinth(String slug) {
                requireText(slug, "slug");
                return WikiBrowser.modrinthProjectUrl(slug.trim());
            }

            @Override public String curseForge(int projectId) {
                if (projectId <= 0) throw new IllegalArgumentException("projectId");
                return WikiBrowser.curseForgeProjectUrl(projectId);
            }
        };
    }

    static PresetsApi presets(LauncherCore core, PluginApiFacades.PermissionGate gate) {
        return new PresetsApi() {
            @Override public List<LaunchPresetSummary> list() {
                List<LaunchPresetSummary> out = new ArrayList<>();
                for (Preferences.LaunchPreset preset : core.getPreferences().getLaunchPresets()) {
                    out.add(new LaunchPresetSummary(
                            preset.name, preset.minMemoryMb, preset.maxMemoryMb,
                            preset.gameWindowWidth, preset.gameWindowHeight,
                            preset.gameFullscreen,
                            preset.gameRenderer == null ? "" : preset.gameRenderer));
                }
                return out;
            }

            @Override public void apply(String name) {
                gate.require("WRITE_SETTINGS");
                requireText(name, "name");
                if (!hasPreset(core, name.trim())) throw new IllegalArgumentException("没有这个预设");
                core.getPreferences().applyLaunchPreset(name.trim());
            }

            @Override public void delete(String name) {
                gate.require("WRITE_SETTINGS");
                requireText(name, "name");
                if (!hasPreset(core, name.trim())) throw new IllegalArgumentException("没有这个预设");
                core.getPreferences().deleteLaunchPreset(name.trim());
            }
        };
    }

    static VersionJavaApi versionJava(LauncherCore core, PluginApiFacades.PermissionGate gate) {
        return new VersionJavaApi() {
            @Override public String get(String versionId) {
                requireText(versionId, "versionId");
                return core.getPreferences().getVersionJavaPath(versionId.trim());
            }

            @Override public void set(String versionId, String javaPath) {
                gate.require("WRITE_SETTINGS");
                requireText(versionId, "versionId");
                String path = javaPath == null ? "" : javaPath.trim();
                if (!path.isEmpty()) managedJava(core, path);
                core.getPreferences().setVersionJavaPath(versionId.trim(), path);
            }

            @Override public List<String> customVersions() {
                return new ArrayList<>(core.getPreferences().getVersionsWithCustomJava());
            }
        };
    }

    private static SkinSummary skinOf(OfflineSkinLibrary.Skin skin) {
        return new SkinSummary(skin.id(), skin.name(), skin.player(), skin.model(), skin.addedAt());
    }

    private static List<PlaySlice> slices(List<PlayTimeTracker.BreakdownStat> stats) {
        List<PlaySlice> out = new ArrayList<>();
        if (stats == null) return out;
        for (PlayTimeTracker.BreakdownStat stat : stats) {
            out.add(new PlaySlice(stat.key, stat.displayName, stat.totalDuration, stat.sessionCount));
            if (out.size() >= 40) break;
        }
        return out;
    }

    private static boolean hasPreset(LauncherCore core, String name) {
        for (Preferences.LaunchPreset preset : core.getPreferences().getLaunchPresets()) {
            if (name.equals(preset.name)) return true;
        }
        return false;
    }

    private static ModLoader parseLoader(String raw) {
        requireText(raw, "loader");
        String text = raw.trim();
        for (ModLoader loader : ModLoader.values()) {
            if (loader.name().equalsIgnoreCase(text) || loader.getDisplayName().equalsIgnoreCase(text)) {
                return loader;
            }
        }
        throw new IllegalArgumentException("没有这个加载器: " + text);
    }

    private static void managedJava(LauncherCore core, String path) {
        Path javaPath = Path.of(path).toAbsolutePath().normalize();
        Path work = core.getConfig().getWorkDir().toAbsolutePath().normalize();
        boolean managed = javaPath.startsWith(work.resolve("java")) || javaPath.startsWith(work.resolve("runtimes"));
        if (!managed) {
            throw new SecurityException("Java 只能放在启动器的 java 或 runtimes 目录里");
        }
        if (Files.exists(javaPath) && (Files.isSymbolicLink(javaPath) || !Files.isExecutable(javaPath))) {
            throw new SecurityException("Java 路径是符号链接，或者不能执行");
        }
    }

    private static Path underWork(LauncherCore core, String path) {
        requireText(path, "path");
        Path file = Path.of(path.trim()).toAbsolutePath().normalize();
        Path work = core.getConfig().getWorkDir().toAbsolutePath().normalize();
        if (!file.startsWith(work)) {
            throw new SecurityException("path must stay under the launcher directory");
        }
        return file;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 " + name);
    }

    private static int days(int recentDays) {
        if (recentDays <= 0) return 30;
        return Math.min(recentDays, 365);
    }

    private static List<String> head(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return List.copyOf(values.subList(0, Math.min(SAMPLE, values.size())));
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? e.getClass().getSimpleName() : text;
    }
}
