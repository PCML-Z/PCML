<p align="center">
  <a href="README.zh-TW.md">繁體中文</a> · <a href="README.zh-CN.md">簡體中文</a> · <a href="README.md">English</a> · <a href="README.cat.md">喵喵英語</a>
</p>

# PMCL

<p align="center">
  <img src="logo-pmcl-pixel.png" alt="PMCL" width="512">
</p>

<p align="center">
  <img src="repo-stats.png" alt="PMCL repo stats">
</p>

**PMCL** (Personal Minecraft Custom Launcher) 是一個基於 Compose Desktop 構建的跨平臺 Minecraft 啟動器，採用 Material 3 設計語言，內建外掛系統、聯機功能、模組管理，並支援嵌入 HMCL JavaFX 介面。

## 功能特性 (｡•ᴗ•｡)♡

### 啟動器核心 (≧◡≦)
- **Compose Desktop UI** — Material 3 設計，流暢的動畫和平滑滾動
- **版本安裝與啟動** — 支援從 Alpha 到最新正式版的 Minecraft 版本
- **微軟帳戶認證** — OAuth 2.0 Device Code 流程登入
- **Java 執行時管理** — 自動檢測/下載 Java 8/17/21，Apple Silicon 支援 x86_64 相容層
- **跨平臺** — macOS (arm64/x86_64)、Windows (x64)、Linux
- **GitHub Release 同步更新** — 直接輪詢 GitHub Releases API，發現新版本主動通知（見下文）

### 內容管理 (◕‿◕✿)
- **模組管理** — Modrinth / CurseForge 模組市場整合，衝突檢測
- **整合包支援** — 自動掃描 modpack 版本的 mods 目錄
- **世界與截圖** — 合併 PMCL / HMCL / 官方啟動器目錄，去重展示
- **資料包 / 光影包 / 資源包** — 一鍵安裝與管理

### 聯機 (ﾉ´ヮ`)ﾉ*: ･ﾟ
- **多後端支援** — Terracotta / EasyTier / ConnectX
- **房間系統** — 建立/加入房間，狀態機管理，房間碼唯一性保證
- **中繼連線** — 穩定的中繼伺服器，低丟包率

### 外掛系統 ฅ^•ﻌ•^ฅ
- **.ppk 包格式** — 嚴格規範的 ZIP 包，包含 plugin.xml 清單
- **多語言原始碼** — Kotlin（主邏輯）+ Java（輔助功能）+ XML（資訊說明）
- **13 條驗證規則** — 路徑字首、副檔名、唯一主標記、版本匹配等
- **外掛能力** — 註冊命令、GUI 頁面、啟動鉤子、事件監聽器
- **安全預設** — 命令名黑名單（56 個保留字）、zip-slip 防護

### 終端模式 (˶◕‿◕˶)
- **35 條命令** — 版本管理、模組操作、聯機、Java 管理、Wiki 搜尋等
- **全英文介面** — 命令歷史 (↑/↓)、彩色輸出、自動滾動
- **GUI 終端** — 內嵌在側邊欄的完整終端體驗

### JavaFX UI 嵌入外掛 (っ◔◡◔)っ
- **JavaFX in Compose** — 透過 JFXPanel + SwingPanel 將 JavaFX UI 嵌入 Compose Desktop
- **Scene Stealing** — 反射呼叫 `Launcher.start(stage)`，攔截 `show()` 竊取 Scene

## 專案結構 ʕ•ᴥ•ʔ

