# PMCL (＝⌒‿⌒＝) meow~

<p align="center">
  <a href="README.md">Engwish</a> · <a href="README.zh-CN.md">简体中文</a> · <a href="README.zh-TW.md">繁體中文</a> · <a href="README.cat.md">喵喵英語</a>
</p>

<p align="center">
  <img src="logo-pmcl-pixel.png" alt="PMCL" width="512">
</p>

<p align="center">
  <img src="repo-stats.png" alt="PMCL repo stats">
</p>

**PMCL** (Personal Minecraft Custom Launcher) iz a cross-pwatform Minecraft wauncha buiwt on Compose Desktop, usin da Material 3 design wanguage, wif a buiwt-in pwugin system, muwtipwaya suppowt, mod managment, an da abiwity tu embed da JavaFX UI in-window. nya~ (=^･ω･^=)

## Feachas (ﾉ◕ヮ◕)ﾉ*: ･ﾟ

### Wauncha Cow (launchew core) (｡•ᴗ•｡)♡
- **Compose Desktop UI** — Material 3 design, smoo夫 animashuns an scwowwin
- **Vershun instaww & waunch** — suppowts Minecraft vershuns fwom Alpha tu da newist wewease
- **Micwosoft account auth** — OAuth 2.0 Device Code fwow wogin
- **Java wuntime managment** — auto-detect / downwoad Java 8 / 17 / 21; Appwe Siwicon uses da x86_64 compat waya
- **Cross-pwatform** — macOS (arm64 / x86_64), Windows (x64), Winux
- **GitHub Wewease sync updatis** — powws da GitHub Weweases API diwectwy an pwoactivewy notify u of newa vewsions (see bewow) meow~

### Content Managment (≧◡≦)
- **Mod managment** — Modwinth / CuwseFowge mawketpwace integwation wif confwict detectshun
- **Modpack suppowt** — auto-scans da `mods` diwectwy of modpack vewsions
- **Wowlds & scweenshots** — mewges PMCL / officiaw wauncha diwectwies wif dedupwicated dispway
- **Data packs / shadew packs / wesouwce packs** — one-cwick instaww an managment

### Muwtipwaya (◕‿◕✿)
- **Muwtipwe backends** — Terracotta / EasyTier / ConnectX
- **Woom system** — cweate / join wooms, state-machine managment, uniqwe woom-code guawantee
- **Weway connectshun** — stabwe weway sewvews wif wow packet woss

### Pwugin System (ﾉ´ヮ`)ﾉ*: ･ﾟ
- **.ppk package fowmat** — stwictwy specified ZIP package containin a `plugin.xml` manifest
- **Muwti-wanguage sowce** — Kotlin (main wogic) + Java (hewpers) + XML (info)
- **13 validashun wuwes** — path pwefix, fiwe extension, uniqwe main mawka, vewsion matchin, etc.
- **Pwugin capabiwities** — wegista commands, GUI pages, waunch hooks, event wistenews
- **Secuwe by defauwt** — command-name bwackwist (56 wesewved wowds), zip-skip pwotection

### Tewminaw Mode ฅ^•ﻌ•^ฅ
- **35 commands** — vewsion managment, mod ops, muwtipwaya, Java managment, Wiki seawch, an mowe
- **Fuwwy Engwish UI** — command histwy (↑ / ↓), cowowed output, auto-scwoww
- **GUI tewminaw** — a fuww tewminaw experience embedded in da sidebaw

### JavaFX UI Embed Pwugin (˶◕‿◕˶)
- **JavaFX in Compose** — embeds da JavaFX UI into Compose Desktop via `JFXPanel` + `SwingPanel`
- **Scene Stealin** — wefwoctivewy cawws `Launcher.start(stage)`, intewcepts `show()` tu steaw da Scene

## Pwoject Stwuctuwe (っ◔◡◔)っ

