package com.pmcl.cli;

import com.pmcl.core.LauncherCore;
import com.pmcl.core.auth.Account;
import com.pmcl.core.auth.DeviceCode;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.gamecontent.DatapackManager;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.IntegrityChecker;
import com.pmcl.core.launch.CrashAnalyzer;
import com.pmcl.core.launch.JavaRuntimeFinder;
import com.pmcl.core.launch.LaunchProfile;
import com.pmcl.core.market.ModFile;
import com.pmcl.core.market.ModProject;
import com.pmcl.core.migration.MigrationManager;
import com.pmcl.core.modloader.ModLoader;
import com.pmcl.core.modloader.ModLoaderInstaller;
import com.pmcl.core.modloader.ModLoaderVersion;
import com.pmcl.core.mods.ModManager;
import com.pmcl.core.mods.ModMeta;
import com.pmcl.core.mods.ModScanner;
import com.pmcl.core.multiplayer.MultiplayerManager;
import com.pmcl.core.news.NewsItem;
import com.pmcl.core.plugin.PluginManager;
import com.pmcl.plugin.ModInstalledEvent;
import com.pmcl.plugin.PluginInfo;
import com.pmcl.plugin.VersionInstalledEvent;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.runtime.JavaRuntimeDownloader;
import com.pmcl.core.runtime.RuntimeManager;
import com.pmcl.core.update.SelfUpdater;
import com.pmcl.core.version.McVersion;
import com.pmcl.core.version.VersionManager;
import com.pmcl.core.web.WikiBrowser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

/**
 * PMCL Shell mode: operate all launcher features via terminal commands.
 *
 * Usage:
 *   java -jar pmcl-cli.jar                  # Enter interactive REPL
 *   java -jar pmcl-cli.jar <command> ...    # Execute a single command and exit
 *
 * Supported commands:
 *   help / ?                   Show help
 *   versions / vs              List locally installed versions
 *   remote [limit] / rm        List remote downloadable versions
 *   install <id> / i           Install a specific version
 *   launch <id> / play         Launch a specific version
 *   integrity <id> / check     Verify version integrity
 *   mods                       List installed mods
 *   mod <action> <name>        Manage mods (disable/enable/delete)
 *   search <query> / s         Search mod marketplace
 *   install-mod <id> / im      Install a mod (requires search first)
 *   modloaders / ml            List/install mod loaders
 *   worlds / w                 List world saves
 *   datapacks <worldIndex>     List datapacks in a world
 *   screenshots / shots        List screenshots
 *   resourcepacks / rp         List resource packs
 *   shaders                    List shader packs
 *   news [limit]               View Minecraft news
 *   crash                      Analyze crash reports
 *   migrate                    Migrate from other launchers
 *   account / whoami           Show current account
 *   login offline <name>       Offline login
 *   login ms                   Microsoft device code login
 *   logout                     Log out
 *   java                       Detect system Java runtimes
 *   java list <8|17|21>        List downloadable Java runtimes
 *   java install <8|17|21>     Download and install a Java runtime
 *   config [key] [value]       View/modify configuration
 *   pin <versionId>            Pin a version
 *   unpin <versionId>          Unpin a version
 *   recent                     Show recent versions
 *   playtime                   Show playtime statistics
 *   mp create                   Create a multiplayer room
 *   mp join <code>              Join a multiplayer room
 *   mp leave                    Leave current room
 *   mp status                   Show multiplayer status
 *   mp invite                   Generate invitation code
 *   update check                Check for launcher updates
 *   update download             Download latest update
 *   sysinfo                     Show system information
 *   download <url> <path>       Download a file
 *   wiki <query>                Open Minecraft Wiki search in browser
 *   status                      Show current status
 *   exit / quit                 Exit
 */
public final class PmclCli {

    private static final String SEP = "─────────────────────────────────────────────";
    private static final String BANNER =
        "\n+===========================================+\n" +
        "|          PMCL Shell  v3.0.0               |\n" +
        "|   Minecraft Launcher - CLI Mode           |\n" +
        "|   Access all launcher core features       |\n" +
        "+===========================================+\n";

    private final LauncherCore core;
    private final Path accountFile;
    private Account currentAccount;
    // Cache last search results for install-mod
    private List<ModProject> lastSearchResults = Collections.emptyList();
    private List<ModFile> lastFileList = Collections.emptyList();
    // Cache last worlds list for datapacks command
    private List<com.pmcl.core.gamecontent.WorldManager.WorldInfo> lastWorlds = Collections.emptyList();

    public PmclCli() {
        this(new LauncherCore());
    }

    /** Create with an existing LauncherCore (for GUI terminal integration). */
    public PmclCli(LauncherCore core) {
        this.core = core;
        this.accountFile = Paths.get(System.getProperty("user.home"), ".pmcl", "accounts.json");
        try {
            this.currentAccount = core.auth().loadAccount(accountFile);
        } catch (Exception e) {
            this.currentAccount = null;
        }
        // Auto-discover and load plugins
        try {
            core.plugins().discoverAndLoadAll();
        } catch (Exception e) {
            // Non-fatal: plugins are optional
        }
    }

    // ==================== Entry ====================

    public static void main(String[] args) {
        PmclCli cli = new PmclCli();
        if (args.length > 0) {
            cli.execute(args);
        } else {
            cli.repl();
        }
    }

    // ==================== REPL Loop ====================

