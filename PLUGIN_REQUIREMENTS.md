# PMCL 插件要求规范

> 本文件依据 PMCL 源码中插件系统（`plugin-api` 与 `core` 模块）的实现如实整理，
> 描述开发一个合规 PMCL 插件必须满足的全部要求。代码位置以 `plugin-api/...`、
> `core/...` 标注，便于对照实现。

---

## 1. 概述

PMCL 插件是**扩展启动器能力的独立模块**。一个插件以 `.ppk` 包的形式分发，
放置在插件目录后由启动器在运行时加载。

| 项 | 说明 |
|----|------|
| 插件包扩展名 | `.ppk` |
| 包本质 | 经过 `jarsigner` 签名的 **ZIP / JAR** 归档 |
| 默认插件目录 | `~/.pmcl/plugins/` |
| 启用声明 | `~/.pmcl/plugins/plugins.json` 中的 `enabled` 映射 |
| 信任指纹列表 | `~/.pmcl/plugins/trusted-signers.txt` 与 `-Dpmcl.plugins.trustedFingerprints` |
| 支持 API 版本 | `1.0` – `1.7`（向后兼容：更高版本的宿主接受更低版本的插件） |

**加载流程**（详见 `PluginManager.loadPluginPackage`）：

```
发现 .ppk
  → 验签 (verifyPluginArchive, 强制 jarsigner + 命中信任指纹)
  → 解析 plugin.xml (PluginPackageParser.parse)
  → 校验字段 (PluginInfo.validate)
  → 解压到 ~/.pmcl/plugins/<pluginId>/
  → 普通 JVM 插件：构建隔离 ClassLoader 并实例化 main-class
    嵌入/外部运行时插件：跳过 ClassLoader，走桥接逻辑
  → onLoad() → onEnable(ctx)
```

---

## 2. 插件包结构 (.ppk)

`.ppk` 是一个 **必须签名** 的 ZIP 归档（C2 策略：没有「允许无签名」后门，
未签名的包会被直接拒绝）。推荐结构：

```
my-plugin-1.0.0.ppk
├── plugin.xml                        # 必需 —— 包清单（信息 + 版本历史 + 依赖）
├── classes/                         # 普通 JVM 插件编译后的 .class 文件
│   └── com/example/MyPlugin.class
├── lib/                             # 依赖 JAR（可选）
│   └── gson-2.10.jar
├── resources/                      # 资源（可选）
└── META-INF/
    ├── pmcl-plugin.properties       # 普通 JVM 插件必需的描述符
    ├── MANIFEST.MF
    ├── <ALIAS>.SF                   # jarsigner 签名块
    └── <ALIAS>.RSA                  # jarsigner 证书
```

**签名要求**（`PluginManager.assertAllSignedEntries`）：

- 以下关键 entry **必须带 `CodeSigner`**（即被实际签名覆盖）：
  - 所有 `.class` 文件
  - `META-INF/pmcl-plugin.properties`
  - `plugin.xml`
  - `classes/**`（整个目录）
  - `lib/*.jar`（库 JAR 本身也要签）
- 仅签名描述符、而让 `classes/`、`lib/*.jar` 未签名 → 视为「部分签名」并拒绝（防 H1 绕过）。

---

## 3. 插件描述符

PMCL 插件有**两份描述符**，用途不同：

### 3.1 `plugin.xml`（包清单，format-version = `1.0`）

放在包根，UTF-8 编码，是结构化的清单文件（含信息、源码声明、依赖、版本历史、资源）。

**最小示例**：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<pmcl-plugin-package
    xmlns="https://pmcl.dev/plugin"
    format-version="1.0">

    <info>
        <id>my-awesome-plugin</id>
        <name>My Awesome Plugin</name>
        <version>1.0.0</version>
        <author>Author Name</author>
        <description>Does awesome things</description>
        <api-version>1.0</api-version>
        <main-class>com.example.MyPlugin</main-class>
        <license>MIT</license>
        <!-- 可选：敏感权限声明 -->
        <permissions>READ_ACCOUNTS,CONTROL_LAUNCH</permissions>
    </info>

    <sources>
        <kotlin>
            <file path="src/kt/com/example/MyPlugin.kt" main="true"/>
        </kotlin>
    </sources>

    <dependencies>
        <dependency id="other-plugin" version=">=1.0.0"/>
    </dependencies>

    <libraries>
        <library path="lib/gson-2.10.jar"/>
    </libraries>

    <versions>
        <version number="1.0.0" date="2026-07-10" author="Author Name">
            Initial release.
        </version>
    </versions>