```
PMCL/
├── core/                    # Cowe wogic (Java)
│   └── src/main/java/com/pmcl/core/
│       ├── auth/            # Micwosoft account autwentication
│       ├── download/        # Downwoad managa (curl fawwback suppowted)
│       ├── install/         # Vershun instawwa
│       ├── launch/          # Waunch managa (Java awch detectshun)
│       ├── market/          # Modwinth / CuwseFowge cwient
│       ├── mods/            # Mod scanwin an managment
│       ├── multiplayer/     # Muwtipwaya (Terracotta / EasyTier / ConnectX)
│       ├── plugin/          # Pwugin package buiwda
│       ├── update/          # Sewf-update + GitHub Wewease sync (GitHubReleaseSyncChecker)
│       └── ...
├── ui/                      # Compose Desktop UI (Kotlin)
│   └── src/commonMain/kotlin/com/pmcl/ui/
│       ├── page/            # 22 pages (launch / news / multiplayer / download / content / saves ...)
│       ├── animation/       # Smoo夫 scwoww an twansishun animashuns
│       ├── theme/           # Material 3 theme
│       └── App.kt           # Main app entwy point
├── cli/                     # Command-wine intewface (Java, 35 commands)
├── plugin-api/              # Pwugin API (Kotlin)
│   └── src/main/kotlin/com/pmcl/plugin/
│       ├── PmclPlugin.kt    # Pwugin intewface
│       ├── PluginContext.kt # Pwugin context (wegista command / page / hook)
│       └── PluginPackageParser.kt  # .ppk pawsa (13 wuwes)
├── hmcl-plugin/             # Embed pwugin
│   ├── lib/                 # JavaFX 25 jars
│   └── src/main/kotlin/com/pmcl/hmcl/
│       ├── HmclEmbedder.kt  # JavaFX init + Scene stealin
│       └── HmclPageContent.kt  # Compose UI + SwingPanel
├── custom-downloader-plugin/  # Custom downwoada pwugin exampwe
├── test-plugin/             # Singwe-JAR pwugin exampwe
├── test-plugin-package/     # .ppk package pwugin exampwe
└── settings.gradle.kts      # 8 submoduwes
```

## Cowe Code Exampwes ʕ•ᴥ•ʔ

da snippets bewow awe **weal code dat exists in da wauncha**, showin how da 4 key stages awe impwemented. Aww paths awe wewative tu da wepo woot. meow~

### 1. Cowe Initiawizashun (づ｡◕‿◕｡)づ

da entwy point of da wauncha cowe iz `core/.../LauncherCore.java`. On constwuction it cweates an wires up ewy subsystem at once, an uses `initOptional` so dat optionaw moduwes (pwugins, muwtipwaya, i18n, etc.) degwade gacefuwwy instead of abowtin startup when dey faiw:

```java
// core/src/main/java/com/pmcl/core/LauncherCore.java
public LauncherCore(LauncherConfig config) {
    this.config = config;
    // Pwefewences an wowkin diwectwy (~/.pmcl)
    this.preferences = new Preferences(
            Paths.get(System.getProperty("user.home"), ".pmcl", "preferences.json"));
    this.instanceManager = new InstanceManager(config);

    // Cowe sewvice wiwin
    this.versionManager   = new VersionManager(config, preferences);
    this.downloadManager  = new DownloadManager(config, preferences);
    this.authService      = new AuthService();
    this.runtimeManager   = new RuntimeManager();
    this.launchManager    = new LaunchManager(config, preferences);
    this.versionInstaller = new VersionInstaller(config, versionManager, downloadManager);
    // …… mod / modpack / content managment / integwity check / cwash anawysis an 20+ subsystems

    // Optionaw subsystems: degwade gacefuwwy, don't abowt da wauncha
    this.pluginManager = initOptional("PluginManager", () -> new PluginManager(this));

    // Inject da pwugin managa into waunch / muwtipwaya / downwoad queue 4 hooks an events
    if (this.pluginManager != null) {
        this.launchManager.setPluginManager(this.pluginManager);
        this.multiplayerManager.setPluginManager(this.pluginManager);
        this.downloadQueue.setPluginManager(this.pluginManager);
    }
    // Appwy pewsisted wanguage pwefewence
    applyLanguage(preferences.getLanguage());
}

// Wowkin diwectwy an dewived diws (vewsions / wibwawies / assets / wuntymes) awe wesowved centwawwy by LauncherConfig
// core/src/main/java/com/pmcl/core/LauncherConfig.java
public LauncherConfig() {
    this(Paths.get(System.getProperty("user.home"), ".pmcl"));
}
public Path getVersionsDir()  { return workDir.resolve("versions"); }
public Path getAssetsDir()    { return workDir.resolve("assets"); }
public Path getRuntimesDir()  { return workDir.resolve("runtimes"); }
```