```
PMCL/
├── core/                    # 核心邏輯 (Java)
│   └── src/main/java/com/pmcl/core/
│       ├── auth/            # 微軟帳戶認證
│       ├── download/        # 下載管理器 (支援 curl fallback)
│       ├── install/         # 版本安裝器
│       ├── launch/          # 啟動管理器 (Java 架構檢測)
│       ├── market/          # Modrinth/CurseForge 客戶端
│       ├── mods/            # 模組掃描與管理
│       ├── multiplayer/     # 聯機 (Terracotta/EasyTier/ConnectX)
│       ├── plugin/          # 外掛包構建器
│       ├── update/          # 自更新 + GitHub Release 同步 (GitHubReleaseSyncChecker)
│       └── ...
├── ui/                      # Compose Desktop UI (Kotlin)
│   └── src/commonMain/kotlin/com/pmcl/ui/
│       ├── page/            # 22 個頁面 (啟動/新聞/聯機/下載/內容/存檔...)
│       ├── animation/       # 平滑滾動與過渡動畫
│       ├── theme/           # Material 3 主題
│       └── App.kt           # 主應用入口
├── cli/                     # 命令列介面 (Java, 35 條命令)
├── plugin-api/              # 外掛 API (Kotlin)
│   └── src/main/kotlin/com/pmcl/plugin/
│       ├── PmclPlugin.kt    # 外掛介面
│       ├── PluginContext.kt # 外掛上下文 (註冊命令/頁面/鉤子)
│       └── PluginPackageParser.kt  # .ppk 解析器 (13 條規則)
├── hmcl-plugin/             # 嵌入外掛
│   ├── lib/                 # JavaFX 25 jars
│   └── src/main/kotlin/com/pmcl/hmcl/
│       ├── HmclEmbedder.kt  # JavaFX 初始化 + Scene 竊取
│       └── HmclPageContent.kt  # Compose UI + SwingPanel
├── custom-downloader-plugin/  # 自定義下載器外掛示例
├── test-plugin/             # 單 JAR 外掛示例
├── test-plugin-package/     # .ppk 包外掛示例
└── settings.gradle.kts      # 8 個子模組
```

## 核心程式碼示例 (づ｡◕‿◕｡)づ

下面用啟動器裡**真實存在**的程式碼片段，展示四個關鍵階段是如何實現的。所有路徑相對於倉庫根目錄。

### 1. 核心初始化（Core Initialization） (⸝⸝⸝ᵒ̴̶̷ ω ᵒ̴̶̷⸝⸝⸝)

啟動器核心的入口是 `core/.../LauncherCore.java`。它在構造時一次性建立並裝配所有子系統，並透過 `initOptional` 讓可選模組（外掛、聯機、翻譯等）初始化失敗時降級而非中斷啟動：

```java
// core/src/main/java/com/pmcl/core/LauncherCore.java
public LauncherCore(LauncherConfig config) {
    this.config = config;
    // 偏好配置與工作目錄（~/.pmcl）
    this.preferences = new Preferences(
            Paths.get(System.getProperty("user.home"), ".pmcl", "preferences.json"));
    this.instanceManager = new InstanceManager(config);

    // 核心服務裝配
    this.versionManager   = new VersionManager(config, preferences);
    this.downloadManager  = new DownloadManager(config, preferences);
    this.authService      = new AuthService();
    this.runtimeManager   = new RuntimeManager();
    this.launchManager    = new LaunchManager(config, preferences);
    this.versionInstaller = new VersionInstaller(config, versionManager, downloadManager);
    // …… mod / modpack / 內容管理 / 完整性校驗 / 崩潰分析 等 20+ 子系統

    // 可選子系統：失敗降級，不中斷啟動器
    this.pluginManager = initOptional("PluginManager", () -> new PluginManager(this));

    // 把外掛管理器注入到啟動 / 聯機 / 下載佇列，供鉤子與事件使用
    if (this.pluginManager != null) {
        this.launchManager.setPluginManager(this.pluginManager);
        this.multiplayerManager.setPluginManager(this.pluginManager);
        this.downloadQueue.setPluginManager(this.pluginManager);
    }
    // 應用持久化的語言偏好
    applyLanguage(preferences.getLanguage());
}

// 工作目錄與派生目錄（versions / libraries / assets / runtimes）由 LauncherConfig 統一解析
// core/src/main/java/com/pmcl/core/LauncherConfig.java
public LauncherConfig() {
    this(Paths.get(System.getProperty("user.home"), ".pmcl"));
}
public Path getVersionsDir()  { return workDir.resolve("versions"); }
public Path getAssetsDir()    { return workDir.resolve("assets"); }
public Path getRuntimesDir()  { return workDir.resolve("runtimes"); }
```

