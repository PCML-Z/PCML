package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.instance.InstanceInfo
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.awt.Desktop
import java.awt.FileDialog
import java.io.File

/**
 * 独立实例管理页面（Prism/MultiMC 风格）。
 *
 * 实例与版本 ID 解耦：同一个基础版本（如 Fabric-1.20.1）可以创建多个实例，
 * 每个实例有独立的 mods/saves/config 目录，互不干扰。
 *
 * 实例存储于 ~/.pmcl/instances/<instanceId>/，向后兼容旧版 modpack.json。
 */
@Composable
fun InstancesPage(vm: LauncherViewModel) {
    val instances by vm.instances.collectAsState()
    val status by vm.status.collectAsState()
    val instanceLaunching by vm.instanceLaunching.collectAsState()
    val localInfos by vm.localVersionInfos.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<InstanceInfo?>(null) }
    var copyTarget by remember { mutableStateOf<InstanceInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<InstanceInfo?>(null) }
    var accountTarget by remember { mutableStateOf<InstanceInfo?>(null) }
    val accounts by vm.accounts.collectAsState()

    // 首次进入加载实例列表
    LaunchedEffect(Unit) { vm.loadInstances() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 顶部标题栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                I18n.t("instance.title"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.loadInstances() }) {
                Icon(Icons.Filled.Refresh, contentDescription = I18n.t("common.refresh"))
            }
            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("instance.create"))
            }
        }

        Spacer(Modifier.height(8.dp))

        // 状态提示
        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        if (instances.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Dashboard,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        I18n.t("instance.empty"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        I18n.t("instance.empty_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    items = instances,
                    key = { it.getInstanceId() }
                ) { info ->
                    InstanceCard(
                        info = info,
                        isLaunching = instanceLaunching == info.getInstanceId(),
                        boundAccountName = vm.getBoundAccount(info)?.getUsername(),
                        vm = vm,
                        onLaunch = { vm.launchInstance(info.getInstanceId()) },
                        onCopy = { copyTarget = info },
                        onRename = { renameTarget = info },
                        onDelete = { deleteTarget = info },
                        onBindAccount = { accountTarget = info },
                        onOpenDir = {
                            try {
                                val dir = info.getInstanceDir()
                                if (dir != null && java.nio.file.Files.isDirectory(dir)) {
                                    Desktop.getDesktop().open(dir.toFile())
                                }
                            } catch (_: Throwable) {}
                        }
                    )
                }
            }
        }
    }

    // 创建实例对话框
    if (showCreateDialog) {
        CreateInstanceDialog(
            localVersionIds = localInfos.map { it.getId() }.filter { it.isNotEmpty() },
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, version, loader, loaderVersion ->
                vm.createInstance(name, version, loader, loaderVersion)
                showCreateDialog = false
            }
        )
    }

    // 重命名对话框
    renameTarget?.let { target ->
        RenameDialog(
            title = I18n.t("instance.rename_title"),
            initialName = target.getName(),
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                vm.renameInstance(target.getInstanceId(), newName)
                renameTarget = null
            }
        )
    }

    // 复制对话框
    copyTarget?.let { target ->
        RenameDialog(
            title = I18n.t("instance.copy_title"),
            initialName = target.getName() + " (copy)",
            onDismiss = { copyTarget = null },
            onConfirm = { newName ->
                vm.copyInstance(target.getInstanceId(), newName)
                copyTarget = null
            }
        )
    }

    // 删除确认对话框
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(I18n.t("instance.delete_title")) },
            text = {
                Text(I18n.t("instance.delete_confirm", arrayOf(target.getName())))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteInstance(target.getInstanceId())
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(I18n.t("common.delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(I18n.t("common.cancel"))
                }
            }
        )
    }

    // 绑定账户对话框
    accountTarget?.let { target ->
        BindAccountDialog(
            instanceName = target.getName(),
            accounts = accounts,
            selectedUuid = target.getBoundAccountUuid(),
            onDismiss = { accountTarget = null },
            onConfirm = { uuid ->
                vm.bindAccountToInstance(target.getInstanceId(), uuid)
                accountTarget = null
            }
        )
    }
}

/**
 * 单个实例卡片：显示名称、版本、加载器、游玩信息，提供操作按钮。
 */