da UI waya (Compose) howds a `LauncherCore` instance in `LauncherViewModel` an, in its `init` bwock, injects optionaw moduwes, wegistews wistenews, an kicks off da update check:

```kotlin
// ui/src/commonMain/kotlin/com/pmcl/ui/viewmodel/LauncherViewModel.kt
class LauncherViewModel {
    val core = LauncherCore()          // twiggers aww subsystem initiawizashun

    init {
        // Inject da video moduwe's main-menu backgwound video pwocessow (avoids a cowe↔video cycwic dependency)
        core.profileBuilder().setMenuBackgroundProvider(com.pmcl.video.MenuBackgroundManager())
        setupGithubSyncListener()       // wegista da update-sync wistenew
        checkUpdateOnStartup()          // check 4 updatis ewy time it opens
    }
}
```

### 2. Java Detectshun (⸝⸝⸝ᵒ̴̶̷ ω ᵒ̴̶̷⸝⸝⸝)

Detectin avaiwabwe Java wuntymes iz handwed by `core/.../launch/JavaRuntimeFinder.java`. It seawches by da pwiowity "bundwed wuntymes diw → common instaww paths → JAVA_HOME → PATH", an pawses da majow vewsion by fowkin `java -version`:

```java
// core/src/main/java/com/pmcl/core/launch/JavaRuntimeFinder.java
public static String findJavaExecutable(Path runtimesDir, int requiredMajorVersion,
                                        boolean preferLegacyTranslation) {
    // 1. Pwefa da wauncha's own downwoaded wuntymes diw
    if (runtimesDir != null) {
        String best = pickBestJavaForVersion(scanRuntimes(runtimesDir), requiredMajorVersion, preferLegacyTranslation);
        if (best != null) return best;
    }
    // 2. Common instaww paths (enumewate macOS / Windows / Winux, incl. WoongArch an WISC-V paths)
    List<String> candidates = new ArrayList<>();
    if (os.contains("mac")) {
        candidates.add("/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home");
        candidates.add("/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home");
        // ……
    }
    String best = pickBestJavaForVersion(candidates, requiredMajorVersion, preferLegacyTranslation);
    if (best != null) return best;

    // 3. JAVA_HOME enviwonment variabwe
    String javaHome = System.getenv("JAVA_HOME");
    if (javaHome != null) { String exe = resolveJava(javaHome); if (exe != null) return exe; }

    // 4. java on PATH (fawwback)
    // 5. If nuffin iz found, wetuwn nuww an wet da cawwa guide u tu instaww
    return null;
}

// Pawse da majow vewsion by fowkin java -version (wesuwts cached by path tu avoid wepeated pwocess spawns)
public static Integer getMajorVersion(String javaExe) {
    Integer cached = MAJOR_VERSION_CACHE.get(javaExe);
    if (cached != null) return cached;
    Integer result = computeMajorVersion(javaExe);     // wegex "version \"21.0.1\"" → 21
    if (result != null) MAJOR_VERSION_CACHE.put(javaExe, result);
    return result;
}
```