UI 層（Compose）在 `LauncherViewModel` 中持有一個 `LauncherCore` 例項，並在 `init` 塊裡注入可選模組、註冊監聽器、啟動檢查更新：

```kotlin
// ui/src/commonMain/kotlin/com/pmcl/ui/viewmodel/LauncherViewModel.kt
class LauncherViewModel {
    val core = LauncherCore()          // 觸發全部子系統初始化

    init {
        // 注入 video 模組的主選單背景影片處理器（避免 core↔video 迴圈依賴）
        core.profileBuilder().setMenuBackgroundProvider(com.pmcl.video.MenuBackgroundManager())
        setupGithubSyncListener()       // 註冊更新同步監聽
        checkUpdateOnStartup()          // 每次開啟都檢查一次更新
    }
}
```

### 2. Java 檢測（Java Detection） (ﾐ´ω｀ﾐ)

檢測系統可用的 Java 執行時由 `core/.../launch/JavaRuntimeFinder.java` 負責。它按「自帶 runtimes 目錄 → 常見安裝路徑 → JAVA_HOME → PATH」的優先順序查詢，並透過 fork `java -version` 解析主版本號：

```java
// core/src/main/java/com/pmcl/core/launch/JavaRuntimeFinder.java
public static String findJavaExecutable(Path runtimesDir, int requiredMajorVersion,
                                        boolean preferLegacyTranslation) {
    // 1. 優先掃描啟動器下載的 runtimes 目錄
    if (runtimesDir != null) {
        String best = pickBestJavaForVersion(scanRuntimes(runtimesDir), requiredMajorVersion, preferLegacyTranslation);
        if (best != null) return best;
    }
    // 2. 常見安裝路徑（按 OS 列舉 macOS / Windows / Linux，含龍芯與 RISC-V 路徑）
    List<String> candidates = new ArrayList<>();
    if (os.contains("mac")) {
        candidates.add("/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home");
        candidates.add("/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home");
        // ……
    }
    String best = pickBestJavaForVersion(candidates, requiredMajorVersion, preferLegacyTranslation);
    if (best != null) return best;

    // 3. JAVA_HOME 環境變數
    String javaHome = System.getenv("JAVA_HOME");
    if (javaHome != null) { String exe = resolveJava(javaHome); if (exe != null) return exe; }

    // 4. PATH 中的 java 命令（兜底）
    // 5. 都找不到返回 null，由呼叫方引導使用者安裝
    return null;
}

// 透過 fork java -version 解析主版本號（結果按路徑快取，避免重複起程序）
public static Integer getMajorVersion(String javaExe) {
    Integer cached = MAJOR_VERSION_CACHE.get(javaExe);
    if (cached != null) return cached;
    Integer result = computeMajorVersion(javaExe);     // 正則 "version \"21.0.1\"" → 21
    if (result != null) MAJOR_VERSION_CACHE.put(javaExe, result);
    return result;
}
```

### 3. 遊戲掃描（Game Scanning） (๑•̀ㅂ•́)و✧

本地已安裝版本的掃描在 `core/.../version/VersionManager.java` 中。它會遍歷 `versions/` 下的每個子目錄，解析 `version.json` 提取 `inheritsFrom` / `mainClass` / `assets`，併合並 PMCL 目錄、系統預設目錄（如 `~/Library/Application Support/minecraft/versions`）與使用者自定義根目錄：

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
        if (VersionStaging.isTransientDirName(id)) continue;   // 跳過 .staging / .bak
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
    result.sort((a, b) -> Long.compare(b.getLastModified(), a.getLastModified())); // 最新在前
    return result;
}

