package com.pmcl.ui.page

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URL
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ======================== 3D Math ========================

private data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun normalized(): V3 {
        val l = sqrt(x * x + y * y + z * z)
        return if (l > 0f) V3(x / l, y / l, z / l) else this
    }
}

// ======================== Model Definition ========================

/**
 * One face of the player model: 4 vertices (CCW from outside) + UV region + fallback color.
 * UV coordinates are in pixel space of a 64x64 (or 64x32) Minecraft skin texture.
 */
private data class ModelFace(
    val vertices: List<V3>,
    val u0: Float, val v0: Float, val u1: Float, val v1: Float,
    val fallback: Color
)

/**
 * Build a box (rectangular cuboid) and return its 6 textured faces.
 *
 * @param cx cy cz  center position
 * @param w  h  d   width / height / depth
 * @param uv 6 UV regions: [top, bottom, right, front, left, back], each floatArrayOf(u0,v0,u1,v1)
 * @param fallback solid color when no texture is available
 */
private fun buildBox(
    cx: Float, cy: Float, cz: Float,
    w: Float, h: Float, d: Float,
    uv: List<FloatArray>,
    fallback: Color
): List<ModelFace> {
    val x0 = cx - w / 2f; val x1 = cx + w / 2f
    val y0 = cy - h / 2f; val y1 = cy + h / 2f
    val z0 = cz - d / 2f; val z1 = cz + d / 2f
    fun f(v: List<V3>, u: FloatArray) = ModelFace(v, u[0], u[1], u[2], u[3], fallback)

    return listOf(
        // Top (+Y)
        f(listOf(V3(x0, y1, z1), V3(x1, y1, z1), V3(x1, y1, z0), V3(x0, y1, z0)), uv[0]),
        // Bottom (-Y)
        f(listOf(V3(x0, y0, z0), V3(x1, y0, z0), V3(x1, y0, z1), V3(x0, y0, z1)), uv[1]),
        // Right (-X, player's right)
        f(listOf(V3(x0, y0, z0), V3(x0, y0, z1), V3(x0, y1, z1), V3(x0, y1, z0)), uv[2]),
        // Front (+Z)
        f(listOf(V3(x0, y0, z1), V3(x1, y0, z1), V3(x1, y1, z1), V3(x0, y1, z1)), uv[3]),
        // Left (+X, player's left)
        f(listOf(V3(x1, y0, z1), V3(x1, y0, z0), V3(x1, y1, z0), V3(x1, y1, z1)), uv[4]),
        // Back (-Z)
        f(listOf(V3(x1, y0, z0), V3(x0, y0, z0), V3(x0, y1, z0), V3(x1, y1, z0)), uv[5])
    )
}

