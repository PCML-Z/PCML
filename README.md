<p align="center">
  <a href="README.zh-CN.md">中文</a> · <a href="README.md">English</a>
</p>

# PMCL

<p align="center">
  <img src="logo-pmcl-pixel.png" alt="PMCL" width="512">
</p>

<p align="center">
  <img src="repo-stats.png" alt="PMCL repo stats">
</p>

**PMCL** (Personal Minecraft Custom Launcher) is a cross-platform Minecraft launcher built on Compose Desktop, using the Material 3 design language, with a built-in plugin system, multiplayer support, mod management, and the ability to embed the HMCL JavaFX UI in-window.

## Features

### Launcher Core
- **Compose Desktop UI** — Material 3 design, smooth animations and scrolling
- **Version install & launch** — supports Minecraft versions from Alpha to the latest release
- **Microsoft account auth** — OAuth 2.0 Device Code flow login
- **Java runtime management** — auto-detect / download Java 8 / 17 / 21; Apple Silicon uses the x86_64 compatibility layer
- **Cross-platform** — macOS (arm64 / x86_64), Windows (x64), Linux
- **GitHub Release sync updates** — polls the GitHub Releases API directly and proactively notifies you of new versions (see below)

### Content Management
- **Mod management** — Modrinth / CurseForge marketplace integration with conflict detection
- **Modpack support** — auto-scans the `mods` directory of modpack versions
- **Worlds & screenshots** — merges PMCL / HMCL / official launcher directories with deduplicated display
- **Data packs / shader packs / resource packs** — one-click install and management

### Multiplayer
- **Multiple backends** — Terracotta / EasyTier / ConnectX
- **Room system** — create / join rooms, state-machine management, unique room-code guarantee
- **Relay connection** — stable relay servers with low packet loss

### Plugin System
- **.ppk package format** — strictly specified ZIP package containing a `plugin.xml` manifest
- **Multi-language source** — Kotlin (main logic) + Java (helpers) + XML (info)
- **13 validation rules** — path prefix, file extension, unique main marker, version matching, etc.
- **Plugin capabilities** — register commands, GUI pages, launch hooks, event listeners
- **Secure by default** — command-name blacklist (56 reserved words), zip-slip protection

### Terminal Mode
- **35 commands** — version management, mod operations, multiplayer, Java management, Wiki search, and more
- **Fully English UI** — command history (↑ / ↓), colored output, auto-scroll
- **GUI terminal** — a full terminal experience embedded in the sidebar

### JavaFX UI Embed Plugin
- **JavaFX in Compose** — embeds the JavaFX UI into Compose Desktop via `JFXPanel` + `SwingPanel`
- **Scene Stealing** — reflectively calls `Launcher.start(stage)`, intercepts `show()` to steal the Scene

## Project Structure

```
PMCL/
├── core/                    # Core logic (Java)
│   └── src/main/java/com/pmcl/core/
│       ├── auth/            # Microsoft account authentication
│       ├── download/        # Download manager (curl fallback supported)
│       ├── install/         # Version installer
│       ├── launch/          # Launch manager (Java arch detection)
│       ├── market/          # Modrinth / CurseForge client
│       ├── mods/            # Mod scanning and management
│       ├── multiplayer/     # Multiplayer (Terracotta / EasyTier / ConnectX)
│       ├── plugin/          # Plugin package builder
│       ├── update/          # Self-update + GitHub Release sync (GitHubReleaseSyncChecker)
│       └── ...
├── ui/                      # Compose Desktop UI (Kotlin)
│   └── src/commonMain/kotlin/com/pmcl/ui/
│       ├── page/            # 22 pages (launch / news / multiplayer / download / content / saves ...)
│       ├── animation/       # Smooth scroll and transition animations
│       ├── theme/           # Material 3 theme
│       └── App.kt           # Main app entry point
├── cli/                     # Command-line interface (Java, 35 commands)
├── plugin-api/              # Plugin API (Kotlin)
│   └── src/main/kotlin/com/pmcl/plugin/
│       ├── PmclPlugin.kt    # Plugin interface
│       ├── PluginContext.kt # Plugin context (register command / page / hook)
│       └── PluginPackageParser.kt  # .ppk parser (13 rules)
├── hmcl-plugin/             # Embed plugin
│   ├── lib/                 # JavaFX 25 jars
│   └── src/main/kotlin/com/pmcl/hmcl/
│       ├── HmclEmbedder.kt  # JavaFX init + Scene stealing
│       └── HmclPageContent.kt  # Compose UI + SwingPanel
├── custom-downloader-plugin/  # Custom downloader plugin example
├── test-plugin/             # Single-JAR plugin example
├── test-plugin-package/     # .ppk package plugin example
└── settings.gradle.kts      # 8 submodules
```