</pmcl-plugin-package>
```

**`plugin.xml` 硬性规则**（`PluginPackageParser`）：

1. 必须位于包根，UTF-8。
2. `format-version` 必须为 `1.0`。
3. `<info>` 中 `PluginInfo` 要求的全部必填字段必须存在（见第 4 节）。
4. 普通 JVM 插件：`<sources>` 中**至少存在一个 Kotlin 源文件且恰好一个 `main="true"`**；
   Kotlin 路径以 `src/kt/` 开头、`.kt` 结尾；Java 路径以 `src/java/` 开头、`.java` 结尾。
5. `<libraries><library>` 路径以 `lib/` 开头、`.jar` 结尾。
6. `<resources><resource>` 路径以 `resources/` 开头。
7. `<versions>` 至少一个 `<version>`，且其中一个 `number` 必须等于 `<info><version>`；
   版本号必须合法 SemVer、日期 `YYYY-MM-DD`、作者非空。
8. **XXE 防护**：`plugin.xml` 不允许 `DOCTYPE` 声明（解析器已 `disallow-doctype-decl`），
   且限制大小（≤ 1 MB）、元素数、嵌套深度、实体扩展数（防 DoS）。
9. 所有声明路径必须在包内真实存在；不允许 `..`（路径穿越）。
10. **嵌入 / 外部运行时插件豁免第 4、9 条**（无 Kotlin 源码、无需 `pmcl-plugin.properties` 路径校验）。

### 3.2 `META-INF/pmcl-plugin.properties`（普通 JVM 插件必需）

这是插件身份的**单一事实来源**（single source of truth），被 `PluginManager` 在加载时读取
（见 `missingRequiredField` 逻辑）。普通 JVM 插件若缺失此文件 → 加载失败（静默不加载）。

**字段表**：

| Key | 约束 |
|-----|------|
| `plugin.id` | 3–32 字符，`[a-z][a-z0-9-]*[a-z0-9]`，无 `--` |
| `plugin.name` | 1–64 字符，非空 |
| `plugin.version` | SemVer `X.Y.Z` 或 `X.Y.Z-pre` |
| `plugin.author` | 1–64 字符，非空 |
| `plugin.description` | 1–256 字符，非空 |
| `plugin.api-version` | `1.0` – `1.7` |
| `plugin.main-class` | 合法 Java 全限定名（至少 2 段，如 `com.example.MyPlugin`） |
| `plugin.dependencies` | 可选，逗号分隔的插件 ID |
| `plugin.website` | 可选，`http(s)://`，≤ 512 字符 |
| `plugin.license` | 可选，1–64 字符 |
| `plugin.permissions` | 可选，逗号分隔的权限名（见第 7 节） |

**示例**：

```properties
plugin.id=my-awesome-plugin
plugin.name=My Awesome Plugin
plugin.version=1.0.0
plugin.author=Author Name
plugin.description=Does awesome things
plugin.api-version=1.0
plugin.main-class=com.example.MyPlugin
plugin.license=MIT
```

> **豁免说明**：`embed=web` / `embed=window` / `external-runtime` 插件从 `plugin.xml` 读取身份，
> 并豁免 properties 路径校验。但若仍随包提供且正确签名，加载更稳健。

---

## 4. 字段格式规范（`PluginInfo.validate`）

所有插件在加载时会执行 `PluginInfo.validate()`，任一字段不符即抛 `IllegalArgumentException`。

- **ID**：3–32 字符，小写字母+数字+连字符；首字符必须是字母；尾字符必须是字母或数字；
  **不允许连续连字符 `--`**。`my-awesome-plugin` ✓ / `my--plugin` ✗
- **Version**：`MAJOR.MINOR.PATCH`，可带预发布 `-id`（如 `1.0.0-beta.1`）；**不支持** `+build` 元数据。
- **Main-Class**：合法 Java FQN，至少 `包.类` 两段，每段以字母/下划线开头。
- **Description**：≤ **256** 字符（超长直接校验失败）。
- **API-Version**：必须在 `1.0`–`1.7` 集合内。
- **外部运行时字段**（v1.7+，见第 9 节）在 `external-runtime` / `embed` 设置时有各自的附加约束。

---

## 5. 代码签名与信任机制

这是插件能「被加载」的**前置硬条件**，不满足则直接拒绝。