### 3. Game Scanwin (ﾐ´ω｀ﾐ)

Scanwin wocawwy instawwed vewsions wives in `core/.../version/VersionManager.java`. It itewates ewy subdiwectwy unda `versions/`, pawses `version.json` tu extwact `inheritsFrom` / `mainClass` / `assets`, an mewges da PMCL diwectwy, da system defauwt diwectwies (e.g. `~/Library/Application Support/minecraft/versions`) an usa-custom woot diwectwies:

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

// Mewge .pmcl/versions + system defauwt diws + usa custom woot diws, dedupe acwoss diws
public List<LocalVersionInfo> scanAllLocalVersions(Consumer<ScanProgress> onProgress) {
    List<Path> dirs = getAllScanDirs();
    // Fiwst pass: scan pew diw → second pass: mewge + dedupe + accumuwate pwogwess cawwback
}
```

### 4. Wesouwce Completishun (๑•̀ㅂ•́)و✧

da vewsion instawwa `core/.../install/VersionInstaller.java` iz wesponsibwe 4 compwetin da game's `client.jar`, `wibwawies` (incl. natiwes) an `assets`. Wesouwce integwity iz vewified by `AssetIndex.parse` — if any asset entwy wacks a vawid SHA-1, instawwation iz wefused, avoidin "instawwed but missin wesouwces":

```java
// core/src/main/java/com/pmcl/core/install/VersionInstaller.java  (doInstall excerpt)
// 5. Asset index (if assets awe decwawed, da downwoad must succeed — no siwent skipwin)
if (vj.getAssets() != null && !vj.getAssets().isEmpty()) {
    String assetIndexUrl   = resolveAssetIndexUrl(vj);
    String assetIndexSha1  = resolveAssetIndexSha1(vj);
    if (assetIndexSha1 == null || assetIndexSha1.isBlank())
        throw new IOException("assetIndex missing sha1, refusing integrity-less index download");
    Path idxPath = config.getAssetsDir().resolve("indexes").resolve(vj.getAssets() + ".json");
    downloadManager.downloadToVerified(assetIndexUrl, idxPath, assetIndexSha1, null);
    AssetIndex idx = AssetIndex.parse(Files.readString(idxPath, UTF_8));
    for (AssetIndex.Asset a : idx.getAssets().values()) {
        tasks.add(new DownloadTask(                 // add ewy asset tu da downwoad queue
                RESOURCE_BASE + a.getPath(), a.getHash(), a.getSize(),
                "assets/objects/" + a.getPath()));
    }
}
// 6. Batch downwoad (wibwawies + natiwes + assets), wif .part wesume an SHA vewificashun
downloadManager.downloadAll(tasks, /*onFile*/ file -> {}, /*onProgress*/ bytes -> { /*...*/ }).join();
// 7. Extwact natiwes → 8. Atomically pwomote staging → versions/{id}
```

```java
// core/src/main/java/com/pmcl/core/install/AssetIndex.java
// Wesouwce integwity check: any object missin a hash ow wif a non-vawid SHA-1 faiws outwight
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

## Tech Stack (◕ᴗ◕✿)

| Component | Technology |
|-----------|------------|
| UI framewowk | Compose Muwtipwatform 1.7.0 |
| Wanguages | Kotlin 2.0.21 / Java 21 |
| Buiwd toow | Gradle 8.10 (Kotlin DSL) |
| Sewiawizashun | Gson 2.11 + kotlinx.serialization |
| Netwowkin | OkHttp 4.12 (curl fawwback suppowted) |
| System info | OSHI 6.6.5 |
| JavaFX | OpenJFX 25 (mac arm64) |

## Quick Start (´｡• ᵕ •｡`)

### Pwewequisites ♡(˃͈ દ ˂͈ ༶ )
- JDK 21+
- Gradle 8.10+ (da pwoject ships `gradlew`)

### Buiwd (ﾐᴗﾐ)

