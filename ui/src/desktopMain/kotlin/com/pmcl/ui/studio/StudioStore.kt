package com.pmcl.ui.studio

import androidx.compose.runtime.mutableStateListOf
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal data class RecentFile(val root: String = "", val file: String = "")

/** Studio 自己的界面状态，写在 studio.json，不碰 preferences.json。 */
internal data class StudioSettings(
    val favorites: List<String> = emptyList(),
    val recent: List<RecentFile> = emptyList(),
    val javaProjects: List<String> = emptyList(),
    val recentDocs: List<String> = emptyList(),
    val leftWidth: Float = 290f,
    val rightWidth: Float = 290f,
    val bottomHeight: Float = 240f,
    val dark: Boolean? = null,
    val ribbonOpen: Boolean = true,
    val leftTool: String? = "PROJECT",
    val bottomTool: String? = "RUN",
    val rightOpen: Boolean = true,
)

internal fun favoriteKey(target: StudioTarget): String = (if (target.kind == TargetKind.VERSION) "V:" else "I:") + target.id

/** 收藏、最近文件这些列表所有 Studio 窗口共用一份，各窗口保存 studio.json 时才不会互相覆盖。 */
internal object StudioShared {
    private val initial by lazy { StudioStore.load() }
    val favorites by lazy { mutableStateListOf<String>().apply { addAll(initial.favorites) } }
    val recent by lazy { mutableStateListOf<RecentFile>().apply { addAll(initial.recent) } }
    val javaProjects by lazy { mutableStateListOf<String>().apply { addAll(initial.javaProjects) } }
    val recentDocs by lazy { mutableStateListOf<String>().apply { addAll(initial.recentDocs) } }
}

internal object StudioStore {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private val file: Path by lazy {
        val dir = System.getProperty("pmcl.workdir")?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".pmcl")
        dir.resolve("studio.json")
    }

    fun load(): StudioSettings = try {
        if (!Files.isRegularFile(file)) StudioSettings()
        else {
            val loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), StudioSettings::class.java)
            // Gson 不走 Kotlin 默认值，缺字段时补回来
            StudioSettings(
                favorites = loaded?.favorites.orEmpty(),
                recent = loaded?.recent.orEmpty().filter { it.root.isNotBlank() && it.file.isNotBlank() },
                javaProjects = loaded?.javaProjects.orEmpty().filter { it.isNotBlank() }.distinct().take(24),
                recentDocs = loaded?.recentDocs.orEmpty().filter { it.isNotBlank() }.distinct().take(24),
                leftWidth = loaded?.leftWidth?.takeIf { it > 0 } ?: 290f,
                rightWidth = loaded?.rightWidth?.takeIf { it > 0 } ?: 290f,
                bottomHeight = loaded?.bottomHeight?.takeIf { it > 0 } ?: 240f,
                dark = loaded?.dark,
                ribbonOpen = loaded?.ribbonOpen ?: true,
                leftTool = loaded?.leftTool,
                bottomTool = loaded?.bottomTool,
                rightOpen = loaded?.rightOpen ?: true,
            )
        }
    } catch (_: Throwable) {
        StudioSettings()
    }

    /** 多个 Studio 窗口可能同时保存，串行写盘 */
    @Synchronized
    fun save(settings: StudioSettings) {
        try {
            Files.createDirectories(file.parent)
            writeAtomically(file, gson.toJson(settings))
        } catch (_: Throwable) {
        }
    }
}