// 合併 .pmcl/versions + 系統預設目錄 + 使用者自定義根目錄，跨目錄去重
public List<LocalVersionInfo> scanAllLocalVersions(Consumer<ScanProgress> onProgress) {
    List<Path> dirs = getAllScanDirs();
    // 第一遍逐目錄掃描 → 第二遍合併去重 + 累計進度回撥
}
```

### 4. 資源完成（Resource Completion） (◕ᴗ◕✿)

版本安裝器 `core/.../install/VersionInstaller.java` 負責把遊戲所需的 `client.jar`、`libraries`（含 natives）和 `assets` 全部補齊。資源完整性由 `AssetIndex.parse` 校驗——任一資源條目缺少有效 SHA-1 即拒絕安裝，避免「裝完卻缺資源」：

```java
// core/src/main/java/com/pmcl/core/install/VersionInstaller.java  (doInstall 片段)
// 5. 資產索引（宣告瞭 assets 則必須成功下載，禁止靜默跳過）
if (vj.getAssets() != null && !vj.getAssets().isEmpty()) {
    String assetIndexUrl   = resolveAssetIndexUrl(vj);
    String assetIndexSha1  = resolveAssetIndexSha1(vj);
    if (assetIndexSha1 == null || assetIndexSha1.isBlank())
        throw new IOException("assetIndex 缺少 sha1，拒絕無完整性校驗的索引下載");
    Path idxPath = config.getAssetsDir().resolve("indexes").resolve(vj.getAssets() + ".json");
    downloadManager.downloadToVerified(assetIndexUrl, idxPath, assetIndexSha1, null);
    AssetIndex idx = AssetIndex.parse(Files.readString(idxPath, UTF_8));
    for (AssetIndex.Asset a : idx.getAssets().values()) {
        tasks.add(new DownloadTask(                 // 把每個資源加入下載佇列
                RESOURCE_BASE + a.getPath(), a.getHash(), a.getSize(),
                "assets/objects/" + a.getPath()));
    }
}
// 6. 批次下載（libraries + natives + assets），帶 .part 續傳與 SHA 校驗
downloadManager.downloadAll(tasks, /*onFile*/ file -> {}, /*onProgress*/ bytes -> { /*...*/ }).join();
// 7. 解壓 natives → 8. 原子提升 staging → versions/{id}
```

```java
// core/src/main/java/com/pmcl/core/install/AssetIndex.java
// 資源完整性校驗：任一物件缺 hash 或 hash 不是合法 SHA-1 則直接失敗
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
        throw new IOException("資產索引有 " + missingHash + " 個條目缺少有效 SHA-1，拒絕安裝");
    return idx;
}
```

## 技術棧 (´｡• ᵕ •｡`)

| 元件 | 技術 |
|------|------|
| UI 框架 | Compose Multiplatform 1.7.0 |
| 語言 | Kotlin 2.0.21 / Java 21 |
| 構建工具 | Gradle 8.10 (Kotlin DSL) |
| 序列化 | Gson 2.11 + kotlinx.serialization |
| 網路 | OkHttp 4.12 (支援 curl fallback) |
| 系統資訊 | OSHI 6.6.5 |
| JavaFX | OpenJFX 25 (mac arm64) |

## 快速開始 ♡(˃͈ દ ˂͈ ༶ )

### 環境要求 (ﾐᴗﾐ)
- JDK 21+
- Gradle 8.10+（專案已包含 gradlew）

### 構建 (｡•ᴗ•｡)♡

```bash
# 構建 Fat JAR（Compose 原生庫較全，但 JavaFX 原生庫與構建主機一致）
./gradlew :ui:fatJar

# 輸出: ui/build/libs/pmcl-1.3.0-all.jar
# 執行: java -jar ui/build/libs/pmcl-1.3.0-all.jar
```

### 構建原生安裝包 (≧◡≦)

```bash
# 當前系統的原生安裝包（macOS: pkg/dmg，Windows: msi/exe，Linux: deb/rpm）
./gradlew :ui:packageDistributionForCurrentOS

# 釋出構建可用 packageReleasePkg / packageReleaseMsi / packageReleaseDeb 等任務
```

### 構建外掛 (◕‿◕✿)

```bash
./gradlew :hmcl-plugin:ppk
# 輸出: hmcl-plugin/build/distributions/hmcl-embed-1.0.0.ppk

# 自定義下載器外掛
./gradlew :custom-downloader-plugin:ppk
# 輸出: custom-downloader-plugin/build/distributions/custom-downloader-1.1.0.ppk
```