## Core Code Examples

The snippets below are **real code that exists in the launcher**, showing how the four key stages are implemented. All paths are relative to the repository root.

### 1. Core Initialization

The entry point of the launcher core is `core/.../LauncherCore.java`. On construction it creates and wires up every subsystem at once, and uses `initOptional` so that optional modules (plugins, multiplayer, i18n, etc.) degrade gracefully instead of aborting startup when they fail:

```java
// core/src/main/java/com/pmcl/core/LauncherCore.java
public LauncherCore(LauncherConfig config) {
    this.config = config;
    // Preferences and working directory (~/.pmcl)
    this.preferences = new Preferences(
            Paths.get(System.getProperty("user.home"), ".pmcl", "preferences.json"));
    this.instanceManager = new InstanceManager(config);

    // Core service wiring
    this.versionManager   = new VersionManager(config, preferences);
    this.downloadManager  = new DownloadManager(config, preferences);
    this.authService      = new AuthService();
    this.runtimeManager   = new RuntimeManager();
    this.launchManager    = new LaunchManager(config, preferences);
    this.versionInstaller = new VersionInstaller(config, versionManager, downloadManager);
    // …… mod / modpack / content management / integrity check / crash analysis and 20+ subsystems

    // Optional subsystems: degrade gracefully, don't abort the launcher
    this.pluginManager = initOptional("PluginManager", () -> new PluginManager(this));

    // Inject the plugin manager into launch / multiplayer / download queue for hooks and events
    if (this.pluginManager != null) {
        this.launchManager.setPluginManager(this.pluginManager);
        this.multiplayerManager.setPluginManager(this.pluginManager);
        this.downloadQueue.setPluginManager(this.pluginManager);
    }
    // Apply persisted language preference
    applyLanguage(preferences.getLanguage());
}

// Working directory and derived dirs (versions / libraries / assets / runtimes) are resolved centrally by LauncherConfig
// core/src/main/java/com/pmcl/core/LauncherConfig.java
public LauncherConfig() {
    this(Paths.get(System.getProperty("user.home"), ".pmcl"));
}
public Path getVersionsDir()  { return workDir.resolve("versions"); }
public Path getAssetsDir()    { return workDir.resolve("assets"); }
public Path getRuntimesDir()  { return workDir.resolve("runtimes"); }
```

The UI layer (Compose) holds a `LauncherCore` instance in `LauncherViewModel` and, in its `init` block, injects optional modules, registers listeners, and kicks off the update check:

```kotlin
// ui/src/commonMain/kotlin/com/pmcl/ui/viewmodel/LauncherViewModel.kt
class LauncherViewModel {
    val core = LauncherCore()          // triggers all subsystem initialization

    init {
        // Inject the video module's main-menu background video processor (avoids a core↔video cyclic dependency)
        core.profileBuilder().setMenuBackgroundProvider(com.pmcl.video.MenuBackgroundManager())
        setupGithubSyncListener()       // register the update-sync listener
        checkUpdateOnStartup()          // check for updates every time it opens
    }
}
```

### 2. Java Detection

Detecting available Java runtimes is handled by `core/.../launch/JavaRuntimeFinder.java`. It searches by the priority "bundled runtimes dir → common install paths → JAVA_HOME → PATH", and parses the major version by forking `java -version`:

```java
// core/src/main/java/com/pmcl/core/launch/JavaRuntimeFinder.java
public static String findJavaExecutable(Path runtimesDir, int requiredMajorVersion,
                                        boolean preferLegacyTranslation) {
    // 1. Prefer the launcher's own downloaded runtimes dir
    if (runtimesDir != null) {
        String best = pickBestJavaForVersion(scanRuntimes(runtimesDir), requiredMajorVersion, preferLegacyTranslation);
        if (best != null) return best;
    }
    // 2. Common install paths (enumerate macOS / Windows / Linux, incl. LoongArch and RISC-V paths)
    List<String> candidates = new ArrayList<>();
    if (os.contains("mac")) {
        candidates.add("/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home");
        candidates.add("/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home");
        // ……
    }
    String best = pickBestJavaForVersion(candidates, requiredMajorVersion, preferLegacyTranslation);
    if (best != null) return best;

    // 3. JAVA_HOME environment variable
    String javaHome = System.getenv("JAVA_HOME");
    if (javaHome != null) { String exe = resolveJava(javaHome); if (exe != null) return exe; }

    // 4. java on PATH (fallback)
    // 5. If nothing is found, return null and let the caller guide the user to install
    return null;
}

// Parse the major version by forking java -version (results cached by path to avoid repeated process spawns)
public static Integer getMajorVersion(String javaExe) {
    Integer cached = MAJOR_VERSION_CACHE.get(javaExe);
    if (cached != null) return cached;
    Integer result = computeMajorVersion(javaExe);     // regex "version \"21.0.1\"" → 21
    if (result != null) MAJOR_VERSION_CACHE.put(javaExe, result);
    return result;
}
```

