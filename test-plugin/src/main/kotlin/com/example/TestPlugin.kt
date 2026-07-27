package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pmcl.plugin.HomeCard
import com.pmcl.plugin.LaunchHook
import com.pmcl.plugin.PmclPlugin
import com.pmcl.plugin.PluginContext
import com.pmcl.plugin.api.DialogKind
import com.pmcl.plugin.api.NotificationLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TestPlugin : PmclPlugin {
    override val pluginId = "test-plugin"

    override fun onEnable(ctx: PluginContext) {
        ctx.info("Test plugin enabled (API 1.6)!")

        ctx.i18n().registerStrings(
            "zh_CN",
            mapOf(
                "test-plugin.hello" to "你好，插件 API 1.6",
                "test-plugin.card" to "测试主页卡片",
            ),
        )
        ctx.i18n().registerStrings(
            "en_US",
            mapOf(
                "test-plugin.hello" to "Hello, plugin API 1.6",
                "test-plugin.card" to "Test home card",
            ),
        )

        ctx.registerCommand("greet", "Greet someone") { args ->
            val name = args.firstOrNull() ?: "World"
            "Hello, $name! ${ctx.i18n().t("test-plugin.hello")}"
        }

        ctx.registerCommand("list-versions", "List local versions via plugin API") { _ ->
            val versions = ctx.versions().listLocalVersions()
            if (versions.isEmpty()) {
                "No local versions found."
            } else {
                "Found ${versions.size} local versions:\n" +
                    versions.take(8).joinToString("\n") {
                        "  - ${it.id}" + if (it.launchable) "" else " (incomplete)"
                    }
            }
        }

        ctx.registerCommand("remote-versions", "List remote versions (NETWORK)") { args ->
            try {
                val lim = args.firstOrNull()?.toIntOrNull() ?: 5
                val list = ctx.versions().listRemoteVersions(lim)
                if (list.isEmpty()) "No remote versions"
                else list.joinToString("\n") { "  - ${it.id} (${it.type})" }
            } catch (e: Throwable) {
                "Failed: ${e.message}"
            }
        }

        ctx.registerCommand("whoami-plugin", "Show selected account via AccountsApi") { _ ->
            val acc = try {
                ctx.accounts().getSelectedAccount()
            } catch (e: SecurityException) {
                return@registerCommand "Missing READ_ACCOUNTS: ${e.message}"
            }
            if (acc == null) "No selected account"
            else "Selected: ${acc.username} (${acc.type})"
        }

        ctx.registerCommand("add-offline", "Add an offline account") { args ->
            val name = args.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: return@registerCommand "Usage: add-offline <username>"
            try {
                val uuid = ctx.accounts().addOfflineAccount(name)
                "Added offline account $name ($uuid)"
            } catch (e: Throwable) {
                "Failed: ${e.message}"
            }
        }

        ctx.registerCommand("http-get", "HTTP GET via HttpApi") { args ->
            val url = args.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: return@registerCommand "Usage: http-get <url>"
            try {
                val resp = ctx.http().request("GET", url)
                "HTTP ${resp.statusCode} (${resp.body.length} chars)\n" + resp.body.take(400)
            } catch (e: Throwable) {
                "Failed: ${e.message}"
            }
        }

        ctx.registerCommand("fs-write", "Write a note into plugin data dir") { args ->
            val text = args.joinToString(" ").ifBlank { "hello from test-plugin" }
            try {
                val path = ctx.filesystem().resolveData("note.txt")
                ctx.filesystem().writeText(path, text)
                "Wrote ${path}: ${text.take(80)}"
            } catch (e: Throwable) {
                "Failed: ${e.message}"
            }
        }

        ctx.registerCommand("lang", "Show host language via I18nApi") { _ ->
            "Language: ${ctx.i18n().currentLanguage()} | sample: ${ctx.i18n().t("nav.settings")} | plugin: ${ctx.i18n().t("test-plugin.hello")}"
        }

        ctx.registerCommand("worlds", "List worlds via GameContentApi") { _ ->
            val worlds = ctx.gameContent().listWorlds()
            if (worlds.isEmpty()) "No worlds"
            else worlds.take(10).joinToString("\n") { "  - ${it.displayName.ifBlank { it.name }}" }
        }

        ctx.registerCommand("stats", "Show overall play stats") { _ ->
            try {
                val o = ctx.stats().overall(30)
                "Sessions=${o.totalSessions}, durationMs=${o.totalDurationMs}, versions=${o.versions.size}"
            } catch (e: SecurityException) {
                "Missing READ_STATS: ${e.message}"
            }
        }

        ctx.registerCommand("room", "Show multiplayer room state") { _ ->
            try {
                val s = ctx.rooms().state()
                "state=${s.state} backend=${s.backend} inRoom=${s.inRoom} vip=${s.virtualIp}"
            } catch (e: Throwable) {
                "Rooms unavailable: ${e.message}"
            }
        }

        ctx.registerCommand("host", "Show host metrics") { _ ->
            val m = ctx.javaRuntimes().hostMetrics()
            "mem=${m.availableMemoryMb}/${m.totalMemoryMb}MB cores=${m.cpuLogicalCores} gpu=${m.primaryGpuName}"
        }

        ctx.registerCommand("queue", "Show download queue summary") { _ ->
            val s = ctx.downloadQueue().summary()
            "queued=${s.queued} running=${s.running} done=${s.done} failed=${s.failed}"
        }

        ctx.registerCommand("servers", "List favorite servers") { _ ->
            val list = ctx.servers().listFavorites()
            if (list.isEmpty()) "No favorites"
            else list.joinToString("\n") { "  - ${it.name} ${it.host}:${it.port}" }
        }

        ctx.registerCommand("crashes", "List crash reports") { _ ->
            try {
                val reports = ctx.crashLogs().listReports()
                if (reports.isEmpty()) "No crash reports"
                else reports.take(5).joinToString("\n") { r ->
                    "  - ${r.filePath.ifBlank { "(text)" }} causes=${r.causes.size}"
                }
            } catch (e: SecurityException) {
                "Missing READ_CRASH_LOGS: ${e.message}"
            }
        }

        ctx.registerCommand("now-playing", "Show music playback state") { _ ->
            val n = ctx.music().nowPlaying()
            "state=${n.state} title=${n.title} vol=${n.volume}"
        }

        ctx.registerCommand("plugins-list", "List loaded plugins") { _ ->
            ctx.plugins().listPlugins().joinToString("\n") {
                "  - ${it.id} ${it.version} [${it.state}] enabled=${it.enabled}"
            }
        }

        ctx.registerPage("demo", "Test Plugin") {
            DemoPage(ctx)
        }

        ctx.registerSettingsSection("demo-settings", "Test Plugin") {
            Column(Modifier.padding(4.dp)) {
                Text("Typed plugin APIs are active (1.6).")
                Button(onClick = {
                    ctx.ui().notify("Test Plugin", "Hello from settings!", NotificationLevel.SUCCESS)
                }) {
                    Text("Send notification")
                }
                Button(onClick = {
                    ctx.ui().setNavBadge("plugins", "1.6")
                    ctx.ui().notify("Badge", "Set plugins badge", NotificationLevel.INFO)
                }) {
                    Text("Set plugins nav badge")
                }
            }
        }

        ctx.registerMenuAction("ping", "Test Plugin Ping", "Show a dialog from Plugins page") {
            ctx.ui().showDialog(
                title = "Test Plugin",
                message = "Menu action works. Locale=${ctx.i18n().currentLanguage()}",
                kind = DialogKind.INFO,
            )
        }

        ctx.registerMenuAction("copy-lang", "Copy language code", "Copy host language to clipboard") {
            ctx.ui().copyToClipboard(ctx.i18n().currentLanguage())
            ctx.ui().notify("Clipboard", "Language code copied", NotificationLevel.SUCCESS)
        }

        ctx.registerMenuAction("ask-name", "Ask name", "Input dialog demo") {
            ctx.ui().showInputDialog(
                title = "Your name",
                message = "Enter a name to greet",
                defaultValue = "Steve",
            ) { value ->
                if (value == null) {
                    ctx.ui().notify("Input", "Cancelled", NotificationLevel.WARN)
                } else {
                    ctx.ui().notify("Input", "Hello, $value!", NotificationLevel.SUCCESS)
                }
            }
        }

        ctx.registerStatusBarAction("sb-ping", "TP Ping", "Status bar demo") {
            ctx.ui().notify("StatusBar", "Test plugin status action", NotificationLevel.INFO)
        }

        ctx.registerHomeCard(
            HomeCard(
                id = "demo-card",
                title = ctx.i18n().t("test-plugin.card"),
                subtitle = "API 1.6 home card",
                order = 10,
                content = {
                    Column {
                        Text(ctx.i18n().t("test-plugin.hello"))
                        Button(onClick = {
                            ctx.ui().notify("HomeCard", "Clicked!", NotificationLevel.SUCCESS)
                        }) {
                            Text("Ping")
                        }
                    }
                },
            ),
        )

        ctx.registerUrlRewriteHook { url ->
            // Demo only: leave URLs unchanged (real plugins can remap CDNs here)
            url
        }

        ctx.registerLaunchHook(object : LaunchHook {
            override fun beforeLaunch(versionId: String, accountName: String): Boolean = true
            override fun contributeJvmArgs(versionId: String, accountName: String): List<String> =
                listOf("-Dpmcl.testplugin=1.6")
            override fun contributeEnv(versionId: String, accountName: String): Map<String, String> =
                mapOf("PMCL_PLUGIN_TEST" to "1.6")
        })

        ctx.scheduler().scheduleOnce(2_000) {
            ctx.info("Scheduler: one-shot task fired after 2s")
        }

        ctx.addEventListener { event ->
            when (event.type) {
                "version_installed" -> ctx.info("Saw version_installed")
                "game_launched" -> ctx.info("Saw game_launched")
                "room_created", "room_joined" -> ctx.info("Saw ${event.type}")
                "navigation" -> ctx.info("Saw navigation")
                "settings_changed", "account_added", "account_removed",
                "url_rewritten", "mod_toggled" -> ctx.info("Saw ${event.type}")
            }
        }

        ctx.info("Test Plugin registered commands, page, settings, home card, hooks, scheduler, listeners")
    }

    override fun onDisable() {
        println("[test-plugin] Goodbye!")
    }
}