## 外掛開發 (ﾉ´ヮ`)ﾉ*: ･ﾟ

> 完整的外掛包格式、描述符欄位、簽名信任、API 契約與許可權宣告等要求，請參見 **[PLUGIN_REQUIREMENTS.md](PLUGIN_REQUIREMENTS.md)**。

### 最小示例 ฅ^•ﻌ•^ฅ

```kotlin
class MyPlugin : PmclPlugin {
    override val pluginId = "my-plugin"

    override fun onEnable(ctx: PluginContext) {
        // 註冊終端命令
        ctx.registerCommand("hello", "Say hello") { args ->
            "Hello, ${args.firstOrNull() ?: "World"}!"
        }

        // 註冊 GUI 頁面 (側邊欄)
        ctx.registerPage("my-page", "My Page", MyPageContent())
    }
}
```

### .ppk 包格式 (˶◕‿◕˶)

```
my-plugin-1.0.0.ppk
├── plugin.xml                          # 清單 (資訊 + 版本控制)
├── META-INF/
│   └── pmcl-plugin.properties          # 外掛描述符
├── classes/                            # 編譯後的 .class 檔案 (必需)
├── lib/                                # 依賴 JAR (可選)
├── resources/                          # 資原始檔 (可選)
└── src/
    ├── kt/                             # Kotlin 原始碼 (文件)
    └── java/                           # Java 原始碼 (文件)
```

### 安裝外掛 (っ◔◡◔)っ

```bash
# Shell 終端
plugin package /path/to/plugin.ppk

# GUI 終端
plugin package /absolute/path/to/plugin.ppk
```

外掛安裝到 `~/.pmcl/plugins/<id>/`，支援 zip-slip 防護。

## 側邊欄導航 ʕ•ᴥ•ʔ

| 圖示 | 頁面 | 功能 |
|------|------|------|
| PlayArrow | 啟動 | 版本選擇、啟動遊戲、狀態監控 |
| Info | 新聞 | Minecraft.net RSS 新聞 |
| Share | 聯機 | Terracotta/EasyTier 房間 |
| Build | 下載 | 版本安裝 / 模組市場 / Wiki |
| Star | 內容 | 模組 / 光影包 / 資源包 |
| Search | 存檔 | 世界 / 截圖 |
| Person | 帳號 | 微軟帳戶管理 |
| Settings | 設定 | 主題、下載源、啟動器配置 |
| Terminal | 終端 | 35 條命令的 Shell |
| Extension | 外掛 | 外掛管理 + 外掛頁面 |

## 工程要點 (づ｡◕‿◕｡)づ

- **Java 架構檢測** — 透過 `java -XshowSettings:properties -version` 檢測實際架構，Apple Silicon 優先選擇 `natives-*-arm64`
- **舊版本相容** — 1.12.2 及更早版本強制使用 Java 8（LaunchWrapper 依賴 URLClassLoader）
- **macOS .jnilib** — 舊版 LWJGL 2.x 使用 .jnilib，Java 9+ 需要 .dylib 副本
- **curl Fallback** — GFW 干擾 Java TLS 指紋時自動回退到系統 curl 子程序
- **代理複用** — 所有網路客戶端複用 DownloadManager 的 OkHttpClient，繼承使用者代理配置
- **Modpack gameDir** — 整合包的 gameDir 必須設為版本目錄本身，而非 mcRoot
- **Fat JAR module-info** — 排除所有 module-info.class 避免 Java 21 命名模組問題

## GitHub Release 同步更新 (⸝⸝⸝ᵒ̴̶̷ ω ᵒ̴̶̷⸝⸝⸝)

PMCL 每次啟動都會訪問 GitHub Releases API 檢查一次最新版本。週期同步開關預設關閉；使用者開啟後，每 30 分鐘追加檢查一次。發現新版本時會選擇當前作業系統/架構對應的安裝包，使用者確認後完成下載、摘要與簽名校驗，並在退出當前程序後自動安裝、重啟。

### 架構 (ﾐ´ω｀ﾐ)