### 3. Game Scanning

Scanning locally installed versions lives in `core/.../version/VersionManager.java`. It iterates each subdirectory under `versions/`, parses `version.json` to extract `inheritsFrom` / `mainClass` / `assets`, and merges the PMCL directory, the system default directories (e.g. `~/Library/Application Support/minecraft/versions`) and user-custom root directories:

```java
// core/src/main/java/com/pmcl/core/version/VersionManager.java
public List<LocalVersionInfo> scanVersionsDir(Path versionsDir,
                                              Consumer<ScanProgress> onProgress) {
    if (!Files.isDirectory(versionsDir)) return Collections.emptyList();
    List<Path> subDirs = new ArrayList<>();
    try (var stream = Files.list(versionsDir)) {
        stream.filter(Files::isDirectory).forEach(subDirs::add);
    }
    // ……
    for (Path p : subDirs) {
        String id = p.getFileName().toString();
        if (VersionStaging.isTransientDirName(id)) continue;   // skip .staging / .bak
        Path json = p.resolve(id + ".json");
        boolean hasJson = Files.exists(json);
        boolean hasJar  = Files.exists(p.resolve(id + ".jar"));
        String inheritsFrom = null, mainClass = null, assets = null;
        if (hasJson) {
            JsonObject root = JsonParser.parseString(Files.readString(json, UTF_8)).getAsJsonObject();
            if (root.has("inheritsFrom")) inheritsFrom = root.get("inheritsFrom").getAsString();
            if (root.has("mainClass"))    mainClass    = root.get("mainClass").getAsString();
            if (root.has("assets"))       assets       = root.get("assets").getAsString();
        }
        result.add(new LocalVersionInfo(id, mtime, hasJar, hasJson, inheritsFrom, mainClass, assets));
        if (onProgress != null) onProgress.accept(new ScanProgress(dirName, ++scanned, total, id));
    }
    result.sort((a, b) -> Long.compare(b.getLastModified(), a.getLastModified())); // newest first
    return result;
}

// Merge .pmcl/versions + system default dirs + user custom root dirs, dedupe across dirs
public List<LocalVersionInfo> scanAllLocalVersions(Consumer<ScanProgress> onProgress) {
    List<Path> dirs = getAllScanDirs();
    // First pass: scan per dir → second pass: merge + dedupe + accumulate progress callback
}
```

### 4. Resource Completion

The version installer `core/.../install/VersionInstaller.java` is responsible for completing the game's `client.jar`, `libraries` (including natives) and `assets`. Resource integrity is verified by `AssetIndex.parse` — if any asset entry lacks a valid SHA-1, installation is refused, avoiding "installed but missing resources":

```java
// core/src/main/java/com/pmcl/core/install/VersionInstaller.java  (doInstall excerpt)
// 5. Asset index (if assets are declared, the download must succeed — no silent skipping)
if (vj.getAssets() != null && !vj.getAssets().isEmpty()) {
    String assetIndexUrl   = resolveAssetIndexUrl(vj);
    String assetIndexSha1  = resolveAssetIndexSha1(vj);
    if (assetIndexSha1 == null || assetIndexSha1.isBlank())
        throw new IOException("assetIndex missing sha1, refusing integrity-less index download");
    Path idxPath = config.getAssetsDir().resolve("indexes").resolve(vj.getAssets() + ".json");
    downloadManager.downloadToVerified(assetIndexUrl, idxPath, assetIndexSha1, null);
    AssetIndex idx = AssetIndex.parse(Files.readString(idxPath, UTF_8));
    for (AssetIndex.Asset a : idx.getAssets().values()) {
        tasks.add(new DownloadTask(                 // add each asset to the download queue
                RESOURCE_BASE + a.getPath(), a.getHash(), a.getSize(),
                "assets/objects/" + a.getPath()));
    }
}
// 6. Batch download (libraries + natives + assets), with .part resume and SHA verification
downloadManager.downloadAll(tasks, /*onFile*/ file -> {}, /*onProgress*/ bytes -> { /*...*/ }).join();
// 7. Extract natives → 8. Atomically promote staging → versions/{id}
```