/** Minecraft player model geometry with standard skin UV mapping. */
private fun buildPlayerModel(slim: Boolean, hasLeftLayer: Boolean): List<ModelFace> {
    val faces = mutableListOf<ModelFace>()

    // === Head (8x8x8) at (0, 28, 0) ===
    faces.addAll(buildBox(0f, 28f, 0f, 8f, 8f, 8f, listOf(
        floatArrayOf(8f, 0f, 16f, 8f),    // top
        floatArrayOf(16f, 0f, 24f, 8f),   // bottom
        floatArrayOf(0f, 8f, 8f, 16f),    // right
        floatArrayOf(8f, 8f, 16f, 16f),   // front
        floatArrayOf(16f, 8f, 24f, 16f),  // left
        floatArrayOf(24f, 8f, 32f, 16f)   // back
    ), Color(0xFFB88160)))

    // === Body (8x12x4) at (0, 18, 0) ===
    faces.addAll(buildBox(0f, 18f, 0f, 8f, 12f, 4f, listOf(
        floatArrayOf(20f, 16f, 28f, 20f),
        floatArrayOf(28f, 16f, 36f, 20f),
        floatArrayOf(16f, 20f, 20f, 32f),
        floatArrayOf(20f, 20f, 28f, 32f),
        floatArrayOf(28f, 20f, 32f, 32f),
        floatArrayOf(32f, 20f, 40f, 32f)
    ), Color(0xFF129087)))

    // === Right Arm at (-6, 18, 0) ===
    val armW = if (slim) 3f else 4f
    val armUV = if (slim) listOf(
        floatArrayOf(44f, 16f, 47f, 20f),
        floatArrayOf(47f, 16f, 50f, 20f),
        floatArrayOf(40f, 20f, 44f, 32f),
        floatArrayOf(44f, 20f, 47f, 32f),
        floatArrayOf(47f, 20f, 51f, 32f),
        floatArrayOf(51f, 20f, 54f, 32f)
    ) else listOf(
        floatArrayOf(44f, 16f, 48f, 20f),
        floatArrayOf(48f, 16f, 52f, 20f),
        floatArrayOf(40f, 20f, 44f, 32f),
        floatArrayOf(44f, 20f, 48f, 32f),
        floatArrayOf(48f, 20f, 52f, 32f),
        floatArrayOf(52f, 20f, 56f, 32f)
    )
    faces.addAll(buildBox(-6f, 18f, 0f, armW, 12f, 4f, armUV, Color(0xFFB88160)))

    // === Left Arm at (6, 18, 0) ===
    if (hasLeftLayer) {
        val leftArmUV = if (slim) listOf(
            floatArrayOf(36f, 48f, 39f, 52f),
            floatArrayOf(39f, 48f, 42f, 52f),
            floatArrayOf(32f, 52f, 36f, 64f),
            floatArrayOf(36f, 52f, 39f, 64f),
            floatArrayOf(39f, 52f, 43f, 64f),
            floatArrayOf(43f, 52f, 46f, 64f)
        ) else listOf(
            floatArrayOf(36f, 48f, 40f, 52f),
            floatArrayOf(40f, 48f, 44f, 52f),
            floatArrayOf(32f, 52f, 36f, 64f),
            floatArrayOf(36f, 52f, 40f, 64f),
            floatArrayOf(40f, 52f, 44f, 64f),
            floatArrayOf(44f, 52f, 48f, 64f)
        )
        faces.addAll(buildBox(6f, 18f, 0f, armW, 12f, 4f, leftArmUV, Color(0xFFB88160)))
    } else {
        // 64x32 old format: reuse right arm UVs
        faces.addAll(buildBox(6f, 18f, 0f, armW, 12f, 4f, armUV, Color(0xFFB88160)))
    }

    // === Right Leg at (-2, 6, 0) ===
    val legUV = listOf(
        floatArrayOf(4f, 16f, 8f, 20f),
        floatArrayOf(8f, 16f, 12f, 20f),
        floatArrayOf(0f, 20f, 4f, 32f),
        floatArrayOf(4f, 20f, 8f, 32f),
        floatArrayOf(8f, 20f, 12f, 32f),
        floatArrayOf(12f, 20f, 16f, 32f)
    )
    faces.addAll(buildBox(-2f, 6f, 0f, 4f, 12f, 4f, legUV, Color(0xFF3C3C9C)))

    // === Left Leg at (2, 6, 0) ===
    if (hasLeftLayer) {
        faces.addAll(buildBox(2f, 6f, 0f, 4f, 12f, 4f, listOf(
            floatArrayOf(20f, 48f, 24f, 52f),
            floatArrayOf(24f, 48f, 28f, 52f),
            floatArrayOf(16f, 52f, 20f, 64f),
            floatArrayOf(20f, 52f, 24f, 64f),
            floatArrayOf(24f, 52f, 28f, 64f),
            floatArrayOf(28f, 52f, 32f, 64f)
        ), Color(0xFF3C3C9C)))
    } else {
        faces.addAll(buildBox(2f, 6f, 0f, 4f, 12f, 4f, legUV, Color(0xFF3C3C9C)))
    }

    return faces
}

// ======================== Texture Sampling ========================

/** Sample the average color from a UV region of the skin texture. */
private fun sampleAvgColor(
    img: BufferedImage,
    u0: Float, v0: Float, u1: Float, v1: Float
): Color {
    var r = 0; var g = 0; var b = 0; var count = 0
    val x0 = u0.toInt().coerceIn(0, img.width - 1)
    val x1 = u1.toInt().coerceIn(1, img.width)
    val y0 = v0.toInt().coerceIn(0, img.height - 1)
    val y1 = v1.toInt().coerceIn(1, img.height)
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val argb = img.getRGB(x, y)
            val alpha = (argb ushr 24) and 0xFF
            if (alpha > 25) {
                r += (argb ushr 16) and 0xFF
                g += (argb ushr 8) and 0xFF
                b += argb and 0xFF
                count++
            }
        }
    }
    return if (count > 0) Color(r / count / 255f, g / count / 255f, b / count / 255f) else Color.Gray
}

// ======================== 3D Renderer ========================

/**
 * Real-time 3D Minecraft skin model viewer.
 *
 * - Auto-rotates around Y axis
 * - Drag to rotate manually (X and Y axes)
 * - Samples average color per face from the skin texture for a blocky 3D look
 * - Uses painter's algorithm (back-to-front depth sorting) for face rendering
 * - Applies simple directional lighting for depth perception
 * - Falls back to solid Steve-like colors when no texture is available
 */
