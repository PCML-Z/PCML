package com.pmcl.ui.page

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pmcl.core.i18n.I18n
import com.pmcl.core.runtime.FoojayDisco
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun JavaDownloadDialog(
    vm: LauncherViewModel,
    downloading: Boolean,
    onDismiss: () -> Unit
) {
    var vendor by remember { mutableStateOf<FoojayDisco.Vendor?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var builds by remember { mutableStateOf<List<FoojayDisco.Build>>(emptyList()) }
    val scroll = rememberScrollState()

    LaunchedEffect(vendor?.id) {
        scroll.scrollTo(0)
        val selected = vendor ?: return@LaunchedEffect
        loading = true
        error = ""
        builds = emptyList()
        try {
            builds = withContext(Dispatchers.IO) { vm.listFoojayBuilds(selected.id) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: I18n.t("common.unknown")
        } finally {
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.width(720.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        I18n.t("settings.java_download_open"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, I18n.t("common.cancel"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                val current = vendor
                if (current == null) {
                    Text(
                        I18n.t("settings.java_pick_vendor"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VendorMark(current, Modifier.height(18.dp).width(if (current.id == "zulu") 52.dp else 22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            I18n.t("settings.java_pick_version", current.brand),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val selected = vendor
                    if (selected == null) {
                        FoojayDisco.vendors().chunked(3).forEach { row ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { item ->
                                    VendorCell(Modifier.weight(1f), item, !downloading) {
                                        vendor = item
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } else if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(I18n.t("settings.java_loading_versions"))
                        }
                    } else if (error.isNotEmpty()) {
                        Text(
                            I18n.t("settings.java_versions_failed", error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else if (builds.isEmpty()) {
                        Text(
                            I18n.t("settings.java_no_versions"),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        builds.chunked(4).forEach { row ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { build ->
                                    VersionCell(Modifier.weight(1f), build, !downloading) {
                                        onDismiss()
                                        vm.downloadFoojayJava(selected.id, build)
                                    }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (vendor != null) {
                        OutlinedButton(onClick = { vendor = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("settings.java_back_vendors"))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("common.cancel"))
                    }
                }
            }
        }
    }
}

@Composable
private fun VendorMark(vendor: FoojayDisco.Vendor, modifier: Modifier) {
    Image(
        painter = painterResource(vendorIcon(vendor.id)),
        contentDescription = vendor.brand,
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}

private fun vendorIcon(id: String): String = when (id) {
    "corretto" -> "java_corretto.png"
    "zulu" -> "java_zulu.png"
    "liberica" -> "java_liberica.png"
    "temurin" -> "java_temurin.png"
    "graalvm_community" -> "java_graalvm.png"
    "semeru" -> "java_semeru.png"
    "jetbrains" -> "java_jetbrains.png"
    "microsoft" -> "java_microsoft.png"
    "oracle_open_jdk" -> "java_oracle.png"
    "sap_machine" -> "java_sap.png"
    else -> "java_cup.png"
}

@Composable
private fun VendorCell(
    modifier: Modifier,
    vendor: FoojayDisco.Vendor,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.heightIn(min = 72.dp).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
            Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VendorMark(
                vendor,
                Modifier.size(width = if (vendor.id == "zulu") 58.dp else 36.dp, height = 32.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    vendor.brand,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    vendor.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VersionCell(
    modifier: Modifier,
    build: FoojayDisco.Build,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource("java_cup.png"),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.width(6.dp))
            Text("Java ${build.major}", fontWeight = FontWeight.Medium)
        }
    }
}