```java
// core/src/main/java/com/pmcl/core/install/AssetIndex.java
// Resource integrity check: any object missing a hash or with a non-valid SHA-1 fails outright
public static AssetIndex parse(String json) throws IOException {
    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
    AssetIndex idx = new AssetIndex(root.get("name").getAsString());
    int missingHash = 0;
    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("objects").entrySet()) {
        JsonObject o = e.getValue().getAsJsonObject();
        String hash = o.has("hash") ? o.get("hash").getAsString() : null;
        if (hash == null || !hash.matches("[0-9a-fA-F]{40}")) { missingHash++; continue; }
        long size = o.get("size").getAsLong();
        idx.assets.put(e.getKey(), new Asset(hash, size));
    }
    if (missingHash > 0)
        throw new IOException("asset index has " + missingHash + " entries missing a valid SHA-1, refusing install");
    return idx;
}
```

## Tech Stack

| Component | Technology |
|-----------|------------|
| UI framework | Compose Multiplatform 1.7.0 |
| Languages | Kotlin 2.0.21 / Java 21 |
| Build tool | Gradle 8.10 (Kotlin DSL) |
| Serialization | Gson 2.11 + kotlinx.serialization |
| Networking | OkHttp 4.12 (curl fallback supported) |
| System info | OSHI 6.6.5 |
| JavaFX | OpenJFX 25 (mac arm64) |

## Quick Start

### Prerequisites
- JDK 21+
- Gradle 8.10+ (the project ships `gradlew`)

### Build

```bash
# Build the Fat JAR (Compose native libs are fairly complete, but JavaFX native libs match the build host)
./gradlew :ui:fatJar

# Output: ui/build/libs/pmcl-1.3.0-all.jar
# Run: java -jar ui/build/libs/pmcl-1.3.0-all.jar
```

### Build native installers

```bash
# Native installer for the current OS (macOS: pkg/dmg, Windows: msi/exe, Linux: deb/rpm)
./gradlew :ui:packageDistributionForCurrentOS

# Release builds can use the packageReleasePkg / packageReleaseMsi / packageReleaseDeb tasks
```

### Build plugins

```bash
./gradlew :hmcl-plugin:ppk
# Output: hmcl-plugin/build/distributions/hmcl-embed-1.0.0.ppk

# Custom downloader plugin
./gradlew :custom-downloader-plugin:ppk
# Output: custom-downloader-plugin/build/distributions/custom-downloader-1.1.0.ppk
```

## Plugin Development

> For the full plugin package format, descriptor fields, signature trust, API contract and permission declarations, see **[PLUGIN_REQUIREMENTS.md](PLUGIN_REQUIREMENTS.md)**.

### Minimal Example

```kotlin
class MyPlugin : PmclPlugin {
    override val pluginId = "my-plugin"

    override fun onEnable(ctx: PluginContext) {
        // Register a terminal command
        ctx.registerCommand("hello", "Say hello") { args ->
            "Hello, ${args.firstOrNull() ?: "World"}!"
        }

        // Register a GUI page (sidebar)
        ctx.registerPage("my-page", "My Page", MyPageContent())
    }
}
```

### .ppk Package Format

```
my-plugin-1.0.0.ppk
├── plugin.xml                          # Manifest (info + versioning)
├── META-INF/
│   └── pmcl-plugin.properties          # Plugin descriptor
├── classes/                            # Compiled .class files (required)
├── lib/                                # Dependency JARs (optional)
├── resources/                          # Resource files (optional)
└── src/
    ├── kt/                             # Kotlin source (documentation)
    └── java/                           # Java source (documentation)
```

### Installing a Plugin

```bash
# Shell terminal
plugin package /path/to/plugin.ppk

# GUI terminal
plugin package /absolute/path/to/plugin.ppk
```

Plugins install to `~/.pmcl/plugins/<id>/`, with zip-slip protection.

## Sidebar Navigation

| Icon | Page | Function |
|------|------|----------|
| PlayArrow | Launch | version selection, launch game, status monitoring |
| Info | News | Minecraft.net RSS news |
| Share | Multiplayer | Terracotta / EasyTier rooms |
| Build | Download | version install / mod marketplace / Wiki |
| Star | Content | mods / shader packs / resource packs |
| Search | Saves | worlds / screenshots |
| Person | Accounts | Microsoft account management |
| Settings | Settings | theme, download source, launcher config |
| Terminal | Terminal | Shell with 35 commands |
| Extension | Plugins | plugin management + plugin pages |

## Engineering Notes