@Composable
fun SkinViewer3D(
    skinUrl: String,
    skinModel: String,
    modifier: Modifier = Modifier
) {
    var skinImg by remember(skinUrl) { mutableStateOf<BufferedImage?>(null) }
    var dragAngleX by remember { mutableStateOf(-8f) }
    var dragAngleY by remember { mutableStateOf(0f) }

    // Load skin texture asynchronously
    LaunchedEffect(skinUrl) {
        skinImg = null
        if (skinUrl.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val bytes = URL(skinUrl).readBytes()
                    skinImg = ImageIO.read(ByteArrayInputStream(bytes))
                } catch (_: Throwable) {
                    skinImg = null
                }
            }
        }
    }

    // Auto-rotation animation (8 seconds per revolution)
    val transition = rememberInfiniteTransition(label = "skin3d")
    val autoRotY by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotY"
    )

    // Build model and sample colors (only when texture/model changes)
    val modelAndColors = remember(skinImg, skinModel) {
        val hasLeftLayer = (skinImg?.height ?: 64) >= 64
        val slim = skinModel == "slim"
        val model = buildPlayerModel(slim, hasLeftLayer)
        val colors = if (skinImg != null) {
            model.map { face ->
                sampleAvgColor(skinImg!!, face.u0, face.v0, face.u1, face.v1)
            }
        } else {
            model.map { it.fallback }
        }
        model to colors
    }
    val (model, faceColors) = modelAndColors

    // Light direction (from camera-left-top towards model, normalized)
    val lightDir = V3(-0.4f, 0.7f, -0.6f).normalized()

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E2E))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        dragAngleY = (dragAngleY + drag.x * 0.4f) % 360f
                        dragAngleX = (dragAngleX - drag.y * 0.3f).coerceIn(-75f, 75f)
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val scale = min(size.width, size.height) / 48f

            val totalRotY = autoRotY + dragAngleY
            val totalRotX = dragAngleX

            val cosY = cos(totalRotY * PI / 180f)
            val sinY = sin(totalRotY * PI / 180f)
            val cosX = cos(totalRotX * PI / 180f)
            val sinX = sin(totalRotX * PI / 180f)

            // Rotate a vertex: first X, then Y
            fun rotate(v: V3): V3 {
                // X rotation (pitch)
                val y1 = v.y * cosX - v.z * sinX
                val z1 = v.y * sinX + v.z * cosX
                // Y rotation (yaw)
                val x2 = v.x * cosY + z1 * sinY
                val z2 = -v.x * sinY + z1 * cosY
                return V3(x2, y1, z2)
            }

            // Project to 2D (simple perspective)
            val focal = 40f
            fun project(v: V3): Offset {
                val z = v.z + focal
                val safeZ = if (z < 1f) 1f else z
                return Offset(
                    cx + (v.x * focal / safeZ) * scale,
                    cy - (v.y * focal / safeZ) * scale
                )
            }

            // Collect renderable faces: (depth, projected vertices, shaded color)
            data class RenderFace(
                val depth: Float,
                val points: List<Offset>,
                val color: Color
            )

            val renderFaces = mutableListOf<RenderFace>()

            for (i in model.indices) {
                val face = model[i]
                val rotated = face.vertices.map { rotate(it) }

                // Face normal from rotated vertices
                val e1 = rotated[1] - rotated[0]
                val e2 = rotated[2] - rotated[0]
                val normal = e1.cross(e2).normalized()

                // Backface culling: camera is at -Z looking towards +Z.
                // Keep faces whose normal points towards camera (normal.z < 0).
                if (normal.z >= 0f) continue

                // Project vertices
                val projected = rotated.map { project(it) }

                // Average depth for sorting
                val avgZ = (rotated[0].z + rotated[1].z + rotated[2].z + rotated[3].z) / 4f

                // Lighting: brightness from dot(normal, lightDir)
                val brightness = maxOf(0.35f, normal.dot(lightDir))
                val baseColor = faceColors[i]
                val shaded = Color(
                    red = (baseColor.red * brightness).coerceIn(0f, 1f),
                    green = (baseColor.green * brightness).coerceIn(0f, 1f),
                    blue = (baseColor.blue * brightness).coerceIn(0f, 1f),
                    alpha = 1f
                )

                renderFaces.add(RenderFace(avgZ, projected, shaded))
            }

            // Sort back-to-front (painter's algorithm)
            renderFaces.sortByDescending { it.depth }

            // Draw faces
            for (rf in renderFaces) {
                val path = Path()
                path.moveTo(rf.points[0].x, rf.points[0].y)
                for (k in 1 until rf.points.size) {
                    path.lineTo(rf.points[k].x, rf.points[k].y)
                }
                path.close()
                drawPath(path, color = rf.color)
                // Subtle outline for definition
                drawPath(path, color = Color.Black.copy(alpha = 0.15f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.5f))
            }
        }
    }
}

private const val PI = 3.14159265358979323846f
