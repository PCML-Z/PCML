package com.pmcl.ui.page

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.market.PmclPluginStoreClient
import com.pmcl.core.market.StorePlugin
import com.pmcl.ui.animation.MotionTokens
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.theme.glassContainerColor
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.widget.PmclLazyColumn
import com.pmcl.ui.widget.pmclVerticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

private const val STORE_PAGE_SIZE = 20
private val StoreFieldHeight = 36.dp
private val StoreFieldShape = RoundedCornerShape(8.dp)
private val StoreCardShape = RoundedCornerShape(12.dp)

@Composable
private fun Modifier.pluginStoreCard(): Modifier = fillMaxWidth().glassCardBorder(12.dp)

@Composable
fun PmclPluginStorePage(vm: LauncherViewModel, modifier: Modifier = Modifier) {
    val client = remember { PmclPluginStoreClient(vm.core.downloads()) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var pageIndex by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var plugins by remember { mutableStateOf<List<StorePlugin>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<StorePlugin?>(null) }
    var installing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusFailed by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun refreshInstalled() {
        installed = vm.core.plugins().loadedPlugins.associate { it.info.id to (it.info.version ?: "") }
    }

    fun load(page: Int) {
        scope.launch {
            loading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    client.list(query, page * STORE_PAGE_SIZE, STORE_PAGE_SIZE)
                }
                pageIndex = page
                total = result.total
                plugins = result.plugins
                refreshInstalled()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "?"
                plugins = emptyList()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshInstalled()
        load(0)
    }

    val pageCount = maxOf(1, kotlin.math.ceil(total / STORE_PAGE_SIZE.toDouble()).toInt())
    val current = selected

    Column(modifier) {
        if (current != null) {
            PluginStoreDetail(
                plugin = current,
                installedVersion = installed[current.id],
                installing = installing,
                status = status,
                statusFailed = statusFailed,
                onBack = {
                    selected = null
                    status = null
                    statusFailed = false
                },
                onOpenHome = {
                    val home = current.homepage
                    if (home.isNotBlank()) {
                        try {
                            Desktop.getDesktop().browse(URI(home))
                        } catch (e: Throwable) {
                            status = e.message
                            statusFailed = true
                        }
                    }
                },
                onInstall = {
                    if (installing) return@PluginStoreDetail
                    scope.launch {
                        installing = true
                        statusFailed = false
                        status = I18n.t("market.plugins.installing")
                        try {
                            withContext(Dispatchers.IO) { client.install(current, vm.core.plugins()) }
                            refreshInstalled()
                            status = I18n.t("market.plugins.install_ok", current.name)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            statusFailed = true
                            status = I18n.t("market.plugins.install_fail", e.message ?: "?")
                        } finally {
                            installing = false
                        }
                    }
                }
            )
        } else {
            Spacer(Modifier.height(12.dp))
            StoreSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = I18n.t("market.plugins.search_hint"),
                onSearch = { if (!loading) load(0) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            when {
                loading && plugins.isEmpty() -> {
                    StoreMessagePane(Modifier.weight(1f)) {
                        Text(I18n.t("common.loading"), color = MaterialTheme.colorScheme.outline)
                    }
                }
                error != null -> {
                    StoreMessagePane(Modifier.weight(1f)) {
                        Text(
                            I18n.t("market.plugins.error", error ?: ""),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                plugins.isEmpty() -> {
                    StoreMessagePane(Modifier.weight(1f)) {
                        Text(I18n.t("market.plugins.empty"), color = MaterialTheme.colorScheme.outline)
                    }
                }
                else -> {
                    PmclLazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        items(plugins, key = { it.id }) { plugin ->
                            PluginStoreRow(plugin, installed[plugin.id]) {
                                selected = plugin
                                status = null
                                statusFailed = false
                            }
                        }
                    }
                }
            }
            if (pageCount > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (pageIndex > 0 && !loading) load(pageIndex - 1) },
                        enabled = pageIndex > 0 && !loading
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                    Text(I18n.t("market.page_indicator", pageIndex + 1, pageCount))
                    IconButton(
                        onClick = { if (pageIndex + 1 < pageCount && !loading) load(pageIndex + 1) },
                        enabled = pageIndex + 1 < pageCount && !loading
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginStoreRow(plugin: StorePlugin, installedVersion: String?, onClick: () -> Unit) {
    val badge = installBadge(plugin.version, installedVersion)
    Card(
        modifier = Modifier.pluginStoreCard().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        ),
        shape = StoreCardShape,
        colors = glassCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = glassCardElevation()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PluginIcon(plugin, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val byline = listOf(plugin.version, plugin.author).filter { it.isNotBlank() }.joinToString(" · ")
                    if (byline.isNotBlank()) {
                        Text(
                            byline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (badge != null) {
                    StoreStatusPill(badge, emphasize = badge == I18n.t("market.plugins.update"))
                }
            }
            val line = plugin.summary.ifBlank { plugin.description }
            if (line.isNotBlank()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                pluginMetaLine(plugin),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PluginStoreDetail(
    plugin: StorePlugin,
    installedVersion: String?,
    installing: Boolean,
    status: String?,
    statusFailed: Boolean,
    onBack: () -> Unit,
    onOpenHome: () -> Unit,
    onInstall: () -> Unit
) {
    Column(Modifier.fillMaxSize().pmclVerticalScroll(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(I18n.t("market.back"))
        }
        Card(
            modifier = Modifier.pluginStoreCard(),
            shape = StoreCardShape,
            colors = glassCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = glassCardElevation()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PluginIcon(plugin, 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            plugin.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            pluginChips(plugin, installedVersion).forEach { chip ->
                                StoreChip(chip)
                            }
                        }
                    }
                }
                val body = plugin.description.ifBlank { plugin.summary }
                if (body.isNotBlank()) {
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    pluginMetaLine(plugin),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val badge = installBadge(plugin.version, installedVersion)
                    Button(onClick = onInstall, enabled = !installing, shape = RoundedCornerShape(8.dp)) {
                        if (installing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                installing -> I18n.t("market.plugins.installing")
                                badge == I18n.t("market.plugins.update") -> I18n.t("market.plugins.update")
                                installedVersion != null -> I18n.t("market.plugins.installed")
                                else -> I18n.t("market.plugins.install")
                            }
                        )
                    }
                    if (plugin.homepage.isNotBlank()) {
                        OutlinedButton(onClick = onOpenHome, shape = RoundedCornerShape(8.dp)) {
                            Text(I18n.t("market.plugins.homepage"))
                        }
                    }
                }
                if (!status.isNullOrBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginIcon(plugin: StorePlugin, size: androidx.compose.ui.unit.Dp) {
    val image = rememberStoreIcon(plugin.iconUrl)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)), shape),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                image,
                contentDescription = plugin.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(2.dp)
            )
        } else {
            Text(
                plugin.name.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun StoreStatusPill(label: String, emphasize: Boolean) {
    val color = if (emphasize) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun StoreChip(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StoreMessagePane(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Surface(
        color = glassSurfaceVariantColor(),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun StoreSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
        tween(MotionTokens.DURATION_SHORT),
        label = "pluginSearchBorder"
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier.height(StoreFieldHeight),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(StoreFieldShape)
                    .background(glassContainerColor(MaterialTheme.colorScheme.surface))
                    .border(1.dp, border, StoreFieldShape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        }
    )
}

@Composable
private fun rememberStoreIcon(url: String): ImageBitmap? {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isBlank()) {
            image = null
            return@LaunchedEffect
        }
        image = withContext(Dispatchers.IO) {
            try {
                val bytes = com.pmcl.ui.util.SafeUrlFetcher.fetchBytes(url)
                com.pmcl.ui.util.decodeSampledBitmap(bytes, 128)
            } catch (_: Throwable) {
                null
            }
        }
    }
    return image
}

private fun pluginChips(plugin: StorePlugin, installedVersion: String?): List<String> {
    val chips = ArrayList<String>(4)
    if (plugin.version.isNotBlank()) chips.add(plugin.version)
    if (plugin.author.isNotBlank()) chips.add(plugin.author)
    if (plugin.packageType.isNotBlank()) chips.add(plugin.packageType.uppercase())
    val badge = installBadge(plugin.version, installedVersion)
    if (badge != null) chips.add(badge)
    return chips
}

private fun pluginMetaLine(plugin: StorePlugin): String {
    val parts = ArrayList<String>(3)
    parts.add(I18n.t("market.plugins.downloads", formatCount(plugin.downloads)))
    val updated = plugin.updatedAt.trim().take(10)
    if (updated.isNotBlank()) parts.add(updated)
    val size = formatSize(plugin.size)
    if (size.isNotBlank()) parts.add(size)
    return parts.joinToString(" · ")
}

private fun formatCount(n: Long): String = "%,d".format(n)

private fun formatSize(n: Long): String {
    if (n <= 0L) return ""
    val mb = n / (1024.0 * 1024.0)
    return if (mb >= 0.1) String.format("%.1f MB", mb) else String.format("%.0f KB", n / 1024.0)
}

private fun installBadge(remote: String, installed: String?): String? {
    if (installed == null) return null
    return if (remote.isNotBlank() && remote != installed) I18n.t("market.plugins.update")
    else I18n.t("market.plugins.installed")
}