- **Java architecture detection** — detects the actual architecture via `java -XshowSettings:properties -version`; Apple Silicon prefers `natives-*-arm64`
- **Legacy version compatibility** — 1.12.2 and earlier force Java 8 (LaunchWrapper depends on URLClassLoader)
- **macOS .jnilib** — old LWJGL 2.x uses `.jnilib`; Java 9+ needs a `.dylib` copy
- **curl Fallback** — automatically falls back to a system `curl` subprocess when the GFW interferes with Java TLS fingerprints
- **Proxy reuse** — all network clients reuse `DownloadManager`'s OkHttpClient, inheriting user-agent config
- **Modpack gameDir** — a modpack's `gameDir` must be set to the version directory itself, not `mcRoot`
- **Fat JAR module-info** — exclude all `module-info.class` to avoid Java 21 named-module issues

## GitHub Release Sync Updates

PMCL queries the GitHub Releases API for the latest version once on every startup. Periodic sync is off by default; when the user enables it, it additionally checks every 30 minutes. When a new version is found, it picks the installer matching the current OS / arch; after the user confirms, it downloads, verifies the digest and signature, then auto-installs and restarts after the current process exits.

### Architecture

```
GitHub Releases API  ◀── startup check / optional periodic poll──  PMCL client
   │                                                       │
   │ Release contains pkg/msi/deb/rpm/platform JAR assets  │
   ▼                                                       ▼
platform/arch selection + version compare + signature asset match   new version found → prompt user
                                                                    │
                                                                    ▼
                                          download + verify SHA-256 + Ed25519
                                          exit, then install and restart
```

- **Startup check** — checks once every time PMCL opens, regardless of periodic sync
- **Periodic sync** — off by default; checks within 5s of startup when enabled, then every 30 min
- **Version comparison** — takes the Release `tag_name` (strip the `v` prefix) and compares numeric segments dot by dot
- **Asset identification** — macOS prefers `.pkg/.dmg`, Windows `.msi/.exe`, Linux `.deb/.rpm/AppImage`; falls back to an OS / arch-matching JAR only when missing; old unmarked JARs have the lowest priority
- **Security check** — the installer must ship a GitHub SHA-256 digest and a matching `.sig` Ed25519 signature asset
- **Rate-limit handling** — unauthenticated GitHub API is limited to 60/hour; on hitting the limit it auto-extends to a 2-hour interval, detected via the `X-RateLimit-Remaining` header

### Releasing a New Version

The repo ships `.github/workflows/release-desktop.yml`. After pushing a `v*` tag it builds macOS PKG, Windows MSI, Linux DEB / RPM, plus OS / arch JARs for each build host, and uploads each installer alongside its same-named `.sig`.

The publish repo must configure an Actions Secret:

- `PMCL_UPDATE_ED25519_PRIVATE_KEY`: the Base64 PKCS#8 Ed25519 private key paired with the client's built-in public key

The workflow signs a canonical payload of version, download URL, SHA-256 and file size via `tools/SignUpdateAsset.java`. A missing key or signature fails the release task, and the client refuses to install.

### Launcher-side Configuration

1. Open PMCL → Settings → scroll down to the "GitHub Release Sync" card at the bottom
2. The default repo is `PCML-Z/PCML`; you can also enter another `owner/repo`
3. "Check now" does not require periodic sync to be enabled
4. After enabling "GitHub Release Auto Sync", it auto-checks every 30 minutes
5. The status indicator under the card shows the check and sync state

When a new version is found, any PMCL page pops up the version, platform build, release notes and size. After choosing "Download and auto-install", the file is first saved to `~/.pmcl/updates/` and double-verified; then a helper install process takes over, PMCL exits gracefully, installs the corresponding system build and reopens. The system installer may trigger an admin authorization prompt.

### GitHub API Rate Limit

The unauthenticated GitHub REST API is limited to 60/hour. PMCL polls every 30 minutes (2/hour), so normal use never hits the limit. If the limit is hit for other reasons:

- On detecting `X-RateLimit-Remaining: 0`, it auto-extends the poll interval to 2 hours
- After recovery it returns to the normal 30-minute interval
- The status bar shows "GitHub API rate limited, retry in 120 min"

## License

This project is for learning and personal use only.

Minecraft is a trademark of Mojang Studios. Please make sure you own a legitimate copy of Minecraft.

## Acknowledgements

- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) — JetBrains
- [Modrinth](https://modrinth.com) — mod marketplace API
- [CurseForge](https://www.curseforge.com) — mod marketplace API
- [Terracotta](https://maven.terraformersmc.com) — multiplayer backend