    private void repl() {
        System.out.println(BANNER);
        printStatus();
        System.out.println("Type 'help' for available commands, 'exit' to quit.\n");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("pmcl> ");
            System.out.flush();
            String line;
            try {
                line = scanner.nextLine();
            } catch (Exception e) {
                break;
            }
            if (line == null || line.trim().isEmpty()) continue;
            String[] parts = line.trim().split("\\s+");
            String cmd = parts[0].toLowerCase();
            if (cmd.equals("exit") || cmd.equals("quit")) {
                System.out.println("Goodbye!");
                break;
            }
            try {
                execute(parts);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
        scanner.close();
    }

    /** Execute a single command (public for GUI terminal to call) */
    public void execute(String[] args) {
        String cmd = args[0].toLowerCase();
        String[] rest = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        switch (cmd) {
            case "help": case "?": cmdHelp(); break;
            case "versions": case "vs": cmdVersions(); break;
            case "remote": case "rm": cmdRemote(rest); break;
            case "install": case "i": cmdInstall(rest); break;
            case "launch": case "play": cmdLaunch(rest); break;
            case "integrity": case "check": cmdIntegrity(rest); break;
            case "mods": cmdMods(); break;
            case "mod": cmdModManage(rest); break;
            case "search": case "s": cmdSearch(rest); break;
            case "install-mod": case "im": cmdInstallMod(rest); break;
            case "modloaders": case "ml": cmdModLoaders(rest); break;
            case "worlds": case "w": cmdWorlds(rest); break;
            case "datapacks": case "dp": cmdDatapacks(rest); break;
            case "screenshots": case "shots": cmdScreenshots(); break;
            case "resourcepacks": case "rp": cmdResourcePacks(); break;
            case "shaders": cmdShaders(); break;
            case "news": cmdNews(rest); break;
            case "crash": cmdCrash(); break;
            case "migrate": cmdMigrate(rest); break;
            case "account": case "whoami": cmdAccount(); break;
            case "login": cmdLogin(rest); break;
            case "logout": cmdLogout(); break;
            case "java": cmdJava(rest); break;
            case "config": cmdConfig(rest); break;
            case "pin": cmdPin(rest); break;
            case "unpin": cmdUnpin(rest); break;
            case "recent": cmdRecent(); break;
            case "playtime": cmdPlaytime(); break;
            case "mp": case "multiplayer": cmdMultiplayer(rest); break;
            case "update": cmdUpdate(rest); break;
            case "sysinfo": cmdSysInfo(); break;
            case "download": cmdDownload(rest); break;
            case "wiki": cmdWiki(rest); break;
            case "plugin": case "plugins": cmdPlugin(rest); break;
            case "status": printStatus(); break;
            case "cache": cmdCache(rest); break;
            case "log": cmdLog(rest); break;
            case "skin": cmdSkin(); break;
            case "version": case "ver": cmdVersion(); break;
            case "open": cmdOpen(rest); break;
            case "url": cmdUrl(rest); break;
            case "theme": cmdTheme(rest); break;
            case "exit": case "quit": System.out.println("Goodbye!"); System.exit(0); break;
            default:
                // Check for plugin-registered commands: plugin:<pluginId>:<commandName>
                if (cmd.startsWith("plugin:") && cmd.split(":").length >= 3) {
                    executePluginCommand(cmd, args);
                } else {
                    System.err.println("Unknown command: " + cmd + " (type 'help' for help)");
                }
        }
    }

    /** Execute a plugin-registered command (format: plugin:<pluginId>:<commandName>) */
    private void executePluginCommand(String fullCmd, String[] args) {
        String[] parts = fullCmd.split(":", 3);
        if (parts.length < 3) {
            System.err.println("Invalid plugin command format. Use: plugin:<pluginId>:<commandName>");
            return;
        }
        String pluginId = parts[1];
        String commandName = parts[2];
        List<PluginManager.RegisteredCommand> cmds = core.plugins().getCustomCommands();
        PluginManager.RegisteredCommand target = cmds.stream()
                .filter(c -> c.pluginId.equals(pluginId) && c.name.equals(commandName))
                .findFirst()
                .orElse(null);
        if (target == null) {
            System.err.println("Plugin command not found: " + fullCmd);
            System.err.println("Use 'plugin list' to see available plugin commands.");
            return;
        }
        String[] cmdArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        try {
            String output = target.handler.execute(cmdArgs);
            if (output != null && !output.isEmpty()) {
                System.out.println(output);
            }
        } catch (Exception e) {
            System.err.println("Plugin command error: " + e.getMessage());
        }
    }

    // ==================== Status ====================

    private void printStatus() {
        System.out.println(SEP);
        System.out.println("Working directory: " + core.getConfig().getWorkDir());
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        if (currentAccount != null) {
            System.out.println("Account: " + currentAccount.getUsername() + " (" + currentAccount.getType() + ")");
        } else {
            System.out.println("Account: not logged in (use 'login offline <name>' for offline login)");
        }
        System.out.println(SEP);
    }

    // ==================== Help ====================

    private void cmdHelp() {
        System.out.println(SEP);
        System.out.println("PMCL Shell - Available Commands:");
        System.out.println(SEP);
        System.out.println("  Version Management:");
        System.out.println("    versions / vs             List locally installed versions");
        System.out.println("    remote [limit] / rm       List remote downloadable versions (default 50)");
        System.out.println("    install <id> / i          Install a specific version");
        System.out.println("    launch <id> / play        Launch a specific version");
        System.out.println("    integrity <id>            Verify version integrity");
        System.out.println("    pin <versionId>           Pin a version");
        System.out.println("    unpin <versionId>         Unpin a version");
        System.out.println("    recent                    Show recently used versions");
        System.out.println("    playtime                  Show playtime statistics");
        System.out.println();
        System.out.println("  Mod Management:");
        System.out.println("    mods                      List installed mods");
        System.out.println("    mod disable <name>        Disable a mod");
        System.out.println("    mod enable <name>         Enable a disabled mod");
        System.out.println("    mod delete <name>         Delete a mod");
        System.out.println("    search <query> / s        Search mod marketplace");
        System.out.println("    install-mod <#> / im      Install mod from last search results");
        System.out.println("    modloaders / ml           List/install mod loaders");
        System.out.println();
        System.out.println("  Game Content:");
        System.out.println("    worlds / w                List world saves");
        System.out.println("    datapacks <worldIndex>    List datapacks in a world");
        System.out.println("    screenshots / shots       List screenshots");
        System.out.println("    resourcepacks / rp        List resource packs");
        System.out.println("    shaders                   List shader packs");
        System.out.println();
        System.out.println("  Multiplayer:");
        System.out.println("    mp create                 Create a multiplayer room");
        System.out.println("    mp join <code>            Join a multiplayer room");
        System.out.println("    mp leave                  Leave current room");
        System.out.println("    mp status                 Show multiplayer status");
        System.out.println("    mp invite                 Generate invitation code");
        System.out.println();
        System.out.println("  Other Features:");
        System.out.println("    news [limit]              View Minecraft news (default 10)");
        System.out.println("    crash                     Analyze crash reports");
        System.out.println("    migrate                   Migrate from other launchers");
        System.out.println("    update check              Check for launcher updates");
        System.out.println("    update download           Download latest update");
        System.out.println("    sysinfo                   Show system information");
        System.out.println("    download <url> <path>     Download a file");
        System.out.println("    wiki <query>              Open Minecraft Wiki search in browser");
        System.out.println("    cache [clear]             Show cache info or clear all cache");
        System.out.println("    log [lines]               Show recent game log (default 50 lines)");
        System.out.println("    skin                      Show current account skin info");
        System.out.println("    version / ver             Show PMCL version info");
        System.out.println("    open <dir>                Open directory in file manager");
        System.out.println("    url <url>                 Open URL in system browser");
        System.out.println("    theme <dark|light>        Quick switch theme");
        System.out.println();
        System.out.println("  Plugin Management:");
        System.out.println("    plugin list               List loaded plugins");
        System.out.println("    plugin install <file|url> Install a plugin from JAR or URL");
        System.out.println("    plugin uninstall <id>     Uninstall a plugin");
        System.out.println("    plugin enable <id>        Enable a plugin");
        System.out.println("    plugin disable <id>       Disable a plugin");
        System.out.println("    plugin info <id>          Show plugin details");
        System.out.println("    plugin reload <id>        Reload a plugin");
        System.out.println("    plugin discover           Scan and load all plugins");
        System.out.println();
        System.out.println("  Account & System:");
        System.out.println("    account / whoami          Show current account");
        System.out.println("    login offline <name>      Offline login");
        System.out.println("    login ms                  Microsoft device code login");
        System.out.println("    logout                    Log out");
        System.out.println("    java                      Detect system Java runtimes");
        System.out.println("    java list <8|17|21>       List downloadable Java runtimes");
        System.out.println("    java install <8|17|21>    Download and install a Java runtime");
        System.out.println("    config [key] [value]      View/modify configuration");
        System.out.println("    status                    Show current status");
        System.out.println("    exit / quit               Exit");
        System.out.println(SEP);
        System.out.println("Tip: Commands support abbreviations (vs/rm/i/s/im/ml/w/rp/dp)");
    }

    // ==================== Version Management ====================

    private void cmdVersions() {
        System.out.println("Scanning local versions...");
        List<VersionManager.LocalVersionInfo> list = core.versions().scanAllLocalVersions();
        if (list.isEmpty()) {
            System.out.println("No local versions found.");
            System.out.println("Tip: Use 'install <versionId>' to install a new version, or 'remote' to see available versions.");
            return;
        }
        System.out.println(SEP);
        System.out.printf("Total %d local versions:%n", list.size());
        System.out.println(SEP);
        for (VersionManager.LocalVersionInfo v : list) {
            String status = v.isLaunchable() ? "[OK]" : "[X]";
            String jar = v.isHasJar() ? "jar" : "no-jar";
            String inherits = v.getInheritsFrom() != null && !v.getInheritsFrom().isEmpty()
                    ? " <- " + v.getInheritsFrom() : "";
            System.out.printf("  %s %-30s [%s]%s%n", status, v.getId(), jar, inherits);
        }
    }

    private void cmdRemote(String[] rest) {
        int limit = 50;
        if (rest.length > 0) {
            try { limit = Integer.parseInt(rest[0]); } catch (Exception ignored) {}
        }
        System.out.println("Fetching remote version list...");
        try {
            List<McVersion> versions = core.versions().fetchRemoteVersions().get();
            if (versions.isEmpty()) {
                System.out.println("Failed to fetch remote versions (network issue?).");
                return;
            }
            System.out.println(SEP);
            System.out.printf("Total %d remote versions (showing first %d):%n", versions.size(), Math.min(limit, versions.size()));
            System.out.println(SEP);
            int shown = 0;
            for (McVersion v : versions) {
                if (shown >= limit) break;
                String type = v.getType() != null ? v.getType() : "unknown";
                System.out.printf("  %-20s [%s]%n", v.getId(), type);
                shown++;
            }
            System.out.println("\nUse 'install <versionId>' to install.");
        } catch (Exception e) {
            System.err.println("Failed to fetch remote versions: " + e.getMessage());
        }
    }

    private void cmdInstall(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: install <versionId>");
            return;
        }
        String versionId = rest[0];
        System.out.println("Installing version: " + versionId);
        try {
            CompletableFuture<Void> future = core.install().install(versionId, p -> {
                System.out.printf("\r  [%s] %s (%.0f%%)",
                        p.getStage(), p.getMessage(), p.percent());
                if (p.getStage() == InstallProgress.Stage.DONE) {
                    System.out.println();
                }
            });
            future.get();
            System.out.println("Installation complete: " + versionId);
            System.out.println("Use 'launch " + versionId + "' to start the game.");
            core.plugins().fireEvent(new VersionInstalledEvent(versionId));
        } catch (Exception e) {
            System.err.println("\nInstallation failed: " + e.getMessage());
        }
    }

