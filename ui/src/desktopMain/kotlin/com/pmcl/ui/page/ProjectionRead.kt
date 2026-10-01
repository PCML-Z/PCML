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
        ProjectionInfo(
            schematic.name(),
            schematic.width(),
            schematic.height(),
            schematic.length(),
            schematic.nonAirCount(),
            schematic.blockCounts().map { ProjectionKind(it.name, it.count) }
        )
    } catch (_: Throwable) {
        null
    }
}
