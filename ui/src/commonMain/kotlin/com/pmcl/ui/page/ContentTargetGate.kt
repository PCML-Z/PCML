package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.instance.InstanceInfo
import com.pmcl.ui.theme.PmclCard
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.viewmodel.ContentManageTarget
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.widget.VersionTypeIcon
import com.pmcl.ui.widget.pmclVerticalScroll

/**
 * 模组、整合包、光影、投影、资源包、数据包、配置文件先选定版本和实例，再进入原来的操作。
 * 选定结果只放在内存里，几个分区共用，不写入偏好。
 */
@Composable
fun ContentTargetGate(vm: LauncherViewModel, content: @Composable () -> Unit) {
    val target by vm.contentTarget.collectAsState()
    val chosen = target
    if (chosen == null) {
        ContentTargetPicker(vm)
    } else {
        Column(Modifier.fillMaxSize()) {
            ContentTargetBar(vm, chosen)
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun ContentTargetPicker(vm: LauncherViewModel) {
    val versions by vm.localVersions.collectAsState()
    val instances by vm.instances.collectAsState()
    var versionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.loadInstances()
        if (vm.localVersions.value.isEmpty()) vm.refreshLocalVersions()
    }

    val pickedVersion = versionId
    val matched = if (pickedVersion == null) emptyList()
    else instances.filter { it.baseVersionId == pickedVersion || it.instanceId == pickedVersion }

    Column(
        Modifier.fillMaxSize().padding(16.dp).pmclVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (pickedVersion == null) {
            Text(
                I18n.t("content.target.hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                I18n.t("content.target.version"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (versions.isEmpty()) {
                PmclCard(Modifier.fillMaxWidth()) {
                    Text(
                        I18n.t("content.target.no_version"),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                versions.forEach { id ->
                    TargetCard(
                        title = id,
                        icon = Icons.Filled.Dashboard,
                        versionType = versionIconType(id),
                        onOpen = { versionId = id }
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { versionId = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = I18n.t("content.target.back"))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        pickedVersion,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        I18n.t("content.target.instance_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (matched.isEmpty()) {
                Text(
                    I18n.t("content.target.no_instance"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            TargetCard(
                title = I18n.t("content.target.version_dir"),
                hint = I18n.t("content.target.version_dir_hint"),
                icon = Icons.Filled.Folder,
                onOpen = { vm.setContentManageTarget(pickedVersion, "") }
            )
            matched.forEach { info ->
                val name = info.name?.takeIf { it.isNotBlank() } ?: info.instanceId
                TargetCard(
                    title = name,
                    hint = info.instanceId,
                    icon = if (info.type == InstanceInfo.Type.MODPACK) Icons.Filled.Inventory2
                    else Icons.Filled.Dashboard,
                    onOpen = { vm.setContentManageTarget(pickedVersion, info.instanceId) }
                )
            }
        }
    }
}

private fun versionIconType(id: String): String? = when {
    id.matches(Regex("""(?i)\d{2}w\d{2}[a-z]""")) -> "snapshot"
    id.startsWith("a1.") || id.startsWith("a0.") -> "old_alpha"
    id.startsWith("b1.") || id.startsWith("b0.") -> "old_beta"
    id.matches(Regex("""\d+\.\d+.*""")) -> "release"
    else -> null
}

@Composable
private fun TargetCard(
    title: String,
    icon: ImageVector,
    onOpen: () -> Unit,
    hint: String? = null,
    versionType: String? = null
) {
    val colors = MaterialTheme.colorScheme
    PmclCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        colors = glassCardColors()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(colors.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                if (versionType != null) {
                    VersionTypeIcon(versionType, Modifier.size(24.dp))
                } else {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = colors.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!hint.isNullOrBlank() && hint != title) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.outline,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ContentTargetBar(vm: LauncherViewModel, target: ContentManageTarget) {
    val instances by vm.instances.collectAsState()
    val instanceLabel = if (target.instanceId.isBlank()) {
        I18n.t("content.target.version_dir")
    } else {
        instances.firstOrNull { it.instanceId == target.instanceId }
            ?.name?.takeIf { it.isNotBlank() }
            ?: target.instanceId
    }
    val icon = if (target.instanceId.isBlank()) Icons.Filled.Folder else Icons.Filled.Dashboard
    PmclCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    I18n.t("content.target.managing"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    I18n.t("content.target.current", target.versionId, instanceLabel),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { vm.clearContentManageTarget() }) {
                Text(I18n.t("content.target.change"))
            }
        }
    }
}