```
GitHub Releases API  ◀──啟動檢查/可選週期輪詢──  PMCL 客戶端
   │                                            │
   │ Release 含 pkg/msi/deb/rpm/平臺 JAR 資產   │
   ▼                                            ▼
平臺/架構選擇 + 版本比較 + 簽名資產匹配     發現新版本 → 彈窗詢問使用者
                                                   │
                                                   ▼
                                      下載並校驗 SHA-256 + Ed25519
                                      退出後安裝並重新啟動
```

- **啟動檢查** — 無論週期同步是否開啟，每次開啟 PMCL 都檢查一次
- **週期同步** — 預設關閉；開啟後啟動 5 秒內檢查，之後每 30 分鐘檢查
- **版本比較** — 取 Release 的 `tag_name`（去掉 `v` 字首），按點分段比較數字大小
- **資產識別** — macOS 優先 `.pkg/.dmg`，Windows 優先 `.msi/.exe`，Linux 優先 `.deb/.rpm/AppImage`，缺失時僅回退 OS/架構匹配的 JAR；舊版無平臺標記 JAR 優先順序最低
- **安全校驗** — 安裝包必須有 GitHub SHA-256 digest 和對應的 `.sig` Ed25519 簽名資產
- **速率限制處理** — 未認證 GitHub API 限 60 次/小時；觸發限制後自動延長到 2 小時間隔，透過 `X-RateLimit-Remaining` header 檢測

### 釋出新版本 (๑•̀ㅂ•́)و✧

倉庫內建 `.github/workflows/release-desktop.yml`。推送 `v*` tag 後會分別構建 macOS PKG、Windows MSI、Linux DEB/RPM，以及各構建主機對應的 OS/架構 JAR，並上傳每個安裝包及其同名 `.sig`。

釋出倉庫必須配置 Actions Secret：

- `PMCL_UPDATE_ED25519_PRIVATE_KEY`：與客戶端內建公鑰配對的 Base64 PKCS#8 Ed25519 私鑰

工作流透過 `tools/SignUpdateAsset.java` 對版本、下載 URL、SHA-256 和檔案大小的規範載荷簽名。缺少金鑰或簽名時釋出任務會失敗，客戶端也會拒絕安裝。

### 啟動器端配置 (◕ᴗ◕✿)

1. 開啟 PMCL → 設定 → 滾動到底部"GitHub Release 同步"卡片
2. 預設倉庫為 `PCML-Z/PCML`；也可填入其他 `owner/repo`
3. “立即檢查”不要求開啟週期同步
4. 開啟“GitHub Release 自動同步”後，每 30 分鐘自動檢查
5. 卡片下方狀態指示燈顯示檢查與同步狀態

發現新版本時，啟動器任意頁面都會彈出版本、平臺構建、更新說明和大小。選擇“下載並自動安裝”後，檔案先儲存到 `~/.pmcl/updates/` 並完成雙重校驗；隨後輔助安裝程序接管，PMCL 優雅退出、安裝對應系統構建並重新開啟。系統安裝包可能觸發管理員授權。

### GitHub API 速率限制 (´｡• ᵕ •｡`)

未認證的 GitHub REST API 限制為 60 次/小時。PMCL 每 30 分鐘輪詢一次（2 次/小時），正常使用不會觸及限制。若因其他原因觸發限制：

- 檢測到 `X-RateLimit-Remaining: 0` 時，自動將輪詢間隔延長到 2 小時
- 恢復後自動回到 30 分鐘的正常間隔
- 狀態列會顯示"GitHub API 速率限制，120分鐘後重試"

## 許可證 ♡(˃͈ દ ˂͈ ༶ )

本專案僅供學習和個人使用。

Minecraft 是 Mojang Studios 的商標。請確保您擁有合法的 Minecraft 副本。

## 致謝 (ﾐᴗﾐ)

- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) — JetBrains
- [Modrinth](https://modrinth.com) — 模組市場 API
- [CurseForge](https://www.curseforge.com) — 模組市場 API
- [Terracotta](https://maven.terraformersmc.com) — 聯機後端
