package com.pmcl.ui.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pmcl.core.i18n.I18n
import com.pmcl.core.gamecontent.ConfigFileManager


/**
 * M29 拆分：配置文件编辑器域。
 */

// ============ 配置文件编辑器 ============

/** 获取当前选中版本的 config 目录 */
fun LauncherViewModel.getConfigDir(): java.nio.file.Path {
    val versionId = _selectedVersion.value
    val pref = preferences
    if (pref.isVersionIsolation() && versionId != null) {
        return config.getWorkDir().resolve("instances").resolve(versionId).resolve("config")
    }
    return config.getWorkDir().resolve("config")
}

/** 创建 ConfigFileManager 实例（基于当前选中版本的 config 目录） */
fun LauncherViewModel.createConfigFileManager(): ConfigFileManager {
    return ConfigFileManager(getConfigDir())
}

/** 刷新配置文件列表 */
fun LauncherViewModel.refreshConfigFiles(subDir: String = "") {
    scope.launch {
        try {
            val manager = createConfigFileManager()
            val files = withContext(Dispatchers.IO) {
                manager.listFiles(subDir)
            }
            _configFiles.value = files
            _configCurrentDir.value = subDir
        } catch (e: Throwable) {
            _status.value = I18n.t("status.config_files_load_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 读取配置文件内容 */
fun LauncherViewModel.readConfigFile(relativePath: String) {
    scope.launch {
        try {
            val manager = createConfigFileManager()
            val content = withContext(Dispatchers.IO) {
                manager.readFile(relativePath)
            }
            _configFileContent.value = content
            _currentConfigPath.value = relativePath
            _configFileDirty.value = false
        } catch (e: Throwable) {
            _status.value = I18n.t("status.config_file_read_failed", e.message ?: I18n.t("common.unknown"))
            _configFileContent.value = null
            _currentConfigPath.value = null
        }
    }
}

/** 保存配置文件内容 */
fun LauncherViewModel.saveConfigFile(content: String) {
    val path = _currentConfigPath.value ?: return
    scope.launch {
        try {
            val manager = createConfigFileManager()
            withContext(Dispatchers.IO) {
                manager.writeFile(path, content)
            }
            _configFileDirty.value = false
            _status.value = I18n.t("status.config_file_saved", path)
        } catch (e: Throwable) {
            _status.value = I18n.t("status.config_file_save_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 删除配置文件 */
fun LauncherViewModel.deleteConfigFile(relativePath: String) {
    scope.launch {
        try {
            val manager = createConfigFileManager()
            withContext(Dispatchers.IO) {
                manager.deleteFile(relativePath)
            }
            if (_currentConfigPath.value == relativePath) {
                _configFileContent.value = null
                _currentConfigPath.value = null
                _configFileDirty.value = false
            }
            _status.value = I18n.t("status.config_file_deleted", relativePath)
            refreshConfigFiles(_configCurrentDir.value)
        } catch (e: Throwable) {
            _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 创建新配置文件 */
fun LauncherViewModel.createConfigFile(fileName: String) {
    scope.launch {
        try {
            val manager = createConfigFileManager()
            val dir = _configCurrentDir.value
            val relativePath = if (dir.isEmpty()) fileName else "$dir/$fileName"
            withContext(Dispatchers.IO) {
                manager.createFile(relativePath)
            }
            _status.value = I18n.t("status.config_file_created", fileName)
            refreshConfigFiles(dir)
        } catch (e: Throwable) {
            _status.value = I18n.t("status.config_file_create_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 标记当前文件已修改（未保存） */
fun LauncherViewModel.markConfigDirty() {
    _configFileDirty.value = true
}

/** 关闭当前编辑的文件 */
fun LauncherViewModel.closeConfigFile() {
    _configFileContent.value = null
    _currentConfigPath.value = null
    _configFileDirty.value = false
}

/** 进入子目录 */
fun LauncherViewModel.enterConfigDir(subDir: String) {
    val newDir = if (_configCurrentDir.value.isEmpty()) subDir
                 else "${_configCurrentDir.value}/$subDir"
    refreshConfigFiles(newDir)
}

/** 返回上级目录 */
fun LauncherViewModel.navigateConfigUp() {
    val current = _configCurrentDir.value
    if (current.isEmpty()) return
    val idx = current.lastIndexOf('/')
    val parent = if (idx < 0) "" else current.substring(0, idx)
    refreshConfigFiles(parent)
}

/** 在系统文件管理中打开 config 目录 */
fun LauncherViewModel.openConfigDir() {
    try {
        val dir = getConfigDir().toFile()
        if (!dir.isDirectory) dir.mkdirs()
        openDir(dir)
    } catch (e: Throwable) {
        _status.value = I18n.t("status.open_dir_failed", e.message ?: I18n.t("common.unknown"))
    }
}

