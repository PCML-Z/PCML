package com.pmcl.ui.viewmodel

import com.pmcl.core.gamecontent.DatapackManager
import com.pmcl.core.gamecontent.ResourcePackManager
import com.pmcl.core.gamecontent.ScreenshotManager
import com.pmcl.core.gamecontent.ShaderPackManager
import com.pmcl.core.gamecontent.WorldManager
import com.pmcl.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * M29 拆分：世界 / 截图 / 资源包 / 光影 / 数据包域。
 *
 * 状态字段保留在 LauncherViewModel（@PublishedApi internal），
 * UI 调用签名不变（需 import 扩展函数）。
 */

@PublishedApi
internal fun LauncherViewModel.contentSourceLabelFor(dir: java.nio.file.Path, subDirName: String): String {
    if (dir == config.getWorkDir().resolve(subDirName)) return "全局"
    val parent = dir.parent
    if (parent != null) {
        val grandName = parent.parent?.fileName?.toString()?.lowercase()
        if (grandName == "versions" || grandName == "instances") {
            return parent.fileName?.toString() ?: "版本"
        }
    }
    return "系统"
}

// ============ 世界管理 ============

fun LauncherViewModel.refreshWorlds() {
    scope.launch {
        try {
            val list = withContext(Dispatchers.IO) {
                val all = mutableListOf<WorldManager.WorldInfo>()
                val seenPaths = mutableSetOf<String>()
                val savesDirs = mutableListOf<Pair<Path, String>>()
                // 1. PMCL 工作目录的 saves
                savesDirs.add(config.getWorkDir().resolve("saves") to "PMCL")
                // 2. 系统所有 Minecraft 根目录的 saves（HMCL / 官方启动器）
                for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
                    val mcRoot = mcDir.parent
                    if (mcRoot != null) savesDirs.add(mcRoot.resolve("saves") to "外部启动器")
                }
                // 3. 每个版本目录下的 saves（整合包结构：versions/<id>/saves/）
                val allVersionsDirs = mutableListOf<Path>()
                allVersionsDirs.add(config.getVersionsDir())
                allVersionsDirs.addAll(com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs())
                for (versionsDir in allVersionsDirs) {
                    val versionsFile = versionsDir.toFile()
                    if (!versionsFile.isDirectory) continue
                    val subDirs = versionsFile.listFiles { f -> f.isDirectory } ?: continue
                    for (subDir in subDirs) {
                        val versionSaves = subDir.toPath().resolve("saves")
                        savesDirs.add(versionSaves to subDir.name)
                    }
                }
                // 4. 版本隔离目录下的 saves（instances/<id>/saves/）
                val instancesDir = config.getWorkDir().resolve("instances")
                val instancesFile = instancesDir.toFile()
                if (instancesFile.isDirectory) {
                    val instDirs = instancesFile.listFiles { f -> f.isDirectory } ?: emptyArray()
                    for (instDir in instDirs) {
                        val instSaves = instDir.toPath().resolve("saves")
                        savesDirs.add(instSaves to instDir.name)
                    }
                }
                // 扫描所有 saves 目录，按绝对路径去重
                val wm = core.worlds()
                val diag = StringBuilder()
                diag.append("savesDirs.size = ${savesDirs.size}\n")
                for ((savesDir, source) in savesDirs) {
                    try {
                        val part = wm.listWorlds(savesDir, source)
                        for (w in part) {
                            if (seenPaths.add(w.dir.toAbsolutePath().toString())) all.add(w)
                        }
                        diag.append("[$savesDir] → ${part.size} worlds (exists=${java.nio.file.Files.isDirectory(savesDir)})\n")
                    } catch (t: Throwable) {
                        diag.append("[$savesDir] → 异常: ${t.javaClass.simpleName}: ${t.message}\n")
                    }
                }
                diag.append("TOTAL = ${all.size} worlds\n")
                System.err.println("[refreshWorlds] $diag")
                all
            }
            _worlds.value = list
            _status.value = I18n.t("status.worlds_scanned", list.size)
        } catch (e: Throwable) {
            _status.value = I18n.t("status.scan_worlds_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.backupWorld(world: WorldManager.WorldInfo): Job {
    return scope.launch {
        try {
            _status.value = I18n.t("status.backing_up", world.name)
            val zip = withContext(Dispatchers.IO) { core.worlds().backup(world) }
            _status.value = I18n.t("status.world_backed_up", zip.fileName.toString())
        } catch (e: Throwable) {
            _status.value = I18n.t("status.backup_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 打开某个世界所在文件夹 */
fun LauncherViewModel.openWorldFolder(world: WorldManager.WorldInfo) {
    openDir(world.dir.toFile())
}

fun LauncherViewModel.deleteWorld(world: WorldManager.WorldInfo) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.worlds().delete(world) }
            _status.value = I18n.t("status.world_deleted", world.name)
            refreshWorlds()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 列出指定世界的所有备份文件 */
suspend fun LauncherViewModel.listBackups(worldName: String): List<java.nio.file.Path> {
    return withContext(Dispatchers.IO) {
        try {
            core.worlds().listBackups(worldName)
        } catch (e: Throwable) {
            emptyList()
        }
    }
}

/** 从备份 zip 恢复世界（覆盖现有同名世界） */
fun LauncherViewModel.restoreWorld(zipFile: java.nio.file.Path, worldName: String) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.worlds().restore(zipFile, worldName) }
            _status.value = I18n.t("status.world_restored", worldName)
            refreshWorlds()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.restore_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 从 zip 导入世界（世界名取自 zip 文件名） */
fun LauncherViewModel.importWorld(zipFile: java.nio.file.Path) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.worlds().importWorld(zipFile) }
            _status.value = I18n.t("status.world_imported", zipFile.fileName.toString())
            refreshWorlds()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.import_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

// ============ 截图 ============

fun LauncherViewModel.refreshScreenshots() {
    scope.launch {
        try {
            val list = withContext(Dispatchers.IO) {
                val all = mutableListOf<ScreenshotManager.Screenshot>()
                val seenPaths = mutableSetOf<String>()
                val shotDirs = mutableListOf<Pair<Path, String>>()
                // 1. PMCL 工作目录的 screenshots
                shotDirs.add(config.getWorkDir().resolve("screenshots") to "PMCL")
                // 2. 系统所有 Minecraft 根目录的 screenshots（HMCL / 官方启动器）
                for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
                    val mcRoot = mcDir.parent
                    if (mcRoot != null) shotDirs.add(mcRoot.resolve("screenshots") to "外部启动器")
                }
                // 3. 每个版本目录下的 screenshots（整合包结构：versions/<id>/screenshots/）
                val allVersionsDirs = mutableListOf<Path>()
                allVersionsDirs.add(config.getVersionsDir())
                allVersionsDirs.addAll(com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs())
                for (versionsDir in allVersionsDirs) {
                    val versionsFile = versionsDir.toFile()
                    if (!versionsFile.isDirectory) continue
                    val subDirs = versionsFile.listFiles { f -> f.isDirectory } ?: continue
                    for (subDir in subDirs) {
                        val versionShots = subDir.toPath().resolve("screenshots")
                        shotDirs.add(versionShots to subDir.name)
                    }
                }
                // 4. 版本隔离目录下的 screenshots（instances/<id>/screenshots/）
                val instancesDir = config.getWorkDir().resolve("instances")
                val instancesFile = instancesDir.toFile()
                if (instancesFile.isDirectory) {
                    val instDirs = instancesFile.listFiles { f -> f.isDirectory } ?: emptyArray()
                    for (instDir in instDirs) {
                        val instShots = instDir.toPath().resolve("screenshots")
                        shotDirs.add(instShots to instDir.name)
                    }
                }
                // 扫描所有 screenshots 目录，按绝对路径去重
                val sm = core.screenshots()
                val diag = StringBuilder()
                diag.append("shotDirs.size = ${shotDirs.size}\n")
                for ((shotDir, source) in shotDirs) {
                    try {
                        val part = sm.list(shotDir, source)
                        for (s in part) {
                            if (seenPaths.add(s.path.toAbsolutePath().toString())) all.add(s)
                        }
                        diag.append("[$shotDir] → ${part.size} shots (exists=${java.nio.file.Files.isDirectory(shotDir)})\n")
                    } catch (t: Throwable) {
                        diag.append("[$shotDir] → 异常: ${t.javaClass.simpleName}: ${t.message}\n")
                    }
                }
                // 合并后再次按修改时间倒序
                all.sortByDescending { it.modified }
                diag.append("TOTAL = ${all.size} shots\n")
                System.err.println("[refreshScreenshots] $diag")
                all
            }
            _screenshots.value = list
            _status.value = I18n.t("status.screenshots_scanned", list.size)
        } catch (e: Throwable) {
            _status.value = I18n.t("status.scan_screenshots_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.deleteScreenshot(shot: ScreenshotManager.Screenshot) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.screenshots().delete(shot) }
            _status.value = I18n.t("status.screenshot_deleted", shot.name)
            refreshScreenshots()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量删除截图 */
fun LauncherViewModel.deleteScreenshots(shots: List<ScreenshotManager.Screenshot>) {
    if (shots.isEmpty()) return
    scope.launch {
        try {
            var ok = 0
            withContext(Dispatchers.IO) {
                for (shot in shots) {
                    try {
                        core.screenshots().delete(shot)
                        ok++
                    } catch (_: Throwable) {
                        // 单个失败继续删其余
                    }
                }
            }
            _status.value = I18n.t("status.screenshots_deleted", ok)
            refreshScreenshots()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 复制截图到系统剪贴板（作为图片） */
fun LauncherViewModel.copyScreenshotToClipboard(shot: ScreenshotManager.Screenshot) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val img = javax.imageio.ImageIO.read(shot.getPath().toFile())
                if (img != null) {
                    val selection = object : java.awt.datatransfer.Transferable {
                        override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
                        override fun isDataFlavorSupported(f: java.awt.datatransfer.DataFlavor) =
                            f == java.awt.datatransfer.DataFlavor.imageFlavor
                        override fun getTransferData(f: java.awt.datatransfer.DataFlavor): Any {
                            if (f != java.awt.datatransfer.DataFlavor.imageFlavor)
                                throw java.awt.datatransfer.UnsupportedFlavorException(f)
                            return img
                        }
                    }
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                }
            }
            _status.value = I18n.t("status.screenshot_copied", shot.name)
        } catch (e: Throwable) {
            _status.value = I18n.t("status.copy_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 导出多张截图为 ZIP 文件 */
fun LauncherViewModel.exportScreenshotsZip(shots: List<ScreenshotManager.Screenshot>, targetPath: String) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                java.nio.file.Files.newOutputStream(java.nio.file.Paths.get(targetPath)).use { fos ->
                    java.util.zip.ZipOutputStream(fos).use { zos ->
                        val usedNames = mutableSetOf<String>()
                        for (shot in shots) {
                            var name = shot.getName()
                            while (!usedNames.add(name)) {
                                val dot = name.lastIndexOf('.')
                                name = if (dot > 0) name.substring(0, dot) + "_1" + name.substring(dot)
                                       else name + "_1"
                            }
                            zos.putNextEntry(java.util.zip.ZipEntry(name))
                            java.nio.file.Files.copy(shot.getPath(), zos)
                            zos.closeEntry()
                        }
                    }
                }
            }
            _status.value = I18n.t("status.screenshots_exported", shots.size, targetPath)
        } catch (e: Throwable) {
            _status.value = I18n.t("status.export_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

// ============ 资源包 ============

fun LauncherViewModel.refreshResourcePacks() {
    scope.launch {
        try {
            val list = withContext(Dispatchers.IO) {
                val all = mutableListOf<ResourcePackManager.Pack>()
                val seen = mutableSetOf<String>()
                val dirs = mutableListOf<java.nio.file.Path>()
                // 1. PMCL 全局 resourcepacks
                dirs.add(config.getWorkDir().resolve("resourcepacks"))
                // 2. 系统 .minecraft/resourcepacks
                for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
                    val mcRoot = mcDir.parent
                    if (mcRoot != null) dirs.add(mcRoot.resolve("resourcepacks"))
                }
                // 3. 版本隔离 versions/<id>/resourcepacks
                val versionsDirs = mutableListOf<java.nio.file.Path>()
                versionsDirs.add(config.getVersionsDir())
                versionsDirs.addAll(com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs())
                for (vd in versionsDirs) {
                    val vf = vd.toFile()
                    if (!vf.isDirectory) continue
                    val subs = vf.listFiles { f -> f.isDirectory } ?: continue
                    for (sub in subs) dirs.add(sub.toPath().resolve("resourcepacks"))
                }
                // 4. 实例 instances/<id>/resourcepacks
                val instDir = config.getWorkDir().resolve("instances")
                if (instDir.toFile().isDirectory) {
                    val insts = instDir.toFile().listFiles { f -> f.isDirectory } ?: emptyArray()
                    for (inst in insts) dirs.add(inst.toPath().resolve("resourcepacks"))
                }
                // 扫描所有目录；单目录失败不阻断，但汇总给 UI
                var dirErrors = 0
                for (dir in dirs) {
                    try {
                        val sourceLabel = contentSourceLabelFor(dir, "resourcepacks")
                        val part = core.resourcePacks().list(dir, sourceLabel)
                        for (p in part) {
                            val key = "$dir/${p.name}"
                            if (seen.add(key)) all.add(p)
                        }
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        dirErrors++
                        System.err.println("[VM] 资源包目录扫描失败 $dir: ${t.message}")
                    }
                }
                Pair(all, dirErrors)
            }
            _resourcePacks.value = list.first
            _status.value = if (list.second > 0) {
                I18n.t("status.resource_packs_scanned_partial", list.first.size, list.second)
            } else {
                I18n.t("status.resource_packs_scanned", list.first.size)
            }
        } catch (e: Throwable) {
            _status.value = I18n.t("status.scan_resource_packs_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.enableResourcePack(pack: ResourcePackManager.Pack) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.resourcePacks().enable(pack.name) }
            _status.value = I18n.t("status.resource_pack_enabled", pack.name)
            refreshResourcePacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.enable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.disableResourcePack(pack: ResourcePackManager.Pack) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.resourcePacks().disable(pack.name) }
            _status.value = I18n.t("status.resource_pack_disabled", pack.name)
            refreshResourcePacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.disable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.deleteResourcePack(pack: ResourcePackManager.Pack) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.resourcePacks().delete(pack) }
            _status.value = I18n.t("status.resource_pack_deleted", pack.name)
            refreshResourcePacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 导入资源包文件到 resourcepacks 目录 */
fun LauncherViewModel.importResourcePack(filePath: String) {
    scope.launch {
        try {
            val fileName = withContext(Dispatchers.IO) {
                val src = java.nio.file.Paths.get(filePath)
                val targetDir = config.getWorkDir().resolve("resourcepacks")
                java.nio.file.Files.createDirectories(targetDir)
                val target = targetDir.resolve(src.fileName)
                java.nio.file.Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                src.fileName.toString()
            }
            _status.value = I18n.t("status.resource_pack_imported", fileName)
            refreshResourcePacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.import_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量启用资源包 */
fun LauncherViewModel.batchEnableResourcePacks(packs: List<ResourcePackManager.Pack>) {
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (pack in packs) {
                    try {
                        core.resourcePacks().enable(pack.name)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchEnableResourcePacks 失败 ${pack.name}: ${t.message}")
                    }
                }
            }
            _status.value = batchResultStatus("status.batch_enabled_resource_packs", ok, fail, packs.size)
            refreshResourcePacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_enable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量禁用资源包 */
fun LauncherViewModel.batchDisableResourcePacks(packs: List<ResourcePackManager.Pack>) {
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (pack in packs) {
                    try {
                        core.resourcePacks().disable(pack.name)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchDisableResourcePacks 失败 ${pack.name}: ${t.message}")
                    }
                }
            }
            _status.value = batchResultStatus("status.batch_disabled_resource_packs", ok, fail, packs.size)
            refreshResourcePacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_disable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量删除资源包 */
fun LauncherViewModel.batchDeleteResourcePacks(packs: List<ResourcePackManager.Pack>) {
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (pack in packs) {
                    try {
                        core.resourcePacks().delete(pack)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchDeleteResourcePacks 失败 ${pack.name}: ${t.message}")
                    }
                }
            }
            _status.value = batchResultStatus("status.batch_deleted_resource_packs", ok, fail, packs.size)
            refreshResourcePacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

// ============ 光影包 ============

fun LauncherViewModel.refreshShaderPacks() {
    scope.launch {
        try {
            val list = withContext(Dispatchers.IO) {
                val all = mutableListOf<ShaderPackManager.ShaderPack>()
                val seen = mutableSetOf<String>()
                val dirs = mutableListOf<java.nio.file.Path>()
                // 1. PMCL 全局 shaderpacks
                dirs.add(config.getWorkDir().resolve("shaderpacks"))
                // 2. 系统 .minecraft/shaderpacks
                for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
                    val mcRoot = mcDir.parent
                    if (mcRoot != null) dirs.add(mcRoot.resolve("shaderpacks"))
                }
                // 3. 版本隔离 versions/<id>/shaderpacks
                val versionsDirs = mutableListOf<java.nio.file.Path>()
                versionsDirs.add(config.getVersionsDir())
                versionsDirs.addAll(com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs())
                for (vd in versionsDirs) {
                    val vf = vd.toFile()
                    if (!vf.isDirectory) continue
                    val subs = vf.listFiles { f -> f.isDirectory } ?: continue
                    for (sub in subs) dirs.add(sub.toPath().resolve("shaderpacks"))
                }
                // 4. 实例 instances/<id>/shaderpacks
                val instDir = config.getWorkDir().resolve("instances")
                if (instDir.toFile().isDirectory) {
                    val insts = instDir.toFile().listFiles { f -> f.isDirectory } ?: emptyArray()
                    for (inst in insts) dirs.add(inst.toPath().resolve("shaderpacks"))
                }
                var dirErrors = 0
                for (dir in dirs) {
                    try {
                        val sourceLabel = contentSourceLabelFor(dir, "shaderpacks")
                        val part = core.shaderPacks().list(dir, sourceLabel)
                        for (p in part) {
                            val key = "$dir/${p.name}"
                            if (seen.add(key)) all.add(p)
                        }
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        dirErrors++
                        System.err.println("[VM] 光影包目录扫描失败 $dir: ${t.message}")
                    }
                }
                Pair(all, dirErrors)
            }
            _shaderPacks.value = list.first
            _status.value = if (list.second > 0) {
                I18n.t("status.shader_packs_scanned_partial", list.first.size, list.second)
            } else {
                I18n.t("status.shader_packs_scanned", list.first.size)
            }
        } catch (e: Throwable) {
            _status.value = I18n.t("status.scan_shader_packs_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.enableShaderPack(pack: ShaderPackManager.ShaderPack) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.shaderPacks().enable(pack.name) }
            _status.value = I18n.t("status.shader_pack_enabled", pack.name)
            refreshShaderPacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.enable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.disableShaderPack(pack: ShaderPackManager.ShaderPack) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.shaderPacks().disable(pack.name) }
            _status.value = I18n.t("status.shader_pack_disabled", pack.name)
            refreshShaderPacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.disable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.deleteShaderPack(pack: ShaderPackManager.ShaderPack) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.shaderPacks().delete(pack) }
            _status.value = I18n.t("status.shader_pack_deleted", pack.name)
            refreshShaderPacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 导入光影包文件到 shaderpacks 目录 */
fun LauncherViewModel.importShaderPack(filePath: String) {
    scope.launch {
        try {
            val fileName = withContext(Dispatchers.IO) {
                val src = java.nio.file.Paths.get(filePath)
                val targetDir = config.getWorkDir().resolve("shaderpacks")
                java.nio.file.Files.createDirectories(targetDir)
                val target = targetDir.resolve(src.fileName)
                java.nio.file.Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                src.fileName.toString()
            }
            _status.value = I18n.t("status.shader_pack_imported", fileName)
            refreshShaderPacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.import_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量启用光影包 */
fun LauncherViewModel.batchEnableShaderPacks(packs: List<ShaderPackManager.ShaderPack>) {
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (pack in packs) {
                    try {
                        core.shaderPacks().enable(pack.name)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchEnableShaderPacks 失败 ${pack.name}: ${t.message}")
                    }
                }
            }
            _status.value = batchResultStatus("status.batch_enabled_shader_packs", ok, fail, packs.size)
            refreshShaderPacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_enable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量禁用光影包 */
fun LauncherViewModel.batchDisableShaderPacks(packs: List<ShaderPackManager.ShaderPack>) {
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (pack in packs) {
                    try {
                        core.shaderPacks().disable(pack.name)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchDisableShaderPacks 失败 ${pack.name}: ${t.message}")
                    }
                }
            }
            _status.value = batchResultStatus("status.batch_disabled_shader_packs", ok, fail, packs.size)
            refreshShaderPacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_disable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量删除光影包 */
fun LauncherViewModel.batchDeleteShaderPacks(packs: List<ShaderPackManager.ShaderPack>) {
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (pack in packs) {
                    try {
                        core.shaderPacks().delete(pack)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchDeleteShaderPacks 失败 ${pack.name}: ${t.message}")
                    }
                }
            }
            _status.value = batchResultStatus("status.batch_deleted_shader_packs", ok, fail, packs.size)
            refreshShaderPacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 将指定光影包设为当前选中（写入 options.txt） */
fun LauncherViewModel.setActiveShaderPack(pack: ShaderPackManager.ShaderPack) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.shaderPacks().setActive(pack) }
            _status.value = I18n.t("status.shader_pack_applied", pack.name)
            refreshShaderPacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.apply_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 关闭光影（清空当前选中） */
fun LauncherViewModel.clearActiveShaderPack() {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.shaderPacks().clearActive() }
            _status.value = I18n.t("status.shader_pack_cleared")
            refreshShaderPacks()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.clear_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 在系统文件管理中打开 shaderpacks 目录 */
fun LauncherViewModel.openShaderPacksDir() {
    openDir(core.shaderPacks().shaderPacksDir.toFile())
}

/** 在系统文件管理中打开 resourcepacks 目录 */
fun LauncherViewModel.openResourcePacksDir() {
    openDir(core.resourcePacks().resourcePacksDir.toFile())
}

/** 在系统文件管理中打开 screenshots 目录 */
fun LauncherViewModel.openScreenshotsDir() {
    openDir(core.screenshots().screenshotsDir.toFile())
}

/** 打开单张截图所在文件夹（版本/实例真实目录） */
fun LauncherViewModel.openScreenshotFolder(shot: ScreenshotManager.Screenshot) {
    val parent = shot.path?.parent?.toFile()
    if (parent == null) {
        _status.value = I18n.t("status.open_dir_failed", I18n.t("common.unknown"))
        return
    }
    openDir(parent)
}

// ============ 数据包 ============

fun LauncherViewModel.refreshDatapacks(worldDir: java.nio.file.Path) {
    scope.launch {
        try {
            val list = withContext(Dispatchers.IO) { core.datapacks().list(worldDir) }
            _datapacks.value = list
            _status.value = I18n.t("status.datapacks_scanned", list.size)
        } catch (e: Throwable) {
            _status.value = I18n.t("status.scan_datapacks_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.deleteDatapack(pack: DatapackManager.Datapack) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.datapacks().delete(pack) }
            _status.value = I18n.t("status.datapack_deleted", pack.name)
            // 删除后刷新当前选中的世界
            _selectedDatapackWorld.value?.let { w ->
                val list = withContext(Dispatchers.IO) { core.datapacks().list(w.dir) }
                _datapacks.value = list
            }
        } catch (e: Throwable) {
            _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.enableDatapack(pack: DatapackManager.Datapack) {
    scope.launch {
        try {
            _selectedDatapackWorld.value?.let { w ->
                withContext(Dispatchers.IO) { core.datapacks().enable(w.dir, pack.name) }
                _status.value = I18n.t("status.datapack_enabled", pack.name)
                val list = withContext(Dispatchers.IO) { core.datapacks().list(w.dir) }
                _datapacks.value = list
            }
        } catch (e: Throwable) {
            _status.value = I18n.t("status.enable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.disableDatapack(pack: DatapackManager.Datapack) {
    scope.launch {
        try {
            _selectedDatapackWorld.value?.let { w ->
                withContext(Dispatchers.IO) { core.datapacks().disable(w.dir, pack.name) }
                _status.value = I18n.t("status.datapack_disabled", pack.name)
                val list = withContext(Dispatchers.IO) { core.datapacks().list(w.dir) }
                _datapacks.value = list
            }
        } catch (e: Throwable) {
            _status.value = I18n.t("status.disable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 导入数据包文件到选中世界的 datapacks 目录 */
fun LauncherViewModel.importDatapack(filePath: String) {
    val world = _selectedDatapackWorld.value
    if (world == null) {
        _status.value = I18n.t("status.world_select_first")
        return
    }
    scope.launch {
        try {
            val fileName = withContext(Dispatchers.IO) {
                val src = java.nio.file.Paths.get(filePath)
                val targetDir = world.dir.resolve("datapacks")
                java.nio.file.Files.createDirectories(targetDir)
                val target = targetDir.resolve(src.fileName)
                java.nio.file.Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                src.fileName.toString()
            }
            _status.value = I18n.t("status.datapack_imported", fileName)
            val list = withContext(Dispatchers.IO) { core.datapacks().list(world.dir) }
            _datapacks.value = list
        } catch (e: Throwable) {
            _status.value = I18n.t("status.import_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量启用数据包 */
fun LauncherViewModel.batchEnableDatapacks(packs: List<DatapackManager.Datapack>) {
    val world = _selectedDatapackWorld.value
    if (world == null) {
        _status.value = I18n.t("status.world_select_first")
        return
    }
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (pack in packs) {
                    try {
                        core.datapacks().enable(world.dir, pack.name)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchEnableDatapacks 失败 ${pack.name}: ${t.message}")
                    }
                }
            }
            _status.value = batchResultStatus("status.batch_enabled_datapacks", ok, fail, packs.size)
            val list = withContext(Dispatchers.IO) { core.datapacks().list(world.dir) }
            _datapacks.value = list
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_enable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量禁用数据包 */
fun LauncherViewModel.batchDisableDatapacks(packs: List<DatapackManager.Datapack>) {
    val world = _selectedDatapackWorld.value
    if (world == null) {
        _status.value = I18n.t("status.world_select_first")
        return
    }
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (pack in packs) {
                    try {
                        core.datapacks().disable(world.dir, pack.name)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchDisableDatapacks 失败 ${pack.name}: ${t.message}")
                    }
                }
            }
            _status.value = batchResultStatus("status.batch_disabled_datapacks", ok, fail, packs.size)
            val list = withContext(Dispatchers.IO) { core.datapacks().list(world.dir) }
            _datapacks.value = list
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_disable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量删除数据包 */
fun LauncherViewModel.batchDeleteDatapacks(packs: List<DatapackManager.Datapack>) {
    val world = _selectedDatapackWorld.value
    if (world == null) {
        _status.value = I18n.t("status.world_select_first")
        return
    }
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (pack in packs) {
                    try {
                        core.datapacks().delete(pack)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchDeleteDatapacks 失败 ${pack.name}: ${t.message}")
                    }
                }
            }
            _status.value = batchResultStatus("status.batch_deleted_datapacks", ok, fail, packs.size)
            val list = withContext(Dispatchers.IO) { core.datapacks().list(world.dir) }
            _datapacks.value = list
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.selectDatapackWorld(world: WorldManager.WorldInfo) {
    _selectedDatapackWorld.value = world
    refreshDatapacks(world.dir)
}

/** 清除选中的世界，返回世界列表视图 */
fun LauncherViewModel.clearDatapackWorld() {
    _selectedDatapackWorld.value = null
    _datapacks.value = emptyList()
}

/** 打开指定世界的 datapacks 目录 */
fun LauncherViewModel.openDatapacksDir(world: WorldManager.WorldInfo) {
    openDir(world.dir.resolve("datapacks").toFile())
}