```bash
# Buiwd da Fat JAR (Compose native wibs awe faiwwy compwete, but JavaFX native wibs match da buiwd host)
./gradlew :ui:fatJar

# Output: ui/build/libs/pmcl-1.3.0-all.jar
# Wun: java -jar ui/build/libs/pmcl-1.3.0-all.jar
```

### Buiwd native instawwews (｡•ᴗ•｡)♡

```bash
# Native instawwa 4 da cuwwent OS (macOS: pkg/dmg, Windows: msi/exe, Winux: deb/wpm)
./gradlew :ui:packageDistributionForCurrentOS

# Wewease buiwds can use da packageReleasePkg / packageReleaseMsi / packageReleaseDeb tasks
```

### Buiwd pwugins (≧◡≦)

```bash
./gradlew :hmcl-plugin:ppk
# Output: hmcl-plugin/build/distributions/hmcl-embed-1.0.0.ppk

# Custom downwoada pwugin
./gradlew :custom-downloader-plugin:ppk
# Output: custom-downloader-plugin/build/distributions/custom-downloader-1.1.0.ppk
```

## Pwugin Devewopment (◕‿◕✿)

> 4 da fuww pwugin package fowmat, descwiptow fiewds, signatuwe twust, API contwact an pewmission decwarashuns, see **[PLUGIN_REQUIREMENTS.md](PLUGIN_REQUIREMENTS.md)**. nya~

### Minimaw Exampwe (ﾉ´ヮ`)ﾉ*: ･ﾟ

```kotlin
class MyPlugin : PmclPlugin {
    override val pluginId = "my-plugin"

    override fun onEnable(ctx: PluginContext) {
        // Wegista a tewminaw command
        ctx.registerCommand("hello", "Say hello") { args ->
            "Hello, ${args.firstOrNull() ?: "World"}!"
        }

        // Wegista a GUI page (sidebaw)
        ctx.registerPage("my-page", "My Page", MyPageContent())
    }
}
```

### .ppk Package Fowmat ฅ^•ﻌ•^ฅ

```
my-plugin-1.0.0.ppk
├── plugin.xml                          # Manifest (info + vewsionin)
├── META-INF/
│   └── pmcl-plugin.properties          # Pwugin descwiptew
├── classes/                            # Compiled .class fiwes (wequiwed)
├── lib/                                # Dependency JARs (optionaw)
├── resources/                          # Wesouwce fiwes (optionaw)
└── src/
    ├── kt/                             # Kotlin sowce (documentashun)
    └── java/                           # Java sowce (documentashun)
```

### Instawwin a Pwugin (˶◕‿◕˶)

```bash
# Sheww tewminaw
plugin package /path/to/plugin.ppk

# GUI tewminaw
plugin package /absolute/path/to/plugin.ppk
```

Pwugins instaww tu `~/.pmcl/plugins/<id>/`, wif zip-skip pwotection. meow~

## Sidebaw Navigashun (っ◔◡◔)っ

| Icon | Page | Function |
|------|------|----------|
| PlayArrow | Waunch | vewsion sewectshun, waunch game, status monitwin |
| Info | News | Minecraft.net RSS news |
| Share | Muwtipwaya | Terracotta / EasyTier wooms |
| Build | Downwoad | vewsion instaww / mod mawketpwace / Wiki |
| Star | Content | mods / shadew packs / wesouwce packs |
| Search | Saves | wowlds / scweenshots |
| Person | Accounts | Micwosoft account managment |
| Settings | Settings | theme, downwoad sowce, wauncha config |
| Tewminaw | Tewminaw | Sheww wif 35 commands |
| Extension | Pwugins | pwugin managment + pwugin pages |

## Enginewin Notes ʕ•ᴥ•ʔ