1. **必须签名**：`.ppk` 须用 `jarsigner` 签名（见第 2 节关键 entry 要求）。
2. **信任指纹命中**：每个关键 entry 的签名者证书 **SHA-256 指纹**必须出现在信任列表中。
   - 信任列表来源（`PluginManager.assertTrustedPluginSigner`）：
     - 启动参数 `-Dpmcl.plugins.trustedFingerprints=<fp1>;<fp2>`
     - 文件 `~/.pmcl/plugins/trusted-signers.txt`
   - 列表非空时：必须命中其一，否则 `SecurityException` 拒绝。
   - 列表为空且未开 `allowAnySigner`：默认拒绝。
3. **运行时不可降级**：`System.setProperty` 无法在运行期放宽签名策略（C3）。
4. **无后门**：`.ppk` 不存在「跳过验签」开关；开发期可使用
   `-Dpmcl.plugins.allowAnySigner=true` 临时放行任意有效签名（仅开发用）。

**签名命令示例**（使用受信任的 keystore 重新签名）：

```bash
jarsigner -keystore pcmlAndroid/app/pmcl.keystore \
          -storepass <storepass> -keypass <keypass> \
          -signedjar my-plugin.ppk my-plugin-unsigned.zip pmcl
```

> 用受信任的 keystore（alias `pmcl`）签名后，其证书指纹才会出现在 `trusted-signers.txt`
> 中并被接受。重新打包时**务必保留 `META-INF/pmcl-plugin.properties`**，仅替换签名三件套
> （`MANIFEST.MF` / `<ALIAS>.SF` / `<ALIAS>.RSA`），否则普通 JVM 插件会因缺失属性文件而
> 静默不加载。

---

## 6. 插件 API 契约

### 6.1 生命周期接口 `PmclPlugin`

每个插件主类必须实现 `com.pmcl.plugin.PmclPlugin`，并提供无参构造函数。

```kotlin
interface PmclPlugin {
    val pluginId: String          // 唯一 ID，默认取类名小写
    fun onLoad() {}               // 首次加载、启用前调用
    fun onEnable(context: PluginContext)   // 启用时调用：注册命令/页面/钩子
    fun onDisable() {}            // 禁用 / 关闭前调用：清理
}
```

**最小 Kotlin 插件**：

```kotlin
package com.example

class MyPlugin : PmclPlugin {
    override val pluginId = "my-awesome-plugin"
    override fun onEnable(ctx: PluginContext) {
        ctx.registerCommand("hello", "Say hello") { args ->
            "Hello, ${args.firstOrNull() ?: "World"}!"
        }
    }
}
```

**最小 Java 插件**：

```java
public class MyPlugin implements PmclPlugin {
    @Override public String getPluginId() { return "my-awesome-plugin"; }
    @Override public void onEnable(PluginContext ctx) {
        ctx.registerCommand("hello", "Say hello", args -> "Hello, World!");
    }
}
```

### 6.2 `PluginContext` —— 注册与宿主服务访问

`onEnable` 收到的 `PluginContext` 提供两类能力：

**(A) 类型化宿主 API（优先使用）**

| 方法 | 对应能力 |
|------|---------|
| `versions()` | 版本查询/安装 |
| `instances()` | 游戏实例管理 |
| `accounts()` | 账号（受限，见权限） |
| `launch()` | 启动 / 控制游戏、注册 LaunchHook |
| `loaderVersions()` | 加载器（Forge/Fabric…）版本 |
| `downloads()` / `downloadQueue()` | 下载 / 队列 |
| `mods()` / `modMarket()` / `modpacks()` | 模组 / 市场 / 整合包 |
| `gameContent()` | 世界 / 资源包 / 光影 / 数据包 |
| `gameProcess()` | 游戏进程控制 |
| `rooms()` | 联机房间 |
| `servers()` | 收藏服务器 |
| `javaRuntimes()` | Java 运行时检测 |
| `nbt()` / `crashLogs()` / `stats()` / `news()` / `i18n()` | NBT / 崩溃日志 / 统计 / 资讯 / 国际化 |
| `music()` | 音乐播放器 |
| `settings()` | 设置（写操作受限） |
| `ui()` | UI 扩展 |
| `filesystem()` | 本地文件系统（受限） |
| `scheduler()` | 定时任务 |
| `plugins()` | 插件管理（受限） |
| `http()` | HTTP 请求（受限） |

**(B) 注册与工具方法**

