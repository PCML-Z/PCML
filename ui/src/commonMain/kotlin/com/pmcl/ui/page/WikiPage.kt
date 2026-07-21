package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.web.WikiBrowser
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * Wiki 浏览页：提供搜索入口 + Modrinth/CurseForge/Minecraft Wiki 链接。
 * 点击链接通过系统浏览器打开（启动器内嵌 WebView 需 JavaFX，暂不集成）。
 */
@Composable
fun WikiPage(vm: LauncherViewModel) {
    val status by vm.status.collectAsState()
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Mod Wiki", style = MaterialTheme.typography.headlineSmall,
             fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(I18n.t("wiki.title"),
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(I18n.t("wiki.search_placeholder")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        Text(I18n.t("wiki.shortcuts"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        val wikiLinks = remember(query) {
            listOf(
                I18n.t("wiki.modrinth") to (if (query.isNotBlank()) "https://modrinth.com/mod?q=" +
                        java.net.URLEncoder.encode(query, "UTF-8") else "https://modrinth.com/mods"),
                I18n.t("wiki.curseforge") to (if (query.isNotBlank())
                    "https://www.curseforge.com/minecraft/mc-mods/search?search=" +
                        java.net.URLEncoder.encode(query, "UTF-8")
                    else "https://www.curseforge.com/minecraft/mc-mods"),
                I18n.t("wiki.mc_wiki") to (if (query.isNotBlank())
                    WikiBrowser.minecraftWikiSearchUrl(query) else "https://minecraft.wiki/"),
                I18n.t("wiki.google") to (if (query.isNotBlank())
                    WikiBrowser.searchUrl(query) else "https://www.google.com/"),
                "Fabric Wiki" to "https://fabricmc.net/wiki/",
                I18n.t("wiki.forge_docs") to "https://docs.minecraftforge.net/",
                I18n.t("wiki.mojang") to "https://www.minecraft.net/"
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(wikiLinks, key = { it.first }) { (label, url) ->
                Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f))
                        Button(onClick = { vm.openWikiUrl(url) }) { Text(I18n.t("common.open")) }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(I18n.t("wiki.status_format", status),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
        if (!WikiBrowser.isSupported()) {
            Spacer(Modifier.height(4.dp))
            Text(I18n.t("wiki.unsupported"),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.error)
        }
    }
}