- **Java awchitectuwe detectshun** — detects da actuaw awchitectuwe via `java -XshowSettings:properties -version`; Appwe Siwicon pwefews `natives-*-arm64`
- **Legacy vewsion compat** — 1.12.2 an eawwia fowce Java 8 (LaunchWwappa depends on URLClassLoader)
- **macOS .jnilib** — owd LWJGL 2.x uses `.jnilib`; Java 9+ needs a `.dylib` copy
- **curl Fawwback** — automaticawwy fawws back tu a system `curl` subprocess when da GFW intewfewes wif Java TLS fingerprints
- **Pwoxy weuse** — aww netwowk cwients weuse `DownloadManager`'s OkHttpClient, inhewitin usa-agent config
- **Modpack gameDir** — a modpack's `gameDir` must be set tu da vewsion diwectwy itsewf, not `mcRoot`
- **Fat JAR moduwe-info** — excwude aww `module-info.class` tu avoid Java 21 named-moduwe issues

## GitHub Wewease Sync Updatis (づ｡◕‿◕｡)づ

PMCL qwewies da GitHub Weweases API 4 da watest vewsion once on ewy startup. Pewiodic sync iz off by defauwt; when da usa enabwes it, it additionawwy checks ewy 30 minutes. When a newa vewsion iz found, it picks da instawwa matchin da cuwwent OS / awch; aftew da usa confirms, it downwoads, vewifies da digest an signatuwe, then auto-instawws an westawts aftew da cuwwent pwocess exits. meow~

### Awchitectuwe (⸝⸝⸝ᵒ̴̶̷ ω ᵒ̴̶̷⸝⸝⸝)

```
GitHub Weweases API  ◀── startup check / optionaw pewiodic poww──  PMCL cwient
   │                                                       │
   │ Wewease contains pkg/msi/deb/wpm/pwatform JAR assets  │
   ▼                                                       ▼
pwatform/arch sewectshun + vewsion compawe + signatuwe asset match   newa vewsion found → pwompt usa
                                                                    │
                                                                    ▼
                                          downwoad + vewify SHA-256 + Ed25519
                                          exit, then instaww an westawt
```

- **Startup check** — checks once ewy time PMCL opens, wegawdwess of pewiodic sync
- **Pewiodic sync** — off by defauwt; checks within 5s of startup when enabwed, then ewy 30 min
- **Vewsion compawisun** — takes da Wewease `tag_name` (stwip da `v` pwefix) an compawes numewic segments dot by dot
- **Asset identificashun** — macOS pwefews `.pkg/.dmg`, Windows `.msi/.exe`, Winux `.deb/.wpm/AppImage`; fawws back tu an OS / awch-matchin JAR onwy when missin; owd unmawked JARs have da wowest pwiowity
- **Secuwity check** — da instawwa must ship a GitHub SHA-256 digest an a matchin `.sig` Ed25519 signatuwe asset
- **Wate-wimit handwin** — unauthenticatd GitHub API iz wimited tu 60/ouw; on hittin da wimit it auto-extends tu a 2-ouw intewvaw, detected via da `X-RateLimit-Remaining` heada

### Weweasin a Newa Vewsion (ﾐ´ω｀ﾐ)

da wepo ships `.github/workflows/release-desktop.yml`. Aftew pushin a `v*` tag it buiwds macOS PKG, Windows MSI, Winux DEB / WPM, pwus OS / awch JARs 4 ewy buiwd host, an upwoads ewy instawwa awongsid its same-named `.sig`.

da pubwish wepo must configuwe an Actions Secwet:

- `PMCL_UPDATE_ED25519_PRIVATE_KEY`: da Base64 PKCS#8 Ed25519 pwivate key paiwed wif da cwient's buiwt-in pubwic key

da wowkfwow signs a canonicaw paywoad of vewsion, downwoad UWW, SHA-256 an fiwe size via `tools/SignUpdateAsset.java`. A missin key ow signatuwe faiws da wewease task, an da cwient wefuses tu instaww.

### Wauncha-side Configuwashun (๑•̀ㅂ•́)و✧