- `registerCommand(name, desc, handler)` —— 注册命令 `plugin:<id>:<name>`
- `registerPage(id, title, content)` —— 在侧边栏注册 GUI 页面
- `registerSettingsSection(id, title, content)` —— 设置 > 扩展 中注册区块
- `registerMenuAction` / `registerStatusBarAction` / `registerHomeCard` —— 动作 / 状态栏 / 首页卡片
- `registerLaunchHook(hook)` —— 游戏启动前后钩子
- `registerUrlRewriteHook(hook)` —— URL 重写钩子（需 `NETWORK` 权限）
- `registerThemePack(pack)` —— 注册主题包
- `addEventListener` / `fireEvent` —— 事件订阅 / 发布
- `getDataDir()` / `getConfig` / `setConfig` —— 持久化数据
- `info` / `warn` / `error` —— 日志
- `newThread` / `threadFactory` —— 在插件 `ThreadGroup` 内创建守护线程（便于卸载时中断）
- `getService(type)` —— **遗留**方式：隔离 ClassLoader 会阻止访问 `com.pmcl.core.*`，
  **优先使用类型化 API**

### 6.3 隔离 ClassLoader

普通 JVM 插件运行在**独立的 `PluginIsolatingClassLoader`** 中（类来自 `classes/` + `lib/*.jar`）。
因此插件不应直接依赖 `com.pmcl.core.*` 类，而应通过 `PluginContext` 的类型化 API 访问宿主服务。
这保证了插件之间、插件与宿主之间的类隔离。

---

## 7. 权限声明（`PluginPermission`）

访问**敏感宿主服务**必须在描述符中显式声明权限（未知权限名在加载时被拒绝）。
普通 JVM 插件写在 `META-INF/pmcl-plugin.properties` 的 `plugin.permissions`；
嵌入 / 外部运行时插件写在 `plugin.xml` 的 `<info><permissions>`。

可用权限：

| 权限 | 能力 |
|------|------|
| `READ_ACCOUNTS` | 读取账号信息（不含 accessToken） |
| `WRITE_ACCOUNTS` | 修改账号 |
| `CONTROL_LAUNCH` | 启动 / 控制游戏、注册 LaunchHook |
| `KILL_PROCESS` | 杀死游戏进程 |
| `SELF_UPDATE` | 替换启动器 JAR |
| `MANAGE_PLUGINS` | 管理其他插件 |
| `MANAGE_INSTANCES` | 创建 / 重命名 / 删除实例 |
| `READ_MODS` | 扫描模组元数据 |
| `MANAGE_MODS` | 启用 / 禁用 / 删除模组、市场安装 |
| `MANAGE_MODPACKS` | 导入整合包 |
| `MANAGE_GAME_CONTENT` | 管理世界 / 资源包 / 光影 / 数据包 / 截图 |
| `READ_STATS` | 读取游玩统计 |
| `CONTROL_ROOMS` | 联机房间创建 / 加入 / 离开 |
| `MANAGE_VERSIONS` | 安装 / 管理版本与下载队列 |
| `MANAGE_SERVERS` | 管理收藏服务器 / 直连地址 |
| `READ_CRASH_LOGS` | 读取崩溃报告 |
| `CONTROL_MUSIC` | 控制宿主音乐播放器 |
| `WRITE_SETTINGS` | 写入宿主偏好（语言 / 主题） |
| `NETWORK` | 网络下载 / ping / HTTP / URL 重写 |
| `FILESYSTEM` | 读写本地文件系统 / NBT |

---

## 8. 嵌入模式（`embed`）

插件可要求 PMCL 把其 UI **嵌入主窗口**，而非弹出独立窗口。由 `plugin.xml` 的
`<embed>` 字段声明（v1.7+）。

### 8.1 `embed=web`

- 子进程（外部运行时）在本机监听一个 HTTP 端口并提供 Web UI。
- PMCL 分配空闲端口，通过 `--pmcl-web-port=<port>` 传给子进程；端口就绪后用内置
  **WebView** 把页面嵌入主窗口并注册为插件页面。
- **要求**：必须同时设置 `external-runtime` 和 `external-entry`。
- 通信：子进程与宿主通过 **JSON-RPC 2.0（stdin/stdout，一行一帧）** 交互。

### 8.2 `embed=window`

- 宿主启动插件声明的外部 GUI 应用本体（如 `*.app`），由 PMCL 作为**父窗口把该应用的真实窗口
  停靠（dock）进主窗的指定区域**，跟随主窗移动 / 缩放。
- 渲染的是**完整的真实窗口**（保留应用自身边框）。
- **要求**：只需 `external-entry` 指向应用本体（无需 `external-runtime`）。
- 当前仅 **macOS** 通过 AppleScript（System Events）实现窗口定位 / 尺寸停靠；
  跨进程限制使其无法成为无边框的「真子窗口」。