@Composable
private fun InstanceCard(
    info: InstanceInfo,
    isLaunching: Boolean,
    boundAccountName: String?,
    vm: LauncherViewModel,
    onLaunch: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onBindAccount: () -> Unit,
    onOpenDir: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标：优先显示自定义图标，无则按类型显示 Material 图标
            val iconFile = remember(info) { vm.getInstanceIconFile(info) }
            if (iconFile != null) {
                val bitmap = remember(iconFile) {
                    try {
                        iconFile.toFile().inputStream().use { loadImageBitmap(it) }
                    } catch (_: Throwable) { null }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = if (info.getType() == InstanceInfo.Type.MODPACK)
                            Icons.Filled.Inventory2 else Icons.Filled.Dashboard,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Icon(
                    imageVector = if (info.getType() == InstanceInfo.Type.MODPACK)
                        Icons.Filled.Inventory2 else Icons.Filled.Dashboard,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))

            // 信息列
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        info.getName(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    // 类型标签
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (info.getType() == InstanceInfo.Type.MODPACK)
                                    I18n.t("instance.type_modpack")
                                else I18n.t("instance.type_custom"),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(22.dp)
                    )
                }
                Spacer(Modifier.height(2.dp))
                // 版本与加载器
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val baseVer = info.getBaseVersionId()
                    if (baseVer.isNullOrEmpty()) {
                        Text(
                            I18n.t("instance.missing_version"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            baseVer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    info.getLoader()?.let { loader ->
                        if (loader.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "· $loader" + (info.getLoaderVersion()?.let { " $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // 绑定账户
                if (!boundAccountName.isNullOrEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            boundAccountName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                // 游玩信息
                if (info.getLastPlayedAt() > 0 || info.getTotalPlayTimeSeconds() > 0) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (info.getLastPlayedAt() > 0) {
                            Text(
                                I18n.t("instance.last_played", arrayOf(formatTime(info.getLastPlayedAt()))),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        } else {
                            Text(
                                I18n.t("instance.never_played"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        if (info.getTotalPlayTimeSeconds() > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                I18n.t("instance.play_time", arrayOf(formatDuration(info.getTotalPlayTimeSeconds()))),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // 操作按钮组
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 打开目录
                IconButton(onClick = onOpenDir, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Folder, contentDescription = I18n.t("instance.open_dir"),
                        modifier = Modifier.size(18.dp))
                }
                // 绑定账户
                IconButton(onClick = onBindAccount, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Person, contentDescription = I18n.t("instance.bind_account"),
                        modifier = Modifier.size(18.dp),
                        tint = if (boundAccountName.isNullOrEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                               else MaterialTheme.colorScheme.primary)
                }
                // 更换图标
                IconButton(onClick = {
                    // 弹出文件选择器
                    val fd = FileDialog(java.awt.Frame(), I18n.t("instance.select_icon"), FileDialog.LOAD)
                    fd.setFilenameFilter { _, name -> name.lowercase().matches(Regex(".*\\.(png|jpg|jpeg|gif|webp)$")) }
                    fd.isVisible = true
                    val selectedFile = fd.file
                    val selectedDir = fd.directory
                    if (selectedFile != null && selectedDir != null) {
                        val path = java.nio.file.Paths.get(selectedDir, selectedFile)
                        vm.setInstanceIcon(info.getInstanceId(), path)
                    }
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Image, contentDescription = I18n.t("instance.change_icon"),
                        modifier = Modifier.size(18.dp),
                        tint = if (info.getIconPath().isNullOrEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                               else MaterialTheme.colorScheme.primary)
                }
                // 复制
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = I18n.t("instance.copy"),
                        modifier = Modifier.size(18.dp))
                }
                // 重命名
                IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = I18n.t("instance.rename"),
                        modifier = Modifier.size(18.dp))
                }
                // 删除
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = I18n.t("instance.delete"),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
                // 启动按钮
                Button(
                    onClick = onLaunch,
                    enabled = info.isLaunchable() && !isLaunching,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    if (isLaunching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("instance.launch"))
                }
            }
        }
    }
}

/**
 * 创建实例对话框：输入名称、选择基础版本、可选加载器。
 */
@Composable
private fun CreateInstanceDialog(
    localVersionIds: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, baseVersionId: String, loader: String?, loaderVersion: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedVersion by remember { mutableStateOf(localVersionIds.firstOrNull() ?: "") }
    var expanded by remember { mutableStateOf(false) }
    var loaderExpanded by remember { mutableStateOf(false) }
    val loaders = listOf(I18n.t("instance.loader_none"), "Fabric", "Forge", "Quilt", "NeoForge")
    var selectedLoader by remember { mutableStateOf(0) }
    var loaderVersion by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("instance.create_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    I18n.t("instance.create_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 实例名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(I18n.t("instance.name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // 基础版本下拉
                Box {
                    OutlinedTextField(
                        value = selectedVersion,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(I18n.t("instance.base_version")) },
                        trailingIcon = {
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(if (expanded) Icons.Filled.ArrowDropDown
                                     else Icons.Filled.ArrowDropUp, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        localVersionIds.forEach { vid ->
                            DropdownMenuItem(
                                text = { Text(vid) },
                                onClick = {
                                    selectedVersion = vid
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                // 模组加载器下拉
                Box {
                    OutlinedTextField(
                        value = loaders[selectedLoader],
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(I18n.t("instance.loader")) },
                        trailingIcon = {
                            IconButton(onClick = { loaderExpanded = !loaderExpanded }) {
                                Icon(if (loaderExpanded) Icons.Filled.ArrowDropDown
                                     else Icons.Filled.ArrowDropUp, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = loaderExpanded,
                        onDismissRequest = { loaderExpanded = false }
                    ) {
                        loaders.forEachIndexed { idx, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedLoader = idx
                                    loaderExpanded = false
                                }
                            )
                        }
                    }
                }
                // 加载器版本（仅当选择了具体加载器时显示）
                if (selectedLoader > 0) {
                    OutlinedTextField(
                        value = loaderVersion,
                        onValueChange = { loaderVersion = it },
                        label = { Text(I18n.t("instance.loader_version")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank() || selectedVersion.isBlank()) return@Button
                    val loaderName = if (selectedLoader == 0) null else loaders[selectedLoader]
                    val lVer = if (loaderName != null && loaderVersion.isNotBlank()) loaderVersion else null
                    onConfirm(name.trim(), selectedVersion, loaderName, lVer)
                },
                enabled = name.isNotBlank() && selectedVersion.isNotBlank()
            ) {
                Text(I18n.t("common.confirm"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(I18n.t("common.cancel"))
            }
        }
    )
}

/**
 * 通用重命名/复制输入对话框。
 */
@Composable
private fun RenameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(I18n.t("instance.name")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text(I18n.t("common.confirm"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(I18n.t("common.cancel"))
            }
        }
    )
}

/**
 * 绑定账户对话框：选择一个已登录账户绑定到实例，或清除绑定。
 */
@Composable
private fun BindAccountDialog(
    instanceName: String,
    accounts: List<com.pmcl.core.auth.Account>,
    selectedUuid: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selected by remember { mutableStateOf(selectedUuid) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("instance.bind_account_title", arrayOf(instanceName))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    I18n.t("instance.bind_account_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // 不绑定（使用全局默认账户）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected.isEmpty(),
                        onClick = { selected = "" }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(I18n.t("instance.bind_account_default"))
                }
                // 已登录账户列表
                if (accounts.isEmpty()) {
                    Text(
                        I18n.t("instance.bind_account_no_accounts"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    accounts.forEach { acc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == acc.getUuid(),
                                onClick = { selected = acc.getUuid() }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    acc.getUsername(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    acc.getType().toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected) }) {
                Text(I18n.t("common.confirm"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(I18n.t("common.cancel"))
            }
        }
    )
}

/** 格式化时间戳为 yyyy-MM-dd */
private fun formatTime(millis: Long): String {
    if (millis <= 0) return ""
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = millis
    return String.format(
        "%04d-%02d-%02d",
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH)
    )
}

/** 格式化秒数为 xh xm xs */
private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "0s"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return buildString {
        if (h > 0) append("${h}h ")
        if (m > 0) append("${m}m ")
        if (s > 0 || (h == 0L && m == 0L)) append("${s}s")
    }.trim()
}