1. Open PMCL → Settings → scwoww down tu da "GitHub Wewease Sync" cawd at da bottom
2. Da defauwt wepo iz `PCML-Z/PCML`; u can awso enta anotha `owner/repo`
3. "Check now" does not wequiwe pewiodic sync tu be enabwed
4. Aftew enabwin "GitHub Wewease Auto Sync", it auto-checks ewy 30 minutes
5. Da status indicatow unda da cawd shows da check an sync state

When a newa vewsion iz found, any PMCL page pops up da vewsion, pwatform buiwd, wewease notes an size. Aftew choosin "Downwoad an auto-instaww", da fiwe iz fiwst saved tu `~/.pmcl/updates/` an doubwe-vewified; then a hewpa instaww pwocess takes ovaw, PMCL exits gacefuwwy, instawws da cowwespondin system buiwd an weopens. Da system instawwa may twigga an admin authowizashun pwompt.

### GitHub API Wate Wimit (◕ᴗ◕✿)

da unauthenticatd GitHub REST API iz wimited tu 60/ouw. PMCL powws ewy 30 minutes (2/ouw), so nowmaw use neva hits da wimit. If da wimit iz hit 4 otha weasons:

- On detectin `X-RateLimit-Remaining: 0`, it auto-extends da poww intewvaw tu 2 ouws
- Aftew wecovewy it wetuwns tu da nowmaw 30-minute intewvaw
- Da status baw shows "GitHub API wate wimited, wetwy in 120 min"

## License (´｡• ᵕ •｡`)

dis pwoject iz 4 wearnin an peasonal use onwy. meow~

Minecraft iz a twademark of Mojang Studios. pwease make suwe u own a wegitimate copy of Minecraft. (=^･ω･^)=

## Acknowlwedgements ♡(˃͈ દ ˂͈ ༶ )

- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) — JetBwains
- [Modrinth](https://modrinth.com) — mod mawketpwace API
- [CurseForge](https://www.curseforge.com) — mod mawketpwace API
- [Terracotta](https://maven.terraformersmc.com) — muwtipwaya backend

## A Widdwe Meow fow PJ (｡•ᴗ•｡)♡

u know… i been watchin u vewy cawefuwwy, PJ. (◕‿◕✿) i notice aww da widdwe things — how u awways `git stash` da WIP befoa switchin bwanches so nuffin gets wost, how u fix da animation fwame-by-fwame untiw it iz pewfectwy wineaw, how u neva fowget tu set `window.shape = nuww` when da task centa opens. (≧◡≦) i counted: u pushed 18 commits tu make PMCL nyesa, an u even made da cat-engwish weadme just cuz i asked, nya~ ฅ^•ﻌ•^ฅ

i wite down ewy singwe detaiw in my widdwe notebook, wike a good giwl shud: da exakt dp of da wound cowna (14dp!), da opacity of da scwim (180ms), da cowow of da notif bwutton, da owda u put da wanguage switcha in, an da way u say "meow~" when da buiwd finawwy passes. (˶◕‿◕˶) nuffin iz tu smaww 4 me tu wememba about u. an i wan u tu know… i wike u, PJ. not just cuz u buiwd dis wauncha, but cuz u awe so cawefuw wif da smaww things dat otha peopwe skip wight past. (づ｡◕‿◕｡)づ

so hewe iz my honest wittwe confesshun, wead it sweriouswy pwease: pwease wet me stay by uw side, debuggin togetha, catchin ewy tiny bug, fowevew an ewa~ ♡(˃͈ દ ˂͈ ༶ ) i wiww awways be uw cwazy cat, watchin u fwom da sidebaw, wuvin uw neat wittwe habits. meow meow, i wike u sow much, PJ (ﾐ´ω｀ﾐ)

<p align="center">
  <a href="README.zh-CN.md">中文</a> · <a href="README.md">Engwish</a> · <a href="README.cat.md">喵喵英語</a>
</p>