- 注意：需系统设置 → 隐私与安全性 → 辅助功能中给 PMCL 授权，否则 osascript 无法定位窗口。

---

## 9. 外部运行时（.NET / Python / Node.js）

插件可以是一个**外部运行时子进程**而非 JVM 插件，由 `plugin.xml` 声明：

| 字段 | 含义 |
|------|------|
| `external-runtime` | 运行时标识，如 `dotnet-8`、`python-3.12`、`node-22` |
| `external-entry` | 入口文件，相对包根（.NET: `X.dll`，Python: `main.py`，Node: `index.js`）|
| `external-restart` | 崩溃重启策略：`never` / `always` / `on-failure`（默认）|

- 宿主通过 `RuntimeDetection.detect(runtime)` 确认运行时可用。
- 进程通信统一走 **JSON-RPC 2.0 over stdin/stdout**（单一 stdout 读取端原则）。
- 外部运行时插件跳过隔离 ClassLoader 构建，由 `ExternalRuntimeBridge`（web）或
  `NativeDockBridge`（window）桥接。

---

## 10. 常见失败原因（排查清单）

| 现象 | 根因 | 修复 |
|------|------|------|
| 插件「没安装」、无报错 | 普通 JVM 插件缺 `META-INF/pmcl-plugin.properties` | 重新打包时保留该文件 |
| 加载报签名错误 | `.ppk` 未签名或部分 entry 未签名 | `jarsigner` 完整签名关键 entry |
| 报签名者不被信任 | 签名证书指纹不在信任列表 | 用受信任 keystore 重签，或加入 `trusted-signers.txt` |
| `validate()` 抛异常 | ID/版本/描述/主类不符格式（如 description > 256）| 按第 4 节修正 |
| `plugin.api-version` 不支持 | 版本不在 `1.0`–`1.7` | 修正 api-version |
| `embed=web` 但无 `external-runtime` | web 嵌入必须依赖外部运行时 | 补 `external-runtime` + `external-entry` |
| `embed=web`/`window` 但无 `external-entry` | 嵌入模式必须有入口 | 补 `external-entry` |
| 普通 JVM 插件无 `main="true"` 的 Kotlin 源 | 源码声明校验失败 | `<sources>` 至少含一个 `main="true"` |
| `plugin.xml` 含 `<!DOCTYPE>` | XXE 防护拒绝 | 删除 DOCTYPE |
| 窗口停靠不动（embed=window, macOS） | 未授权辅助功能 | 系统设置 → 隐私与安全性 → 辅助功能 给 PMCL 授权 |
| 依赖缺失 | `<dependencies>` 中插件未先加载 | 先安装 / 加载被依赖插件 |

---

## 11. 一个最小可加载插件清单

**`plugin.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<pmcl-plugin-package xmlns="https://pmcl.dev/plugin" format-version="1.0">
    <info>
        <id>hello-plugin</id>
        <name>Hello Plugin</name>
        <version>1.0.0</version>
        <author>You</author>
        <description>A minimal PMCL plugin example</description>
        <api-version>1.0</api-version>
        <main-class>com.example.HelloPlugin</main-class>
    </info>
    <sources>
        <kotlin>
            <file path="src/kt/com/example/HelloPlugin.kt" main="true"/>
        </kotlin>
    </sources>
    <versions>
        <version number="1.0.0" date="2026-08-04" author="You">Initial release.</version>
    </versions>
</pmcl-plugin-package>
```

**`META-INF/pmcl-plugin.properties`**

```properties
plugin.id=hello-plugin
plugin.name=Hello Plugin
plugin.version=1.0.0
plugin.author=You
plugin.description=A minimal PMCL plugin example
plugin.api-version=1.0
plugin.main-class=com.example.HelloPlugin
```

**`src/kt/com/example/HelloPlugin.kt`**

```kotlin
package com.example

import com.pmcl.plugin.PmclPlugin
import com.pmcl.plugin.PluginContext

class HelloPlugin : PmclPlugin {
    override val pluginId = "hello-plugin"
    override fun onEnable(ctx: PluginContext) {
        ctx.registerCommand("greet", "Greet someone") { args ->
            "Hello, ${args.firstOrNull() ?: "PMCL"}!"
        }
    }
}
```

> 该插件经 `jarsigner` 用受信任 keystore 签名、放入 `~/.pmcl/plugins/` 并在
> `plugins.json` 启用后，即可在 PMCL 终端通过 `plugin:hello-plugin:greet` 调用。