@Composable
private fun DemoPage(ctx: PluginContext) {
    var newsPreview by remember { mutableStateOf<String?>(null) }
    var extra by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(16.dp)) {
        Text("Test Plugin demo page (API 1.6)")
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val n = ctx.versions().listLocalVersionIds().size
            ctx.ui().notify("Versions", "Found $n local version id(s)", NotificationLevel.INFO)
        }) {
            Text("Notify version count")
        }
        Button(onClick = {
            val m = ctx.javaRuntimes().hostMetrics()
            extra = "RAM ${m.availableMemoryMb}/${m.totalMemoryMb} MB · ${m.cpuLogicalCores} cores"
        }) {
            Text("Host metrics")
        }
        Button(onClick = {
            try {
                val s = ctx.rooms().state()
                extra = "Room ${s.state} / ${s.backend} inRoom=${s.inRoom}"
            } catch (e: Throwable) {
                extra = "Rooms: ${e.message}"
            }
        }) {
            Text("Room state")
        }
        Button(onClick = {
            val s = ctx.downloadQueue().summary()
            extra = "Queue q=${s.queued} run=${s.running} done=${s.done}"
        }) {
            Text("Queue summary")
        }
        Button(onClick = {
            val list = ctx.servers().listFavorites()
            extra = "Favorites: ${list.size}" + if (list.isNotEmpty()) {
                " · ${list.first().host}:${list.first().port}"
            } else ""
        }) {
            Text("List servers")
        }
        Button(onClick = {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reports = ctx.crashLogs().listReports()
                    extra = if (reports.isEmpty()) "No crash reports"
                    else "Crashes=${reports.size} first=${reports.first().causes.firstOrNull() ?: "(no cause)"}"
                } catch (e: Throwable) {
                    extra = "Crash scan: ${e.message}"
                }
            }
        }) {
            Text("Scan crashes")
        }
        Button(onClick = {
            val n = ctx.music().nowPlaying()
            extra = "Music ${n.state}: ${n.title.ifBlank { "(none)" }} @${n.volume}"
        }) {
            Text("Now playing")
        }
        Button(onClick = {
            ctx.ui().showInputDialog("Input demo", "Type something", "hello") { v ->
                extra = if (v == null) "Input cancelled" else "You typed: $v"
            }
        }) {
            Text("Input dialog")
        }
        Button(onClick = {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val resp = ctx.http().request("GET", "https://httpbin.org/get")
                    extra = "HTTP ${resp.statusCode}: ${resp.body.take(200)}"
                } catch (e: Throwable) {
                    extra = "HTTP failed: ${e.message}"
                }
            }
        }) {
            Text("HTTP GET demo")
        }
        Button(onClick = {
            val path = ctx.filesystem().resolveData("demo.txt")
            ctx.filesystem().writeText(path, "written at ${System.currentTimeMillis()}")
            extra = "Wrote $path"
        }) {
            Text("Write plugin file")
        }
        Button(onClick = {
            ctx.ui().setNavBadge("plugins", "!")
            extra = "Badge set on plugins"
        }) {
            Text("Set nav badge")
        }
        Button(onClick = { ctx.ui().navigate("settings") }) {
            Text("Open Settings")
        }
        Button(onClick = {
            ctx.ui().showDialog(
                title = "Confirm demo",
                message = "Click OK or Cancel — result goes to a snackbar.",
                kind = DialogKind.CONFIRM,
                onResult = { ok ->
                    ctx.ui().notify(
                        "Dialog",
                        if (ok) "Confirmed" else "Cancelled",
                        if (ok) NotificationLevel.SUCCESS else NotificationLevel.WARN,
                    )
                },
            )
        }) {
            Text("Show confirm dialog")
        }
        Button(onClick = {
            ctx.ui().pickFile("Pick a jar/zip", "jar;zip", false) { path ->
                extra = if (path == null) "File pick cancelled" else "Picked: $path"
            }
        }) {
            Text("Pick file")
        }
        Button(onClick = {
            ctx.ui().showProgress("demo", "Demo progress", 0.35)
            CoroutineScope(Dispatchers.IO).launch {
                Thread.sleep(800)
                ctx.ui().showProgress("demo", "Demo progress", 0.8)
                Thread.sleep(600)
                ctx.ui().dismissProgress("demo")
                ctx.ui().notify("Progress", "Done", NotificationLevel.SUCCESS)
            }
        }) {
            Text("Show progress")
        }
        Button(onClick = {
            newsPreview = "Loading…"
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val items = ctx.news().fetchNews(3)
                    newsPreview = if (items.isEmpty()) {
                        "No news"
                    } else {
                        items.joinToString("\n") { "• ${it.title}" }
                    }
                } catch (e: Throwable) {
                    newsPreview = "News failed: ${e.message}"
                }
            }
        }) {
            Text("Fetch news (NETWORK)")
        }
        Button(onClick = {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val hits = ctx.modMarket().search("sodium", "1.20.1", "fabric", 5)
                    extra = if (hits.isEmpty()) "No market hits"
                    else hits.joinToString("\n") { "• [${it.source}] ${it.name}" }
                } catch (e: Throwable) {
                    extra = "Market failed: ${e.message}"
                }
            }
        }) {
            Text("Search Modrinth (NETWORK)")
        }
        Button(onClick = {
            val worlds = ctx.gameContent().listWorlds()
            extra = "Worlds: ${worlds.size}"
        }) {
            Text("Count worlds")
        }
        newsPreview?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
        }
        extra?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
        }
    }
}
