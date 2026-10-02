package com.pmcl.ui.page

import org.lash.ecxp.lsn.SchematicLoader
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_PROJECTION_BYTES = 64L * 1024 * 1024

actual fun readProjection(path: String): ProjectionInfo? {
    return try {
        val file = Path.of(path)
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_PROJECTION_BYTES) return null
        val schematic = SchematicLoader.load(file)
        // v1.0.0 没有 blockCounts()。按已发布的 block() 统计，重叠区域以最上层为准。
        val counts = HashMap<String, Int>()
        for (y in 0 until schematic.height()) {
            for (z in 0 until schematic.length()) {
                for (x in 0 until schematic.width()) {
                    val state = schematic.block(x, y, z)
                    if (state.isAir()) continue
                    counts[state.name] = (counts[state.name] ?: 0) + 1
                }
            }
        }
        val kinds = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { ProjectionKind(it.key, it.value) }
        ProjectionInfo(
            schematic.name(),
            schematic.width(),
            schematic.height(),
            schematic.length(),
            schematic.nonAirCount(),
            kinds
        )
    } catch (_: Throwable) {
        null
    }
}