    private void cmdLaunch(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: launch <versionId>");
            return;
        }
        String versionId = rest[0];
        if (currentAccount == null) {
            System.err.println("No account logged in. Use 'login offline <name>' first.");
            return;
        }
        System.out.println("Building launch profile...");
        try {
            int requiredJava = core.profileBuilder().getRequiredJavaVersion(versionId);
            System.out.println("Requires Java " + requiredJava + " or higher");
            Path runtimesDir = core.getConfig().getWorkDir().resolve("runtimes");
            String javaPath = JavaRuntimeFinder.findJavaExecutable(runtimesDir, requiredJava);
            if (javaPath == null) {
                System.err.println("No suitable Java " + requiredJava + " runtime found.");
                System.err.println("Please install the corresponding Java version, or configure Java path in PMCL settings.");
                return;
            }
            System.out.println("Using Java: " + javaPath);

            LaunchProfile profile = core.profileBuilder().build(versionId, currentAccount, requiredJava);
            System.out.println(SEP);
            System.out.println("Launching Minecraft " + versionId + " ...");
            System.out.println(SEP);

            CompletableFuture<Integer> future = core.launch().launchAsync(
                profile, javaPath,
                line -> System.out.println("[game] " + line)
            );
            int exitCode = future.get();
            System.out.println(SEP);
            System.out.println("Game exited (code=" + exitCode + ")");
        } catch (Exception e) {
            System.err.println("Launch failed: " + e.getMessage());
        }
    }

    private void cmdIntegrity(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: integrity <versionId>");
            return;
        }
        String versionId = rest[0];
        System.out.println("Verifying version integrity: " + versionId + " ...");
        try {
            IntegrityChecker.Result result = core.integrity().check(versionId);
            if (result.isOk()) {
                System.out.println("[OK] Version integrity verified, all files are normal.");
            } else {
                System.out.println(SEP);
                System.out.printf("[X] Found %d issue(s):%n", result.getIssueCount());
                System.out.println(SEP);
                if (!result.getMissing().isEmpty()) {
                    System.out.println("Missing files (" + result.getMissing().size() + "):");
                    for (String f : result.getMissing().subList(0, Math.min(20, result.getMissing().size()))) {
                        System.out.println("  - " + f);
                    }
                    if (result.getMissing().size() > 20) {
                        System.out.println("  ... and " + (result.getMissing().size() - 20) + " more");
                    }
                }
                if (!result.getHashMismatch().isEmpty()) {
                    System.out.println("Hash mismatches (" + result.getHashMismatch().size() + "):");
                    for (String f : result.getHashMismatch().subList(0, Math.min(20, result.getHashMismatch().size()))) {
                        System.out.println("  - " + f);
                    }
                }
                System.out.println("\nSuggestion: Reinstall this version to fix the issues.");
            }
        } catch (Exception e) {
            System.err.println("Verification failed: " + e.getMessage());
        }
    }

    // ==================== Pin / Unpin / Recent / Playtime ====================

    private void cmdPin(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: pin <versionId>");
            return;
        }
        Preferences pref = core.getPreferences();
        pref.pinVersion(rest[0]);
        System.out.println("Pinned: " + rest[0]);
    }

    private void cmdUnpin(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: unpin <versionId>");
            return;
        }
        Preferences pref = core.getPreferences();
        pref.unpinVersion(rest[0]);
        System.out.println("Unpinned: " + rest[0]);
    }

    private void cmdRecent() {
        Preferences pref = core.getPreferences();
        List<String> recent = pref.getRecentVersions();
        if (recent.isEmpty()) {
            System.out.println("No recent versions.");
            return;
        }
        System.out.println(SEP);
        System.out.printf("Recent versions (%d):%n", recent.size());
        System.out.println(SEP);
        for (int i = 0; i < recent.size(); i++) {
            String v = recent.get(i);
            String pin = pref.isPinned(v) ? " [pinned]" : "";
            System.out.printf("  %d. %s%s%n", i + 1, v, pin);
        }
    }

    private void cmdPlaytime() {
        Preferences pref = core.getPreferences();
        var times = pref.getLastPlayedTimesRaw();
        if (times.isEmpty()) {
            System.out.println("No playtime records.");
            return;
        }
        System.out.println(SEP);
        System.out.printf("Playtime records (%d):%n", times.size());
        System.out.println(SEP);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        List<String> sorted = new ArrayList<>(times.keySet());
        sorted.sort((a, b) -> {
            Long ta = times.get(a), tb = times.get(b);
            return tb.compareTo(ta);
        });
        for (String v : sorted) {
            Long t = times.get(v);
            String date = t != null ? sdf.format(new Date(t)) : "unknown";
            System.out.printf("  %-30s last played: %s%n", v, date);
        }
    }

    // ==================== Mod Management ====================

    private void cmdMods() {
        System.out.println("Scanning mods...");
        List<ModMeta> allMods = new ArrayList<>();
        Path pmclMods = core.getConfig().getWorkDir().resolve("mods");
        scanModsDir(pmclMods, allMods);
        List<Path> versionsDirs = new ArrayList<>();
        versionsDirs.add(core.getConfig().getVersionsDir());
        versionsDirs.addAll(VersionManager.detectAllMinecraftVersionsDirs());
        for (Path versionsDir : versionsDirs) {
            if (!Files.isDirectory(versionsDir)) continue;
            File[] subDirs = versionsDir.toFile().listFiles(File::isDirectory);
            if (subDirs == null) continue;
            for (File subDir : subDirs) {
                scanModsDir(subDir.toPath().resolve("mods"), allMods);
            }
        }
        if (allMods.isEmpty()) {
            System.out.println("No installed mods found.");
            System.out.println("Tip: Use 'search <query>' to search and install mods.");
            return;
        }
        System.out.println(SEP);
        System.out.printf("Total %d mods:%n", allMods.size());
        System.out.println(SEP);
        int enabled = 0, disabled = 0;
        for (ModMeta m : allMods) {
            String status = m.isDisabled() ? "[DISABLED]" : "[ENABLED]";
            if (m.isDisabled()) disabled++; else enabled++;
            System.out.printf("  %-12s %-35s [%s] v%s%n",
                    status, m.getName(), m.getLoader(), m.getVersion());
        }
        System.out.printf("%nEnabled %d / Disabled %d / Total %d%n", enabled, disabled, allMods.size());
    }

    private void scanModsDir(Path dir, List<ModMeta> out) {
        if (!Files.isDirectory(dir)) return;
        try {
            out.addAll(ModScanner.scanDirectory(dir));
        } catch (IOException ignored) {}
    }

    private void cmdModManage(String[] rest) {
        if (rest.length < 2) {
            System.err.println("Usage: mod <disable|enable|delete> <jarFileName>");
            System.err.println("Use 'mods' to list installed mods and their jar file names.");
            return;
        }
        String action = rest[0].toLowerCase();
        String jarName = rest[1];
        ModManager mm = core.modManager();
        try {
            switch (action) {
                case "disable": {
                    String result = mm.disableMod(jarName);
                    System.out.println("Disabled: " + result);
                    break;
                }
                case "enable": {
                    String result = mm.enableMod(jarName);
                    System.out.println("Enabled: " + result);
                    break;
                }
                case "delete": case "remove": case "rm": {
                    boolean ok = mm.deleteMod(jarName);
                    if (ok) {
                        System.out.println("Deleted: " + jarName);
                    } else {
                        System.err.println("Mod not found: " + jarName);
                    }
                    break;
                }
                default:
                    System.err.println("Unknown action: " + action + " (supported: disable, enable, delete)");
            }
        } catch (IOException e) {
            System.err.println("Operation failed: " + e.getMessage());
        }
    }

    private void cmdSearch(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: search <query>");
            return;
        }
        String query = String.join(" ", rest);
        System.out.println("Searching mods: " + query + " ...");
        try {
            List<ModProject> results = core.modMarket().search(query, null, null, 20).get();
            if (results.isEmpty()) {
                System.out.println("No matching mods found.");
                lastSearchResults = Collections.emptyList();
                return;
            }
            lastSearchResults = results;
            System.out.println(SEP);
            System.out.printf("Found %d results:%n", results.size());
            System.out.println(SEP);
            for (int i = 0; i < results.size(); i++) {
                ModProject p = results.get(i);
                System.out.printf("  %2d. %-30s [%s] downloads %d%n",
                        i + 1, p.getName(), p.getSource(), p.getDownloadCount());
            }
            System.out.println("\nUse 'install-mod <#>' to install, e.g.: install-mod 1");
        } catch (Exception e) {
            System.err.println("Search failed: " + e.getMessage());
        }
    }

    private void cmdInstallMod(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: install-mod <#>");
            System.err.println("Please use 'search <query>' first, then install by index number.");
            return;
        }
        int idx;
        try {
            idx = Integer.parseInt(rest[0]) - 1;
        } catch (NumberFormatException e) {
            System.err.println("Invalid index: " + rest[0]);
            return;
        }
        if (lastSearchResults.isEmpty()) {
            System.err.println("No search results. Please use 'search <query>' first.");
            return;
        }
        if (idx < 0 || idx >= lastSearchResults.size()) {
            System.err.println("Index out of range (1-" + lastSearchResults.size() + ").");
            return;
        }
        ModProject project = lastSearchResults.get(idx);
        System.out.println("Fetching mod file list: " + project.getName() + " ...");
        try {
            List<ModFile> files = core.modMarket().listFiles(project).get();
            if (files.isEmpty()) {
                System.err.println("No mod files available.");
                return;
            }
            // Select first release-type file
            ModFile target = files.stream()
                    .filter(f -> "release".equals(f.getReleaseType()))
                    .findFirst()
                    .orElse(files.get(0));
            lastFileList = files;

            System.out.println(SEP);
            System.out.println("Will install: " + target.getFileName());
            String versions = target.getGameVersions() != null ? String.join(", ", target.getGameVersions()) : "?";
            String loaders = target.getLoaders() != null ? String.join(", ", target.getLoaders()) : "any";
            System.out.println("Game versions: " + versions + " | Loaders: " + loaders);
            long sizeMb = target.getFileSize() / (1024 * 1024);
            System.out.println("File size: " + sizeMb + " MB");
            System.out.println(SEP);

            // Determine target game version
            String gameVersion = rest.length > 1 ? rest[1] : "";
            if (gameVersion.isEmpty() && target.getGameVersions() != null && !target.getGameVersions().isEmpty()) {
                gameVersion = target.getGameVersions().get(0);
            }
            if (gameVersion.isEmpty()) {
                System.err.println("Cannot determine game version. Please specify: install-mod <#> <gameVersion>");
                return;
            }

            System.out.println("Installing to MC " + gameVersion + " ...");
            core.modMarket().installMod(target, gameVersion, status -> {
                System.out.println("  " + status);
            }).get();
            System.out.println("[OK] Installation complete: " + target.getFileName());
            core.plugins().fireEvent(new ModInstalledEvent(project.getName(), target.getFileName()));
        } catch (Exception e) {
            System.err.println("Installation failed: " + e.getMessage());
        }
    }

    // ==================== Mod Loaders ====================

    private void cmdModLoaders(String[] rest) {
        if (rest.length == 0) {
            System.out.println(SEP);
            System.out.println("Supported Mod Loaders:");
            System.out.println(SEP);
            for (ModLoader loader : ModLoader.values()) {
                String supported = core.modLoaders().supports(loader) ? "[OK]" : "[X]";
                System.out.printf("  %s %s%n", supported, loader);
            }
            System.out.println("\nUsage:");
            System.out.println("  modloaders list <loader> <gameVersion>");
            System.out.println("  modloaders install <loader> <gameVersion> <loaderVersion>");
            System.out.println("Examples:");
            System.out.println("  modloaders list fabric 1.20.4");
            System.out.println("  modloaders install fabric 1.20.4 0.15.7");
            return;
        }

        String action = rest[0].toLowerCase();
        switch (action) {
            case "list":
                if (rest.length < 3) {
                    System.err.println("Usage: modloaders list <loader> <gameVersion>");
                    System.err.println("Loaders: fabric, forge, neoforge, quilt");
                    return;
                }
                cmdModLoaderList(rest[1], rest[2]);
                break;
            case "install":
                if (rest.length < 4) {
                    System.err.println("Usage: modloaders install <loader> <gameVersion> <loaderVersion>");
                    return;
                }
                cmdModLoaderInstall(rest[1], rest[2], rest[3]);
                break;
            default:
                System.err.println("Unknown action: " + action + " (supported: list, install)");
        }
    }

    private ModLoader parseLoader(String name) {
        switch (name.toLowerCase()) {
            case "fabric": return ModLoader.FABRIC;
            case "forge": return ModLoader.FORGE;
            case "neoforge": case "neo": return ModLoader.NEOFORGE;
            case "quilt": return ModLoader.QUILT;
            case "vanilla": return ModLoader.VANILLA;
            default: return null;
        }
    }

    private void cmdModLoaderList(String loaderName, String gameVersion) {
        ModLoader loader = parseLoader(loaderName);
        if (loader == null) {
            System.err.println("Unknown loader: " + loaderName + " (supported: fabric, forge, neoforge, quilt)");
            return;
        }
        if (loader == ModLoader.VANILLA) {
            System.out.println("Vanilla does not need a mod loader.");
            return;
        }
        if (!core.modLoaders().supports(loader)) {
            System.err.println("Unsupported loader: " + loader);
            return;
        }
        System.out.println("Fetching available " + loader + " versions for MC " + gameVersion + " ...");
        try {
            ModLoaderInstaller installer = core.modLoaders().get(loader);
            List<ModLoaderVersion> versions = installer.listVersions(gameVersion).get();
            if (versions.isEmpty()) {
                System.out.println("No available versions found.");
                return;
            }
            System.out.println(SEP);
            System.out.printf("Total %d available versions (showing first 20):%n", versions.size());
            System.out.println(SEP);
            int limit = Math.min(20, versions.size());
            for (int i = 0; i < limit; i++) {
                ModLoaderVersion v = versions.get(i);
                String stable = v.isStable() ? "stable" : "beta";
                System.out.printf("  %-15s [%s]%n", v.getLoaderVersion(), stable);
            }
            System.out.println("\nUse 'modloaders install " + loaderName + " " + gameVersion + " <version>' to install.");
        } catch (Exception e) {
            System.err.println("Failed to fetch version list: " + e.getMessage());
        }
    }

    private void cmdModLoaderInstall(String loaderName, String gameVersion, String loaderVersion) {
        ModLoader loader = parseLoader(loaderName);
        if (loader == null) {
            System.err.println("Unknown loader: " + loaderName + " (supported: fabric, forge, neoforge, quilt)");
            return;
        }
        System.out.println("Installing " + loader + " " + loaderVersion + " for MC " + gameVersion + " ...");
        try {
            ModLoaderInstaller installer = core.modLoaders().get(loader);
            installer.install(gameVersion, loaderVersion, p -> {
                System.out.printf("\r  [%s] %s (%.0f%%)",
                        p.getStage(), p.getMessage(), p.percent());
                if (p.getStage() == InstallProgress.Stage.DONE) {
                    System.out.println();
                }
            }).get();
            System.out.println("[OK] " + loader + " " + loaderVersion + " installation complete");
        } catch (Exception e) {
            System.err.println("\nInstallation failed: " + e.getMessage());
        }
    }

    // ==================== Game Content ====================

    private void cmdWorlds(String[] rest) {
        System.out.println("Scanning world saves...");
        try {
            List<com.pmcl.core.gamecontent.WorldManager.WorldInfo> worlds = core.worlds().listWorlds();
            if (worlds.isEmpty()) {
                System.out.println("No world saves found.");
                return;
            }
            lastWorlds = worlds;
            System.out.println(SEP);
            System.out.printf("Total %d worlds:%n", worlds.size());
            System.out.println(SEP);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            for (int i = 0; i < worlds.size(); i++) {
                var w = worlds.get(i);
                String size = formatSize(w.getSizeBytes());
                String date = sdf.format(new Date(w.getLastModified()));
                String source = w.getSource() != null ? " [" + w.getSource() + "]" : "";
                System.out.printf("  %2d. %-25s %10s  %s%s%n", i + 1, w.getName(), size, date, source);
            }
            if (rest.length > 0 && rest[0].equalsIgnoreCase("backup") && rest.length > 1) {
                int idx;
                try { idx = Integer.parseInt(rest[1]) - 1; } catch (Exception e) {
                    System.err.println("Invalid index: " + rest[1]);
                    return;
                }
                if (idx >= 0 && idx < worlds.size()) {
                    var w = worlds.get(idx);
                    System.out.println("\nBacking up: " + w.getName() + " ...");
                    Path backup = core.worlds().backup(w);
                    System.out.println("[OK] Backed up to: " + backup);
                }
            }
        } catch (Exception e) {
            System.err.println("Scan failed: " + e.getMessage());
        }
    }

    private void cmdDatapacks(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: datapacks <worldIndex>");
            System.err.println("Use 'worlds' first to list worlds, then specify the index number.");
            return;
        }
        int idx;
        try {
            idx = Integer.parseInt(rest[0]) - 1;
        } catch (NumberFormatException e) {
            System.err.println("Invalid index: " + rest[0]);
            return;
        }
        // Try to use cached worlds, or re-scan
        List<com.pmcl.core.gamecontent.WorldManager.WorldInfo> worlds = lastWorlds;
        if (worlds.isEmpty()) {
            try {
                worlds = core.worlds().listWorlds();
                lastWorlds = worlds;
            } catch (Exception e) {
                System.err.println("Failed to list worlds: " + e.getMessage());
                return;
            }
        }
        if (idx < 0 || idx >= worlds.size()) {
            System.err.println("Index out of range (1-" + worlds.size() + ").");
            return;
        }
        var w = worlds.get(idx);
        System.out.println("Scanning datapacks for world: " + w.getName() + " ...");
        try {
            DatapackManager dm = new DatapackManager();
            List<DatapackManager.Datapack> packs = dm.list(w.getDir());
            if (packs.isEmpty()) {
                System.out.println("No datapacks found in this world.");
                return;
            }
            System.out.println(SEP);
            System.out.printf("Total %d datapacks:%n", packs.size());
            System.out.println(SEP);
            for (int i = 0; i < packs.size(); i++) {
                var p = packs.get(i);
                String type = p.isZip() ? "zip" : "dir";
                String desc = p.getDescription() != null && !p.getDescription().isEmpty()
                        ? " - " + p.getDescription() : "";
                System.out.printf("  %2d. %-30s [%s] format=%d%s%n",
                        i + 1, p.getName(), type, p.getPackFormat(), desc);
            }
        } catch (Exception e) {
            System.err.println("Scan failed: " + e.getMessage());
        }
    }

    private void cmdScreenshots() {
        System.out.println("Scanning screenshots...");
        try {
            List<com.pmcl.core.gamecontent.ScreenshotManager.Screenshot> shots = core.screenshots().list();
            if (shots.isEmpty()) {
                System.out.println("No screenshots found.");
                return;
            }
            System.out.println(SEP);
            System.out.printf("Total %d screenshots:%n", shots.size());
            System.out.println(SEP);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            for (int i = 0; i < shots.size(); i++) {
                var s = shots.get(i);
                String size = formatSize(s.getSize());
                String date = sdf.format(new Date(s.getModified()));
                String source = s.getSource() != null ? " [" + s.getSource() + "]" : "";
                System.out.printf("  %2d. %-30s %8s  %s%s%n", i + 1, s.getName(), size, date, source);
            }
        } catch (Exception e) {
            System.err.println("Scan failed: " + e.getMessage());
        }
    }

    private void cmdResourcePacks() {
        System.out.println("Scanning resource packs...");
        try {
            List<com.pmcl.core.gamecontent.ResourcePackManager.Pack> packs = core.resourcePacks().list();
            if (packs.isEmpty()) {
                System.out.println("No resource packs found.");
                return;
            }
            System.out.println(SEP);
            System.out.printf("Total %d resource packs:%n", packs.size());
            System.out.println(SEP);
            for (int i = 0; i < packs.size(); i++) {
                var p = packs.get(i);
                String type = p.isZip() ? "zip" : "dir";
                String desc = p.getDescription() != null && !p.getDescription().isEmpty()
                        ? " - " + p.getDescription() : "";
                System.out.printf("  %2d. %-30s [%s] format=%d%s%n",
                        i + 1, p.getName(), type, p.getPackFormat(), desc);
            }
        } catch (Exception e) {
            System.err.println("Scan failed: " + e.getMessage());
        }
    }

    private void cmdShaders() {
        System.out.println("Scanning shader packs...");
        try {
            List<com.pmcl.core.gamecontent.ShaderPackManager.ShaderPack> packs = core.shaderPacks().list();
            if (packs.isEmpty()) {
                System.out.println("No shader packs found.");
                return;
            }
            System.out.println(SEP);
            System.out.printf("Total %d shader packs:%n", packs.size());
            System.out.println(SEP);
            for (int i = 0; i < packs.size(); i++) {
                var p = packs.get(i);
                String valid = p.isValid() ? "[OK]" : "[X]";
                String active = p.isActive() ? " [active]" : "";
                String size = formatSize(p.getSize());
                System.out.printf("  %s %-30s %10s%s%n", valid, p.getName(), size, active);
            }
        } catch (Exception e) {
            System.err.println("Scan failed: " + e.getMessage());
        }
    }

    // ==================== News ====================

    private void cmdNews(String[] rest) {
        int limit = 10;
        if (rest.length > 0) {
            try { limit = Integer.parseInt(rest[0]); } catch (Exception ignored) {}
        }
        System.out.println("Fetching Minecraft news...");
        try {
            List<NewsItem> news = core.news().fetch(limit).get();
            if (news.isEmpty()) {
                System.out.println("No news fetched (network issue?).");
                return;
            }
            System.out.println(SEP);
            System.out.printf("Latest %d Minecraft news:%n", news.size());
            System.out.println(SEP);
            for (int i = 0; i < news.size(); i++) {
                NewsItem n = news.get(i);
                System.out.printf("%n  [%d] %s%n", i + 1, n.getTitle());
                if (n.getCategory() != null && !n.getCategory().isEmpty()) {
                    System.out.println("      Category: " + n.getCategory());
                }
                if (n.getPubDate() != null && !n.getPubDate().isEmpty()) {
                    System.out.println("      Date: " + n.getPubDate());
                }
                // Summary (strip HTML tags)
                String desc = n.getDescription().replaceAll("<[^>]+>", "").trim();
                if (!desc.isEmpty()) {
                    int maxLen = 120;
                    String summary = desc.length() > maxLen ? desc.substring(0, maxLen) + "..." : desc;
                    System.out.println("      Summary: " + summary);
                }
                if (n.getLink() != null && !n.getLink().isEmpty()) {
                    System.out.println("      Link: " + n.getLink());
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch news: " + e.getMessage());
        }
    }

    // ==================== Crash Analysis ====================

    private void cmdCrash() {
        System.out.println("Scanning crash reports...");
        try {
            Path workDir = core.getConfig().getWorkDir();
            List<CrashAnalyzer.CrashReport> reports = core.crashAnalyzer().scanReports(workDir);
            if (reports.isEmpty()) {
                // Also check default MC crash reports directory
                Path mcCrashDir = Paths.get(System.getProperty("user.home"), "Library",
                        "Application Support", "minecraft", "crash-reports");
                if (Files.isDirectory(mcCrashDir)) {
                    reports = core.crashAnalyzer().scanReports(mcCrashDir.getParent());
                }
            }
            if (reports.isEmpty()) {
                System.out.println("No crash reports found.");
                return;
            }
            System.out.println(SEP);
            System.out.printf("Found %d crash reports (showing latest 10):%n", reports.size());
            System.out.println(SEP);
            int limit = Math.min(10, reports.size());
            for (int i = 0; i < limit; i++) {
                CrashAnalyzer.CrashReport r = reports.get(i);
                System.out.printf("%n  [%d] %s%n", i + 1, r.getFile().getFileName());
                if (r.getCauses() != null && !r.getCauses().isEmpty()) {
                    System.out.println("      Possible causes:");
                    for (String cause : r.getCauses()) {
                        System.out.println("        - " + cause);
                    }
                }
                if (r.getSuggestions() != null && !r.getSuggestions().isEmpty()) {
                    System.out.println("      Suggestions:");
                    for (String sugg : r.getSuggestions()) {
                        System.out.println("        -> " + sugg);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Scan failed: " + e.getMessage());
        }
    }

    // ==================== Migration ====================

    private void cmdMigrate(String[] rest) {
        System.out.println("Detecting migratable launchers...");
        try {
            List<MigrationManager.Source> sources = core.migration().detectSources();
            if (sources.isEmpty()) {
                System.out.println("No other Minecraft launchers detected.");
                return;
            }
            System.out.println(SEP);
            System.out.printf("Detected %d migratable sources:%n", sources.size());
            System.out.println(SEP);
            for (int i = 0; i < sources.size(); i++) {
                MigrationManager.Source s = sources.get(i);
                String size = MigrationManager.formatSize(s.getEstimatedSize());
                String hasVer = s.hasVersions() ? "has-versions" : "no-versions";
                System.out.printf("  %d. %-20s %10s  %s%n", i + 1, s.getName(), size, hasVer);
                System.out.printf("     Config dir: %s%n", s.getConfigDir());
                System.out.printf("     Game dir:   %s%n", s.getGameRoot());
            }
            if (rest.length > 0 && rest[0].equalsIgnoreCase("do") && rest.length > 1) {
                int idx;
                try { idx = Integer.parseInt(rest[1]) - 1; } catch (Exception e) {
                    System.err.println("Invalid index: " + rest[1]);
                    return;
                }
                if (idx >= 0 && idx < sources.size()) {
                    MigrationManager.Source s = sources.get(idx);
                    System.out.println("\nMigrating: " + s.getName() + " ...");
                    core.migration().migrate(s, progress -> {
                        System.out.println("  " + progress);
                    });
                    System.out.println("[OK] Migration complete");
                }
            } else {
                System.out.println("\nUse 'migrate do <#>' to execute migration.");
            }
        } catch (Exception e) {
            System.err.println("Migration failed: " + e.getMessage());
        }
    }

    // ==================== Account ====================

    private void cmdAccount() {
        if (currentAccount == null) {
            System.out.println("No account logged in.");
            System.out.println("Use 'login offline <name>' for offline login, or 'login ms' for Microsoft login.");
            return;
        }
        System.out.println(SEP);
        System.out.println("Current Account:");
        System.out.println(SEP);
        System.out.println("  Username: " + currentAccount.getUsername());
        System.out.println("  UUID: " + currentAccount.getUuid());
        System.out.println("  Type: " + currentAccount.getType());
        System.out.println("  Token: " + (currentAccount.getAccessToken() != null ? "set" : "none"));
    }

    private void cmdLogin(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: login offline <name>  or  login ms");
            return;
        }
        String method = rest[0].toLowerCase();
        switch (method) {
            case "offline":
                if (rest.length < 2) {
                    System.err.println("Usage: login offline <username>");
                    return;
                }
                String username = rest[1];
                currentAccount = core.auth().offline(username);
                try {
                    core.auth().saveAccount(currentAccount, accountFile);
                    System.out.println("Offline login successful: " + username);
                } catch (IOException e) {
                    System.err.println("Failed to save account: " + e.getMessage());
                }
                break;
            case "ms":
                System.out.println("Microsoft device code login...");
                try {
                    DeviceCode dc = core.auth().requestDeviceCode();
                    System.out.println(SEP);
                    System.out.println("Please visit in browser: " + dc.getVerificationUri());
                    System.out.println("And enter code: " + dc.getUserCode());
                    System.out.println(SEP);
                    System.out.println("Waiting for login to complete...");
                    currentAccount = core.auth().loginMicrosoftAsync(dc, msg -> {
                        System.out.println("[login] " + msg);
                    }).get();
                    if (currentAccount != null) {
                        core.auth().saveAccount(currentAccount, accountFile);
                        System.out.println("Microsoft login successful: " + currentAccount.getUsername());
                    } else {
                        System.err.println("Microsoft login failed.");
                    }
                } catch (Exception e) {
                    System.err.println("Microsoft login failed: " + e.getMessage());
                }
                break;
            default:
                System.err.println("Unknown login method: " + method + " (supported: offline, ms)");
        }
    }

    private void cmdLogout() {
        if (currentAccount == null) {
            System.out.println("Not logged in.");
            return;
        }
        currentAccount = null;
        try {
            Files.deleteIfExists(accountFile);
            System.out.println("Logged out.");
        } catch (IOException e) {
            System.err.println("Failed to delete account file: " + e.getMessage());
        }
    }

    // ==================== Java Runtime ====================

    private void cmdJava(String[] rest) {
        if (rest.length == 0) {
            // Show detected Java runtimes
            System.out.println("Detecting system Java runtimes...");
            System.out.println(SEP);
            System.out.printf("  Current JVM: Java %s (%s)%n",
                    System.getProperty("java.version"),
                    System.getProperty("java.home"));
            Path runtimesDir = core.getConfig().getWorkDir().resolve("runtimes");
            String java8 = JavaRuntimeFinder.findJavaExecutable(runtimesDir, 8);
            System.out.printf("  Java 8:  %s%n", java8 != null ? java8 : "not found");
            String java17 = JavaRuntimeFinder.findJavaExecutable(runtimesDir, 17);
            System.out.printf("  Java 17: %s%n", java17 != null ? java17 : "not found");
            String java21 = JavaRuntimeFinder.findJavaExecutable(runtimesDir, 21);
            System.out.printf("  Java 21: %s%n", java21 != null ? java21 : "not found");
            System.out.println(SEP);
            System.out.println("Tip: Old Minecraft versions (1.12.2 and earlier) require Java 8.");
            System.out.println("Use 'java list <8|17|21>' to see downloadable runtimes.");
            System.out.println("Use 'java install <8|17|21>' to download and install a runtime.");
            return;
        }
        String action = rest[0].toLowerCase();
        switch (action) {
            case "list":
                if (rest.length < 2) {
                    System.err.println("Usage: java list <8|17|21>");
                    return;
                }
                cmdJavaList(rest[1]);
                break;
            case "install":
                if (rest.length < 2) {
                    System.err.println("Usage: java install <8|17|21>");
                    return;
                }
                cmdJavaInstall(rest[1]);
                break;
            default:
                System.err.println("Unknown action: " + action + " (supported: list, install)");
        }
    }

    private JavaRuntimeDownloader.RuntimeType parseRuntimeType(String s) {
        switch (s) {
            case "8": return JavaRuntimeDownloader.RuntimeType.JAVA_8;
            case "17": return JavaRuntimeDownloader.RuntimeType.JAVA_17;
            case "21": return JavaRuntimeDownloader.RuntimeType.JAVA_21;
            default: return null;
        }
    }

    private void cmdJavaList(String versionStr) {
        JavaRuntimeDownloader.RuntimeType type = parseRuntimeType(versionStr);
        if (type == null) {
            System.err.println("Invalid version: " + versionStr + " (supported: 8, 17, 21)");
            return;
        }
        System.out.println("Fetching available " + type.getDisplayName() + " runtimes...");
        try {
            List<JavaRuntimeDownloader.RuntimeEntry> entries = core.javaDownloader().listRuntimes(type).get();
            if (entries.isEmpty()) {
                System.out.println("No runtimes available.");
                return;
            }
            System.out.println(SEP);
            System.out.printf("Available %s runtimes (%d):%n", type.getDisplayName(), entries.size());
            System.out.println(SEP);
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                long sizeMb = e.getSize() / (1024 * 1024);
                System.out.printf("  %d. %-20s v%-10s %5d MB%n", i + 1, e.getName(), e.getVersion(), sizeMb);
            }
            System.out.println("\nUse 'java install " + versionStr + "' to install.");
        } catch (Exception e) {
            System.err.println("Failed to fetch runtime list: " + e.getMessage());
        }
    }

    private void cmdJavaInstall(String versionStr) {
        JavaRuntimeDownloader.RuntimeType type = parseRuntimeType(versionStr);
        if (type == null) {
            System.err.println("Invalid version: " + versionStr + " (supported: 8, 17, 21)");
            return;
        }
        System.out.println("Fetching available " + type.getDisplayName() + " runtimes...");
        try {
            List<JavaRuntimeDownloader.RuntimeEntry> entries = core.javaDownloader().listRuntimes(type).get();
            if (entries.isEmpty()) {
                System.err.println("No runtimes available to install.");
                return;
            }
            // Install the first available runtime
            JavaRuntimeDownloader.RuntimeEntry entry = entries.get(0);
            System.out.println("Installing: " + entry.getName() + " v" + entry.getVersion() + " ...");
            core.javaDownloader().install(type, entry, status -> {
                System.out.println("  " + status);
            }).get();
            System.out.println("[OK] " + type.getDisplayName() + " installation complete");
        } catch (Exception e) {
            System.err.println("Installation failed: " + e.getMessage());
        }
    }

    // ==================== Multiplayer ====================

    private void cmdMultiplayer(String[] rest) {
        if (rest.length == 0) {
            System.out.println(SEP);
            System.out.println("Multiplayer Commands:");
            System.out.println(SEP);
            System.out.println("  mp create         Create a multiplayer room");
            System.out.println("  mp join <code>    Join a multiplayer room");
            System.out.println("  mp leave          Leave current room");
            System.out.println("  mp status         Show multiplayer status");
            System.out.println("  mp invite         Generate invitation code");
            System.out.println(SEP);
            MultiplayerManager mp = core.multiplayer();
            System.out.println("Backend: " + mp.getBackend());
            System.out.println("State: " + mp.getState());
            return;
        }
        String action = rest[0].toLowerCase();
        MultiplayerManager mp = core.multiplayer();
        String playerName = currentAccount != null ? currentAccount.getUsername() : "Player";
        switch (action) {
            case "create": {
                System.out.println("Creating multiplayer room (backend: " + mp.getBackend() + ") ...");
                try {
                    mp.createRoomTerracotta(status -> {
                        System.out.println("  " + status);
                    }, playerName).get();
                    System.out.println(SEP);
                    System.out.println("[OK] Room created successfully!");
                    System.out.println("Room code: " + mp.getCurrentRoomCode());
                    System.out.println("Virtual IP: " + mp.getVirtualIp());
                    System.out.println("Local MC addr: " + mp.getLocalMcAddr());
                    System.out.println(SEP);
                    System.out.println("Share the room code with your friend to play together.");
                    System.out.println("Use 'mp invite' to generate an invitation code.");
                } catch (Exception e) {
                    System.err.println("Failed to create room: " + e.getMessage());
                }
                break;
            }
            case "join": {
                if (rest.length < 2) {
                    System.err.println("Usage: mp join <roomCode>");
                    return;
                }
                String code = rest[1];
                System.out.println("Joining room: " + code + " ...");
                try {
                    mp.joinRoomTerracotta(code, status -> {
                        System.out.println("  " + status);
                    }, playerName).get();
                    System.out.println(SEP);
                    System.out.println("[OK] Joined room successfully!");
                    System.out.println("Virtual IP: " + mp.getVirtualIp());
                    System.out.println("Local MC addr: " + mp.getLocalMcAddr());
                    System.out.println(SEP);
                    System.out.println("You can now connect to the host's virtual IP in Minecraft.");
                } catch (Exception e) {
                    System.err.println("Failed to join room: " + e.getMessage());
                }
                break;
            }
            case "leave": {
                System.out.println("Leaving room...");
                mp.leaveRoom();
                System.out.println("[OK] Left room.");
                break;
            }
            case "status": {
                System.out.println(SEP);
                System.out.println("Multiplayer Status:");
                System.out.println(SEP);
                System.out.println("  Backend:     " + mp.getBackend());
                System.out.println("  State:       " + mp.getState());
                System.out.println("  In room:     " + mp.isInRoom());
                System.out.println("  Virtual IP:  " + (mp.getVirtualIp() != null ? mp.getVirtualIp() : "N/A"));
                System.out.println("  Room code:   " + (mp.getCurrentRoomCode() != null ? mp.getCurrentRoomCode() : "N/A"));
                System.out.println("  Local MC:    " + (mp.getLocalMcAddr() != null ? mp.getLocalMcAddr() : "N/A"));
                if (mp.getLastError() != null) {
                    System.out.println("  Last error:  " + mp.getLastError());
                }
                break;
            }
            case "invite": {
                if (!mp.isInRoom()) {
                    System.err.println("Not in a room. Use 'mp create' or 'mp join' first.");
                    return;
                }
                String invite = mp.generateInvitation();
                System.out.println(SEP);
                System.out.println("Invitation code:");
                System.out.println(SEP);
                System.out.println("  " + invite);
                System.out.println(SEP);
                System.out.println("Share this code with your friend to play together.");
                break;
            }
            default:
                System.err.println("Unknown action: " + action + " (supported: create, join, leave, status, invite)");
        }
    }

    // ==================== Update ====================

    private void cmdUpdate(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: update <check|download>");
            return;
        }
        String action = rest[0].toLowerCase();
        SelfUpdater updater = core.selfUpdater();
        switch (action) {
            case "check": {
                System.out.println("Checking for updates...");
                try {
                    SelfUpdater.UpdateInfo info = updater.checkUpdate().get();
                    if (info == null) {
                        System.out.println("[OK] You are running the latest version.");
                    } else {
                        System.out.println(SEP);
                        System.out.println("Update available!");
                        System.out.println(SEP);
                        System.out.println("  Version: " + info.getVersion());
                        long sizeMb = info.getSize() / (1024 * 1024);
                        System.out.println("  Size:    " + sizeMb + " MB");
                        if (info.getNotes() != null && !info.getNotes().isEmpty()) {
                            System.out.println("  Notes:   " + info.getNotes());
                        }
                        System.out.println("\nUse 'update download' to download the update.");
                    }
                } catch (Exception e) {
                    System.err.println("Failed to check for updates: " + e.getMessage());
                }
                break;
            }
            case "download": {
                System.out.println("Checking for updates...");
                try {
                    SelfUpdater.UpdateInfo info = updater.checkUpdate().get();
                    if (info == null) {
                        System.out.println("[OK] You are running the latest version.");
                        return;
                    }
                    System.out.println("Downloading update v" + info.getVersion() + " ...");
                    Path path = updater.downloadUpdate(info, bytes -> {
                        long mb = bytes / (1024 * 1024);
                        System.out.printf("\r  Downloaded: %d MB", mb);
                    }).get();
                    System.out.println();
                    System.out.println("[OK] Update downloaded to: " + path);
                } catch (Exception e) {
                    System.err.println("Failed to download update: " + e.getMessage());
                }
                break;
            }
            default:
                System.err.println("Unknown action: " + action + " (supported: check, download)");
        }
    }

    // ==================== System Info ====================

    private void cmdSysInfo() {
        RuntimeManager rm = core.runtime();
        System.out.println(SEP);
        System.out.println("System Information:");
        System.out.println(SEP);
        System.out.println("  OS:              " + rm.getOsName());
        System.out.println("  OS arch:         " + System.getProperty("os.arch"));
        System.out.println("  Java version:    " + System.getProperty("java.version"));
        System.out.println("  Java home:       " + System.getProperty("java.home"));
        System.out.println("  Total memory:    " + rm.getTotalMemoryMb() + " MB");
        System.out.println("  Available mem:   " + rm.getAvailableMemoryMb() + " MB");
        System.out.println("  Recommended max: " + rm.getRecommendedMaxMemoryMb() + " MB");
        System.out.println("  CPU cores:       " + Runtime.getRuntime().availableProcessors());
        System.out.println(SEP);
    }

    // ==================== Download ====================

    private void cmdDownload(String[] rest) {
        if (rest.length < 2) {
            System.err.println("Usage: download <url> <path>");
            return;
        }
        String url = rest[0];
        Path target = Paths.get(rest[1]);
        System.out.println("Downloading: " + url);
        System.out.println("Target: " + target);
        try {
            DownloadManager dm = core.downloads();
            dm.downloadTo(url, target, bytes -> {
                long mb = bytes / (1024 * 1024);
                System.out.printf("\r  Downloaded: %d MB", mb);
            });
            System.out.println();
            System.out.println("[OK] Download complete: " + target);
        } catch (Exception e) {
            System.err.println("\nDownload failed: " + e.getMessage());
        }
    }

    // ==================== Wiki ====================

    private void cmdWiki(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: wiki <query>");
            return;
        }
        String query = String.join(" ", rest);
        if (!WikiBrowser.isSupported()) {
            System.err.println("Wiki browser is not supported on this platform.");
            System.out.println("Search URL: " + WikiBrowser.minecraftWikiSearchUrl(query));
            return;
        }
        String url = WikiBrowser.minecraftWikiSearchUrl(query);
        System.out.println("Opening in browser: " + url);
        try {
            WikiBrowser.open(url);
            System.out.println("[OK] Opened in browser.");
        } catch (IOException e) {
            System.err.println("Failed to open browser: " + e.getMessage());
            System.out.println("URL: " + url);
        }
    }

    // ==================== Config ====================

    private void cmdConfig(String[] rest) {
        Preferences pref = core.getPreferences();
        if (rest.length == 0) {
            System.out.println(SEP);
            System.out.println("Current Configuration:");
            System.out.println(SEP);
            System.out.println("  Game Settings:");
            System.out.printf("    Memory: %d - %d MB%n", pref.getMinMemoryMb(), pref.getMaxMemoryMb());
            System.out.printf("    GC: %s (Aikar=%s)%n", pref.getGcType(), pref.isUseAikarFlags());
            System.out.printf("    Window: %dx%d%s%n", pref.getGameWindowWidth(), pref.getGameWindowHeight(),
                    pref.isGameFullscreen() ? " fullscreen" : "");
            System.out.printf("    JVM args: %s%n", pref.getCustomJvmArgs().isEmpty() ? "(default)" : pref.getCustomJvmArgs());
            System.out.printf("    Java path: %s%n", pref.getJavaPath().isEmpty() ? "(auto)" : pref.getJavaPath());
            System.out.printf("    Renderer: %s%n", pref.getGameRenderer());
            System.out.println("  Download Settings:");
            System.out.printf("    Mirror: %s%s%n", pref.getMirrorType(),
                    pref.getMirrorType().equals("CUSTOM") ? " (" + pref.getCustomMirrorBase() + ")" : "");
            System.out.printf("    Speed limit: %s%n", pref.getDownloadSpeedLimitKb() == 0 ? "unlimited" : pref.getDownloadSpeedLimitKb() + " KB/s");
            System.out.printf("    Retry: %d | Resume: %s | Chunk threads: %d%n",
                    pref.getDownloadRetryCount(), pref.isEnableResume() ? "on" : "off", pref.getChunkedDownloadThreads());
            System.out.println("  Network:");
            System.out.printf("    Proxy: %s%n", pref.isUseProxy()
                    ? pref.getProxyHost() + ":" + pref.getProxyPort() : "disabled");
            System.out.println("  Multiplayer:");
            System.out.printf("    Backend: %s%n", pref.getMpBackend());
            System.out.println("  Appearance:");
            System.out.printf("    Dark theme: %s | Dynamic color: %s | Language: %s%n",
                    pref.isUseDarkTheme() ? "on" : "off", pref.isDynamicColor() ? "on" : "off", pref.getLanguage());
            System.out.println(SEP);
            System.out.println("Use 'config <key> <value>' to modify, e.g.:");
            System.out.println("  config maxMemory 4096");
            System.out.println("  config mirror BMCLAPI");
            System.out.println("  config proxyHost 127.0.0.1");
            return;
        }
        if (rest.length == 1) {
            String key = rest[0];
            String val = getConfigValue(pref, key);
            if (val != null) {
                System.out.printf("  %s = %s%n", key, val);
            } else {
                System.err.println("Unknown config key: " + key);
            }
            return;
        }
        String key = rest[0];
        String value = rest[1];
        if (setConfigValue(pref, key, value)) {
            System.out.printf("[OK] Set %s = %s%n", key, value);
        } else {
            System.err.println("Cannot set config key: " + key + " (unknown or read-only)");
        }
    }

    private String getConfigValue(Preferences pref, String key) {
        switch (key.toLowerCase()) {
            case "minmemory": case "minmemorymb": return String.valueOf(pref.getMinMemoryMb());
            case "maxmemory": case "maxmemorymb": return String.valueOf(pref.getMaxMemoryMb());
            case "gctype": case "gc": return pref.getGcType();
            case "useaikarflags": case "aikar": return String.valueOf(pref.isUseAikarFlags());
            case "windowwidth": return String.valueOf(pref.getGameWindowWidth());
            case "windowheight": return String.valueOf(pref.getGameWindowHeight());
            case "fullscreen": return String.valueOf(pref.isGameFullscreen());
            case "customjvmargs": case "jvmargs": return pref.getCustomJvmArgs();
            case "javapath": case "java": return pref.getJavaPath();
            case "renderer": return pref.getGameRenderer();
            case "mirror": case "mirrortype": return pref.getMirrorType();
            case "custommirror": case "custommirrorbase": return pref.getCustomMirrorBase();
            case "speedlimit": case "downloadspeedlimitkb": return String.valueOf(pref.getDownloadSpeedLimitKb());
            case "retrycount": return String.valueOf(pref.getDownloadRetryCount());
            case "enableresume": return String.valueOf(pref.isEnableResume());
            case "chunkedthreads": return String.valueOf(pref.getChunkedDownloadThreads());
            case "useproxy": case "proxy": return String.valueOf(pref.isUseProxy());
            case "proxyhost": return pref.getProxyHost();
            case "proxyport": return String.valueOf(pref.getProxyPort());
            case "mpbackend": return pref.getMpBackend();
            case "darktheme": return String.valueOf(pref.isUseDarkTheme());
            case "dynamiccolor": return String.valueOf(pref.isDynamicColor());
            case "language": case "lang": return pref.getLanguage();
            default: return null;
        }
    }

    private boolean setConfigValue(Preferences pref, String key, String value) {
        try {
            switch (key.toLowerCase()) {
                case "minmemory": case "minmemorymb": pref.setMinMemoryMb(Integer.parseInt(value)); return true;
                case "maxmemory": case "maxmemorymb": pref.setMaxMemoryMb(Integer.parseInt(value)); return true;
                case "gctype": case "gc": pref.setGcType(value); return true;
                case "useaikarflags": case "aikar": pref.setUseAikarFlags(Boolean.parseBoolean(value)); return true;
                case "windowwidth": pref.setGameWindowWidth(Integer.parseInt(value)); return true;
                case "windowheight": pref.setGameWindowHeight(Integer.parseInt(value)); return true;
                case "fullscreen": pref.setGameFullscreen(Boolean.parseBoolean(value)); return true;
                case "customjvmargs": case "jvmargs": pref.setCustomJvmArgs(value); return true;
                case "javapath": case "java": pref.setJavaPath(value); return true;
                case "renderer": pref.setGameRenderer(value); return true;
                case "mirror": case "mirrortype": pref.setMirrorType(value); return true;
                case "custommirror": case "custommirrorbase": pref.setCustomMirrorBase(value); return true;
                case "speedlimit": case "downloadspeedlimitkb": pref.setDownloadSpeedLimitKb(Integer.parseInt(value)); return true;
                case "retrycount": pref.setDownloadRetryCount(Integer.parseInt(value)); return true;
                case "enableresume": pref.setEnableResume(Boolean.parseBoolean(value)); return true;
                case "chunkedthreads": pref.setChunkedDownloadThreads(Integer.parseInt(value)); return true;
                case "useproxy": case "proxy": pref.setUseProxy(Boolean.parseBoolean(value)); return true;
                case "proxyhost": pref.setProxyHost(value); return true;
                case "proxyport": pref.setProxyPort(Integer.parseInt(value)); return true;
                case "mpbackend": pref.setMpBackend(value); return true;
                case "darktheme": pref.setUseDarkTheme(Boolean.parseBoolean(value)); return true;
                case "dynamiccolor": pref.setDynamicColor(Boolean.parseBoolean(value)); return true;
                case "language": case "lang": pref.setLanguage(value); return true;
                default: return false;
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid number format: " + value);
            return false;
        }
    }

    // ==================== Plugin Management ====================

    private void cmdPlugin(String[] rest) {
        if (rest.length == 0) {
            cmdPluginHelp();
            return;
        }
        String action = rest[0].toLowerCase();
        switch (action) {
            case "list": case "ls": cmdPluginList(); break;
            case "install": cmdPluginInstall(rest); break;
            case "uninstall": case "remove": case "rm": cmdPluginUninstall(rest); break;
            case "enable": cmdPluginEnable(rest); break;
            case "disable": cmdPluginDisable(rest); break;
            case "info": cmdPluginInfo(rest); break;
            case "reload": cmdPluginReload(rest); break;
            case "discover": cmdPluginDiscover(); break;
            case "package": case "pkg": cmdPluginPackage(rest); break;
            case "package-info": case "pkginfo": cmdPluginPackageInfo(rest); break;
            default:
                System.err.println("Unknown action: " + action + " (supported: list, install, uninstall, enable, disable, info, reload, discover, package, package-info)");
        }
    }

    private void cmdPluginHelp() {
        System.out.println(SEP);
        System.out.println("Plugin Management Commands:");
        System.out.println(SEP);
        System.out.println("  plugin list                    List all loaded plugins");
        System.out.println("  plugin install <file|url>      Install a plugin from JAR file or URL");
        System.out.println("  plugin package <file|url>      Install a plugin from .ppk package");
        System.out.println("  plugin package-info <file>     Inspect a .ppk package without installing");
        System.out.println("  plugin uninstall <id>          Uninstall a plugin");
        System.out.println("  plugin enable <id>             Enable a plugin");
        System.out.println("  plugin disable <id>            Disable a plugin");
        System.out.println("  plugin info <id>               Show detailed plugin info");
        System.out.println("  plugin reload <id>             Reload a plugin (disable + load + enable)");
        System.out.println("  plugin discover                Scan plugins directory and load all");
        System.out.println(SEP);
        System.out.println("Plugin formats:");
        System.out.println("  JAR (.jar): single-file plugin with META-INF/pmcl-plugin.properties");
        System.out.println("  PPK (.ppk): multi-file package (ZIP) with plugin.xml manifest,");
        System.out.println("              src/kt/*.kt (Kotlin), src/java/*.java (Java helper),");
        System.out.println("              classes/ (compiled), lib/*.jar, resources/");
        System.out.println("  Main class must implement com.pmcl.plugin.PmclPlugin");
    }

    private void cmdPluginList() {
        PluginManager pm = core.plugins();
        List<PluginManager.PluginEntry> plugins = pm.getLoadedPlugins();
        if (plugins.isEmpty()) {
            System.out.println("No plugins loaded.");
            System.out.println("Use 'plugin install <file|url>' to install a plugin.");
            System.out.println("Use 'plugin discover' to scan ~/.pmcl/plugins/ directory.");
            return;
        }
        System.out.println(SEP);
        System.out.printf("Loaded plugins (%d):%n", plugins.size());
        System.out.println(SEP);
        for (PluginManager.PluginEntry entry : plugins) {
            String stateIcon = switch (entry.state) {
                case ENABLED -> "[ENABLED]";
                case LOADED -> "[LOADED]";
                case DISABLED -> "[DISABLED]";
                case FAILED -> "[FAILED]";
            };
            System.out.printf("  %-12s %-25s v%-10s by %s%n",
                    stateIcon, entry.info.getName(), entry.info.getVersion(), entry.info.getAuthor());
            System.out.printf("  %14s id: %-20s api: %s%n", "", entry.info.getId(), entry.info.getApiVersion());
        }
        // Show custom commands from plugins
        List<PluginManager.RegisteredCommand> cmds = pm.getCustomCommands();
        if (!cmds.isEmpty()) {
            System.out.println(SEP);
            System.out.printf("Custom commands (%d):%n", cmds.size());
            for (PluginManager.RegisteredCommand cmd : cmds) {
                System.out.printf("  plugin:%s:%-15s — %s%n", cmd.pluginId, cmd.name, cmd.description);
            }
        }
    }

    private void cmdPluginInstall(String[] rest) {
        if (rest.length < 2) {
            System.err.println("Usage: plugin install <file|url>");
            System.err.println("  file: Path to a local JAR file");
            System.err.println("  url:  HTTP(S) URL to download a JAR from");
            return;
        }
        String source = rest[1];
        PluginManager pm = core.plugins();
        try {
            PluginInfo info;
            if (source.startsWith("http://") || source.startsWith("https://")) {
                System.out.println("Installing plugin from URL: " + source);
                info = pm.installFromUrl(source);
            } else {
                Path jarPath = Paths.get(source);
                if (!Files.exists(jarPath)) {
                    System.err.println("File not found: " + source);
                    return;
                }
                System.out.println("Installing plugin from file: " + source);
                info = pm.installFromPath(jarPath);
            }
            System.out.println(SEP);
            System.out.println("[OK] Plugin installed and enabled!");
            System.out.println(SEP);
            System.out.println("  ID:          " + info.getId());
            System.out.println("  Name:        " + info.getName());
            System.out.println("  Version:     " + info.getVersion());
            System.out.println("  Author:      " + info.getAuthor());
            System.out.println("  Description: " + info.getDescription());
        } catch (Exception e) {
            System.err.println("Installation failed: " + e.getMessage());
        }
    }

    private void cmdPluginUninstall(String[] rest) {
        if (rest.length < 2) {
            System.err.println("Usage: plugin uninstall <id>");
            return;
        }
        String id = rest[1];
        PluginManager pm = core.plugins();
        if (!pm.isLoaded(id)) {
            System.err.println("Plugin not loaded: " + id);
            return;
        }
        try {
            pm.uninstallPlugin(id);
            System.out.println("[OK] Plugin uninstalled: " + id);
        } catch (Exception e) {
            System.err.println("Uninstall failed: " + e.getMessage());
        }
    }

    private void cmdPluginEnable(String[] rest) {
        if (rest.length < 2) {
            System.err.println("Usage: plugin enable <id>");
            return;
        }
        String id = rest[1];
        PluginManager pm = core.plugins();
        if (!pm.isLoaded(id)) {
            System.err.println("Plugin not loaded: " + id);
            return;
        }
        pm.enablePlugin(id);
        System.out.println("[OK] Plugin enabled: " + id);
    }

    private void cmdPluginDisable(String[] rest) {
        if (rest.length < 2) {
            System.err.println("Usage: plugin disable <id>");
            return;
        }
        String id = rest[1];
        PluginManager pm = core.plugins();
        if (!pm.isLoaded(id)) {
            System.err.println("Plugin not loaded: " + id);
            return;
        }
        pm.disablePlugin(id);
        System.out.println("[OK] Plugin disabled: " + id);
    }

    private void cmdPluginInfo(String[] rest) {
        if (rest.length < 2) {
            System.err.println("Usage: plugin info <id>");
            return;
        }
        String id = rest[1];
        PluginManager pm = core.plugins();
        PluginManager.PluginEntry entry = pm.getPlugin(id);
        if (entry == null) {
            System.err.println("Plugin not loaded: " + id);
            return;
        }
        PluginInfo info = entry.info;
        System.out.println(SEP);
        System.out.println("Plugin Details:");
        System.out.println(SEP);
        System.out.println("  ID:           " + info.getId());
        System.out.println("  Name:         " + info.getName());
        System.out.println("  Version:      " + info.getVersion());
        System.out.println("  Author:       " + info.getAuthor());
        System.out.println("  Description:  " + info.getDescription());
        System.out.println("  API version:  " + info.getApiVersion());
        System.out.println("  Main class:   " + info.getMainClass());
        System.out.println("  State:        " + entry.state);
        System.out.println("  JAR path:     " + entry.jarPath);
        System.out.println("  Data dir:     " + pm.getPlugin(id).context.getDataDir());
        if (!info.getDependencies().isEmpty()) {
            System.out.println("  Dependencies: " + String.join(", ", info.getDependencies()));
        }
        if (!info.getLicense().isEmpty()) {
            System.out.println("  License:      " + info.getLicense());
        }
        if (!info.getWebsite().isEmpty()) {
            System.out.println("  Website:      " + info.getWebsite());
        }

        // Show registered extensions
        List<PluginManager.RegisteredCommand> cmds = pm.getCustomCommands();
        List<PluginManager.RegisteredCommand> myCmds = cmds.stream()
                .filter(c -> c.pluginId.equals(id))
                .toList();
        if (!myCmds.isEmpty()) {
            System.out.println(SEP);
            System.out.println("  Commands:");
            for (PluginManager.RegisteredCommand c : myCmds) {
                System.out.printf("    plugin:%s:%s — %s%n", c.pluginId, c.name, c.description);
            }
        }
        List<PluginManager.RegisteredPage> pages = pm.getCustomPages();
        List<PluginManager.RegisteredPage> myPages = pages.stream()
                .filter(p -> p.pluginId.equals(id))
                .toList();
        if (!myPages.isEmpty()) {
            System.out.println(SEP);
            System.out.println("  Pages:");
            for (PluginManager.RegisteredPage p : myPages) {
                System.out.printf("    [%s] %s%n", p.id, p.title);
            }
        }
    }

    private void cmdPluginReload(String[] rest) {
        if (rest.length < 2) {
            System.err.println("Usage: plugin reload <id>");
            return;
        }
        String id = rest[1];
        PluginManager pm = core.plugins();
        if (!pm.isLoaded(id)) {
            System.err.println("Plugin not loaded: " + id);
            return;
        }
        try {
            System.out.println("Reloading plugin: " + id + " ...");
            pm.reloadPlugin(id);
            System.out.println("[OK] Plugin reloaded: " + id);
        } catch (Exception e) {
            System.err.println("Reload failed: " + e.getMessage());
        }
    }

    private void cmdPluginPackage(String[] rest) {
        if (rest.length < 2) {
            System.err.println("Usage: plugin package <file|url>");
            System.err.println("  file: Path to a local .ppk file");
            System.err.println("  url:  HTTP(S) URL to download a .ppk from");
            return;
        }
        String source = rest[1];
        PluginManager pm = core.plugins();
        try {
            System.out.println("Installing plugin package from: " + source);
            com.pmcl.plugin.PluginInfo info;
            if (source.startsWith("http://") || source.startsWith("https://")) {
                info = pm.installFromPackageUrl(source);
            } else {
                info = pm.installFromPackage(Paths.get(source));
            }
            System.out.println("[OK] Plugin package installed and enabled:");
            System.out.println("  ID:          " + info.getId());
            System.out.println("  Name:        " + info.getName());
            System.out.println("  Version:     " + info.getVersion());
            System.out.println("  Author:      " + info.getAuthor());
            System.out.println("  Description: " + info.getDescription());
            System.out.println("  Main class:  " + info.getMainClass());
            if (!info.getDependencies().isEmpty()) {
                System.out.println("  Dependencies: " + String.join(", ", info.getDependencies()));
            }
        } catch (Exception e) {
            System.err.println("Failed to install plugin package: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void cmdPluginPackageInfo(String[] rest) {
        if (rest.length < 2) {
            System.err.println("Usage: plugin package-info <file>");
            System.err.println("  Inspects a .ppk package without installing it.");
            return;
        }
        String filePath = rest[1];
        try {
            com.pmcl.plugin.PluginPackageParser.PluginPackage pkg =
                    com.pmcl.plugin.PluginPackageParser.parse(Paths.get(filePath));
            com.pmcl.plugin.PluginInfo info = pkg.getInfo();
            System.out.println(SEP);
            System.out.println("Plugin Package Info");
            System.out.println(SEP);
            System.out.println("Format version: " + pkg.getFormatVersion());
            System.out.println();
            System.out.println("Identity:");
            System.out.println("  ID:          " + info.getId());
            System.out.println("  Name:        " + info.getName());
            System.out.println("  Version:     " + info.getVersion());
            System.out.println("  Author:      " + info.getAuthor());
            System.out.println("  Description: " + info.getDescription());
            System.out.println("  API version: " + info.getApiVersion());
            System.out.println("  Main class:  " + info.getMainClass());
            if (!info.getWebsite().isEmpty()) {
                System.out.println("  Website:     " + info.getWebsite());
            }
            if (!info.getLicense().isEmpty()) {
                System.out.println("  License:     " + info.getLicense());
            }
            System.out.println();
            System.out.println("Sources (" + pkg.getSources().size() + " total):");
            for (com.pmcl.plugin.PluginPackageParser.SourceFile src : pkg.getSources()) {
                String tag = src.isMain() ? " [MAIN]" : "";
                String lang = src.getLanguage() == com.pmcl.plugin.PluginPackageParser.SourceFile.Language.KOTLIN ? "KT" : "JAVA";
                System.out.println("  [" + lang + "] " + src.getPath() + tag);
            }
            System.out.println();
            if (!pkg.getDependencies().isEmpty()) {
                System.out.println("Dependencies (" + pkg.getDependencies().size() + "):");
                for (com.pmcl.plugin.PluginPackageParser.PackageDependency dep : pkg.getDependencies()) {
                    System.out.println("  " + dep.getId() + " (version: " + dep.getVersionSpec() + ")");
                }
                System.out.println();
            }
            if (!pkg.getLibraries().isEmpty()) {
                System.out.println("Libraries (" + pkg.getLibraries().size() + "):");
                for (com.pmcl.plugin.PluginPackageParser.LibraryRef lib : pkg.getLibraries()) {
                    System.out.println("  " + lib.getPath());
                }
                System.out.println();
            }
            if (!pkg.getResources().isEmpty()) {
                System.out.println("Resources (" + pkg.getResources().size() + "):");
                for (com.pmcl.plugin.PluginPackageParser.ResourceRef res : pkg.getResources()) {
                    System.out.println("  " + res.getPath());
                }
                System.out.println();
            }
            System.out.println("Version History (" + pkg.getVersions().size() + "):");
            for (com.pmcl.plugin.PluginPackageParser.VersionEntry v : pkg.getVersions()) {
                System.out.println("  " + v.getNumber() + " (" + v.getDate() + ") by " + v.getAuthor());
                if (!v.getChangelog().isEmpty()) {
                    System.out.println("    " + v.getChangelog());
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to read package info: " + e.getMessage());
        }
    }

    private void cmdPluginDiscover() {
        PluginManager pm = core.plugins();
        System.out.println("Scanning plugins directory...");
        int before = pm.getLoadedPlugins().size();
        pm.discoverAndLoadAll();
        int after = pm.getLoadedPlugins().size();
        int loaded = after - before;
        if (loaded == 0) {
            System.out.println("No new plugins found.");
            if (after > 0) {
                System.out.println("(" + after + " plugins already loaded)");
            }
        } else {
            System.out.printf("[OK] Loaded %d new plugin(s). Total: %d%n", loaded, after);
        }
    }

    // ==================== Cache ====================

    private void cmdCache(String[] rest) {
        if (rest.length > 0 && rest[0].equalsIgnoreCase("clear")) {
            try {
                com.pmcl.core.cache.DataCache.clearAll();
                System.out.println("[OK] All cache cleared.");
            } catch (Exception e) {
                System.err.println("Failed to clear cache: " + e.getMessage());
            }
            return;
        }
        System.out.println(SEP);
        System.out.println("Cache Information:");
        System.out.println(SEP);
        java.nio.file.Path cacheDir = com.pmcl.core.cache.DataCache.getCacheDir();
        System.out.println("  Cache directory: " + cacheDir);
        try {
            if (java.nio.file.Files.exists(cacheDir)) {
                long[] size = {0};
                long[] count = {0};
                try (var stream = java.nio.file.Files.walk(cacheDir)) {
                    stream.filter(java.nio.file.Files::isRegularFile)
                        .forEach(p -> {
                            try {
                                size[0] += java.nio.file.Files.size(p);
                                count[0]++;
                            } catch (java.io.IOException ignored) {}
                        });
                }
                System.out.printf("  Files: %d%n", count[0]);
                System.out.printf("  Total size: %s%n", formatSize(size[0]));
            } else {
                System.out.println("  (cache directory does not exist)");
            }
        } catch (Exception e) {
            System.out.println("  (unable to calculate size: " + e.getMessage() + ")");
        }
        System.out.println();
        System.out.println("Use 'cache clear' to clear all cached data.");
    }

    // ==================== Game Log ====================

    private void cmdLog(String[] rest) {
        int lines = 50;
        if (rest.length > 0) {
            try {
                lines = Integer.parseInt(rest[0]);
                if (lines <= 0) lines = 50;
                if (lines > 2000) lines = 2000;
            } catch (NumberFormatException e) {
                System.err.println("Invalid number: " + rest[0]);
                return;
            }
        }
        java.nio.file.Path logFile = core.getConfig().getWorkDir().resolve("logs").resolve("latest.log");
        if (!java.nio.file.Files.exists(logFile)) {
            System.out.println("No game log found at: " + logFile);
            System.out.println("Log file is created when a game is launched.");
            return;
        }
        try {
            List<String> allLines = java.nio.file.Files.readAllLines(logFile);
            int total = allLines.size();
            int start = Math.max(0, total - lines);
            List<String> tail = allLines.subList(start, total);
            System.out.println(SEP);
            System.out.printf("Game Log (last %d of %d lines):%n", tail.size(), total);
            System.out.println(SEP);
            for (String l : tail) {
                System.out.println(l);
            }
        } catch (Exception e) {
            System.err.println("Failed to read log: " + e.getMessage());
        }
    }

    // ==================== Skin ====================

    private void cmdSkin() {
        if (currentAccount == null) {
            System.out.println("Not logged in. Use 'login offline <name>' or 'login ms' first.");
            return;
        }
        System.out.println(SEP);
        System.out.println("Account Skin Info:");
        System.out.println(SEP);
        System.out.println("  Username: " + currentAccount.getUsername());
        System.out.println("  UUID: " + currentAccount.getUuid());
        System.out.println("  Type: " + currentAccount.getType());
        String skinUrl = currentAccount.getSkinUrl();
        String skinModel = currentAccount.getSkinModel();
        System.out.println("  Skin URL: " + (skinUrl == null || skinUrl.isEmpty() ? "(none)" : skinUrl));
        System.out.println("  Skin model: " + (skinModel == null || skinModel.isEmpty() ? "classic" : skinModel));
        String avatar = currentAccount.getAvatarUrl();
        String body = currentAccount.getBodyRenderUrl();
        if (avatar != null && !avatar.isEmpty()) {
            System.out.println("  Avatar URL: " + avatar);
        }
        if (body != null && !body.isEmpty()) {
            System.out.println("  Body render URL: " + body);
        }
    }

    // ==================== Version Info ====================

    private void cmdVersion() {
        System.out.println(SEP);
        System.out.println("PMCL — Minecraft Launcher");
        System.out.println(SEP);
        System.out.println("  Java version: " + System.getProperty("java.version"));
        System.out.println("  Java vendor: " + System.getProperty("java.vendor"));
        System.out.println("  OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("  Working directory: " + core.getConfig().getWorkDir());
        System.out.println("  JAR location: " + getJarLocation());
    }

    private String getJarLocation() {
        try {
            java.security.CodeSource cs = PmclCli.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                return cs.getLocation().getPath();
            }
        } catch (Exception ignored) {}
        return "(unknown)";
    }

    // ==================== Open Directory ====================

    private void cmdOpen(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: open <dir>");
            System.err.println("  Subdirs: home, versions, mods, logs, cache, screenshots, plugins");
            System.err.println("  Or use an absolute/relative path");
            return;
        }
        java.nio.file.Path target;
        String dir = rest[0].toLowerCase();
        java.nio.file.Path workDir = core.getConfig().getWorkDir();
        switch (dir) {
            case "home": case ".":
                target = workDir; break;
            case "versions": case "version":
                target = workDir.resolve("versions"); break;
            case "mods": case "mod":
                target = workDir.resolve("mods"); break;
            case "logs": case "log":
                target = workDir.resolve("logs"); break;
            case "cache":
                target = com.pmcl.core.cache.DataCache.getCacheDir(); break;
            case "screenshots": case "shots":
                target = workDir.resolve("screenshots"); break;
            case "plugins": case "plugin":
                target = java.nio.file.Paths.get(System.getProperty("user.home"), ".pmcl", "plugins"); break;
            default:
                target = java.nio.file.Paths.get(rest[0]);
        }
        try {
            java.nio.file.Files.createDirectories(target);
            if (java.awt.Desktop.isDesktopSupported() &&
                java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(target.toFile());
                System.out.println("[OK] Opened: " + target);
            } else {
                System.err.println("Desktop OPEN action not supported on this platform.");
            }
        } catch (Exception e) {
            System.err.println("Failed to open: " + e.getMessage());
        }
    }

    // ==================== Open URL ====================

    private void cmdUrl(String[] rest) {
        if (rest.length == 0) {
            System.err.println("Usage: url <url>");
            return;
        }
        String url = rest[0];
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        try {
            com.pmcl.core.web.WikiBrowser.open(url);
            System.out.println("[OK] Opened in browser: " + url);
        } catch (Exception e) {
            System.err.println("Failed to open URL: " + e.getMessage());
        }
    }

    // ==================== Theme Switch ====================

    private void cmdTheme(String[] rest) {
        if (rest.length == 0) {
            boolean dark = core.getPreferences().isUseDarkTheme();
            System.out.println("Current theme: " + (dark ? "dark" : "light"));
            System.out.println("Use 'theme dark' or 'theme light' to switch.");
            return;
        }
        String mode = rest[0].toLowerCase();
        Preferences pref = core.getPreferences();
        switch (mode) {
            case "dark":
                pref.setUseDarkTheme(true);
                System.out.println("[OK] Theme set to dark (restart UI to apply).");
                break;
            case "light":
                pref.setUseDarkTheme(false);
                System.out.println("[OK] Theme set to light (restart UI to apply).");
                break;
            default:
                System.err.println("Unknown theme: " + mode + " (use 'dark' or 'light')");
        }
    }

    // ==================== Utility ====================

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
