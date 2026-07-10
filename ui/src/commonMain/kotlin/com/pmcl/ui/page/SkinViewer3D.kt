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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
 * One face of the player model: 4 vertices + UV region (in skin pixel coords) + fallback color.
 * Vertex order maps to UV corners: (u0,v1) -> (u1,v1) -> (u1,v0) -> (u0,v0)
 */
private data class ModelFace(
    val vertices: List<V3>,
    val u0: Float, val v0: Float, val u1: Float, val v1: Float,
    val fallback: Color
)

/**
 * Build a box and return its 6 textured faces.
 * All faces use CCW vertex order when viewed from outside.
 * UV mapping: v0=(u0,v1) bottom-left, v1=(u1,v1) bottom-right,
 *             v2=(u1,v0) top-right, v3=(u0,v0) top-left
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
        // Top (+Y) — looking down from +Y, CCW: front-left, front-right, back-right, back-left
        f(listOf(V3(x0, y1, z1), V3(x1, y1, z1), V3(x1, y1, z0), V3(x0, y1, z0)), uv[0]),
        // Bottom (-Y) — looking up from -Y, CCW: back-left, back-right, front-right, front-left
        f(listOf(V3(x0, y0, z0), V3(x1, y0, z0), V3(x1, y0, z1), V3(x0, y0, z1)), uv[1]),
        // Front (+Z) — looking from +Z, CCW: bottom-left, bottom-right, top-right, top-left
        f(listOf(V3(x0, y0, z1), V3(x1, y0, z1), V3(x1, y1, z1), V3(x0, y1, z1)), uv[3]),
        // Back (-Z) — looking from -Z, CCW: bottom-right, bottom-left, top-left, top-right
        f(listOf(V3(x1, y0, z0), V3(x0, y0, z0), V3(x0, y1, z0), V3(x1, y1, z0)), uv[5]),
        // Left (+X) — looking from +X, CCW: front-bottom, back-bottom, back-top, front-top
        f(listOf(V3(x1, y0, z1), V3(x1, y0, z0), V3(x1, y1, z0), V3(x1, y1, z1)), uv[4]),
        // Right (-X) — looking from -X, CCW: back-bottom, front-bottom, front-top, back-top
        f(listOf(V3(x0, y0, z0), V3(x0, y0, z1), V3(x0, y1, z1), V3(x0, y1, z0)), uv[2])
    )
}

/** Minecraft player model geometry with standard skin UV mapping. */
private fun buildPlayerModel(slim: Boolean, hasLeftLayer: Boolean): List<ModelFace> {
    val faces = mutableListOf<ModelFace>()

    // === Head (8x8x8) at (0, 28, 0) ===
    faces.addAll(buildBox(0f, 28f, 0f, 8f, 8f, 8f, listOf(
        floatArrayOf(8f, 0f, 16f, 8f),
        floatArrayOf(16f, 0f, 24f, 8f),
        floatArrayOf(0f, 8f, 8f, 16f),
        floatArrayOf(8f, 8f, 16f, 16f),
        floatArrayOf(16f, 8f, 24f, 16f),
        floatArrayOf(24f, 8f, 32f, 16f)
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

// ======================== Texture Helpers ========================

/**
 * Extract a sub-region of the skin texture as an ImageBitmap for rendering.
 * Returns null if the source image is null.
 */
private fun extractRegion(img: BufferedImage?, u0: Float, v0: Float, u1: Float, v1: Float): ImageBitmap? {
    if (img == null) return null
    val x = u0.toInt().coerceIn(0, img.width)
    val y = v0.toInt().coerceIn(0, img.height)
    val w = (u1 - u0).toInt().coerceIn(1, img.width - x)
    val h = (v1 - v0).toInt().coerceIn(1, img.height - y)
    val sub = img.getSubimage(x, y, w, h)
    // Encode sub-image to PNG bytes, then decode via Skia for Compose ImageBitmap
    val baos = ByteArrayOutputStream()
    ImageIO.write(sub, "png", baos)
    val skiaImage = SkiaImage.makeFromEncoded(baos.toByteArray())
    return skiaImage.toComposeImageBitmap()
}

// Steve 默认皮肤配色（ARGB int）
private const val STEVE_SKIN   = 0xFFB88160.toInt() // 肤色
private const val STEVE_HAIR   = 0xFF2D2D2D.toInt() // 头发
private const val STEVE_SHIRT  = 0xFF129087.toInt() // 青色衫
private const val STEVE_SLEEVE = 0xFF0E6B64.toInt() // 袖子
private const val STEVE_PANTS  = 0xFF3C3C9C.toInt() // 蓝色裤
private const val STEVE_SHOE   = 0xFF2A2A5C.toInt() // 鞋

/**
 * 生成 64x64 的 Steve 默认皮肤纹理（离线账号或加载失败时使用）。
 * 仅绘制模型实际使用的 UV 区域，其余保持透明。
 */
private fun createSteveSkin(): BufferedImage {
    val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
    // Head
    fillRect(img, 0, 0, 32, 16, STEVE_SKIN)
    // Hair overlay on top of head front/sides/back
    fillRect(img, 0, 8, 32, 8, STEVE_HAIR)  // 简化：整个头部区域加深色头发底
    fillRect(img, 8, 8, 16, 16, STEVE_SKIN)  // 脸部
    // Body
    fillRect(img, 16, 20, 24, 32, STEVE_SHIRT)
    fillRect(img, 20, 20, 28, 32, STEVE_SHIRT)
    // Right arm (40-56, 16-32)
    fillRect(img, 40, 16, 56, 20, STEVE_SLEEVE)
    fillRect(img, 40, 20, 56, 32, STEVE_SKIN)
    // Left arm (32-48, 48-64) — 64x64 format
    fillRect(img, 32, 48, 48, 52, STEVE_SLEEVE)
    fillRect(img, 32, 52, 48, 64, STEVE_SKIN)
    // Right leg (0-16, 16-32)
    fillRect(img, 0, 20, 16, 32, STEVE_PANTS)
    // Left leg (16-32, 48-64) — 64x64 format
    fillRect(img, 16, 52, 32, 64, STEVE_PANTS)
    return img
}

private fun fillRect(img: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
    for (y in y0 until y1.coerceAtMost(img.height)) {
        for (x in x0 until x1.coerceAtMost(img.width)) {
            img.setRGB(x, y, argb)
        }
    }
}

// ======================== 3D Renderer ========================

/**
 * Real-time 3D Minecraft skin model viewer with real texture mapping.
 *
 * - Auto-rotates around Y axis (8s/revolution)
 * - Drag to rotate manually (X and Y axes)
 * - Real UV texture mapping via clipped drawImage with affine transform
 * - Backface culling + painter's algorithm depth sorting
 * - Directional lighting for depth perception
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

    LaunchedEffect(skinUrl) {
        skinImg = null
        withContext(Dispatchers.IO) {
            if (skinUrl.isNotEmpty()) {
                try {
                    val bytes = URL(skinUrl).readBytes()
                    val loaded = ImageIO.read(ByteArrayInputStream(bytes))
                    // 校验皮肤尺寸：必须为 64x32 或 64x64
                    skinImg = if (loaded != null && (loaded.width == 64) &&
                                 (loaded.height == 32 || loaded.height == 64)) loaded
                              else createSteveSkin()
                } catch (_: Throwable) {
                    skinImg = createSteveSkin()
                }
            } else {
                // 离线账号无皮肤 URL：使用 Steve 默认皮肤
                skinImg = createSteveSkin()
            }
        }
    }

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

    // Pre-extract texture regions for each face (only when texture/model changes)
    val modelAndTextures = remember(skinImg, skinModel) {
        val hasLeftLayer = (skinImg?.height ?: 64) >= 64
        val slim = skinModel == "slim"
        val model = buildPlayerModel(slim, hasLeftLayer)
        val textures = model.map { face ->
            extractRegion(skinImg, face.u0, face.v0, face.u1, face.v1)
        }
        model to textures
    }
    val (model, faceTextures) = modelAndTextures

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

            // 模型 Y 范围 0~32，中心偏移到 0 以居中显示
            val modelOffsetY = 16f

            fun rotate(v: V3): V3 {
                val py = v.y - modelOffsetY
                val y1 = py * cosX - v.z * sinX
                val z1 = py * sinX + v.z * cosX
                val x2 = v.x * cosY + z1 * sinY
                val z2 = -v.x * sinY + z1 * cosY
                return V3(x2, y1, z2)
            }

            val focal = 40f
            fun project(v: V3): Offset {
                val z = v.z + focal
                val safeZ = if (z < 1f) 1f else z
                return Offset(
                    cx + (v.x * focal / safeZ) * scale,
                    cy - (v.y * focal / safeZ) * scale
                )
            }

            data class RenderFace(
                val depth: Float,
                val points: List<Offset>,
                val texture: ImageBitmap?,
                val fallback: Color,
                val brightness: Float
            )

            val renderFaces = mutableListOf<RenderFace>()

            for (i in model.indices) {
                val face = model[i]
                val rotated = face.vertices.map { rotate(it) }

                // 3D normal for lighting
                val e1 = rotated[1] - rotated[0]
                val e2 = rotated[2] - rotated[0]
                val normal = e1.cross(e2).normalized()

                val projected = rotated.map { project(it) }

                // Screen-space backface culling: compute signed area of projected quad.
                // In screen coords (Y down), CCW 3D faces become CW after projection,
                // so negative signed area = facing camera.
                val signedArea = (projected[1].x - projected[0].x) * (projected[2].y - projected[0].y) -
                                 (projected[1].y - projected[0].y) * (projected[2].x - projected[0].x)
                if (signedArea >= 0f) continue

                val avgZ = (rotated[0].z + rotated[1].z + rotated[2].z + rotated[3].z) / 4f
                val brightness = maxOf(0.45f, normal.dot(lightDir))

                renderFaces.add(RenderFace(avgZ, projected, faceTextures[i], face.fallback, brightness))
            }

            renderFaces.sortByDescending { it.depth }

            for (rf in renderFaces) {
                drawTexturedQuad(rf.points, rf.texture, rf.fallback, rf.brightness)
            }
        }
    }
}

/**
 * Draw a textured quad using clipped drawImage with affine transform.
 * Computes bounding box of the projected quad, draws the texture scaled
 * to fit, clipped to the quad's path. Uses withTransform for clip + matrix.
 * Falls back to solid color when no texture is available.
 */
private fun DrawScope.drawTexturedQuad(
    points: List<Offset>,
    texture: ImageBitmap?,
    fallback: Color,
    brightness: Float
) {
    if (points.size < 4) return

    val shadedFallback = Color(
        red = (fallback.red * brightness).coerceIn(0f, 1f),
        green = (fallback.green * brightness).coerceIn(0f, 1f),
        blue = (fallback.blue * brightness).coerceIn(0f, 1f),
        alpha = 1f
    )

    val path = pathOf(points)

    if (texture == null) {
        drawPath(path, color = shadedFallback)
        drawPath(path, color = Color.Black.copy(alpha = 0.15f), style = Stroke(width = 0.5f))
        return
    }

    // Compute affine transform from texture space to screen space.
    // Quad vertices: v0=bottom-left, v1=bottom-right, v2=top-right, v3=top-left
    // Texture coords: v0->(0,texH), v1->(texW,texH), v2->(texW,0), v3->(0,0)
    // Use v0, v1, v2 to solve affine matrix.
    val texW = texture.width.toFloat()
    val texH = texture.height.toFloat()

    val s0 = points[0]; val s1 = points[1]; val s2 = points[2]
    // Texture triangle: (0,texH), (texW,texH), (texW,0)
    val t0x = 0f; val t0y = texH
    val t1x = texW; val t1y = texH
    val t2x = texW; val t2y = 0f

    val dtx = t1x - t0x
    val dty = t1y - t0y
    val dux = t2x - t0x
    val duy = t2y - t0y
    val dsx = s1.x - s0.x
    val dsy = s1.y - s0.y
    val dvx = s2.x - s0.x
    val dvy = s2.y - s0.y

    val det = dtx * duy - dty * dux
    if (kotlin.math.abs(det) < 0.001f) {
        // Degenerate: fill with fallback
        drawPath(path, color = shadedFallback)
        drawPath(path, color = Color.Black.copy(alpha = 0.1f), style = Stroke(width = 0.5f))
        return
    }

    val a = (dsx * duy - dvx * dty) / det
    val b = (dvx * dtx - dsx * dux) / det
    val c = (dsy * duy - dvy * dty) / det
    val d = (dvy * dtx - dsy * dux) / det
    val e = s0.x - a * t0x - b * t0y
    val f = s0.y - c * t0x - d * t0y

    // Compose Matrix is column-major 4x4:
    // [a c 0 e]
    // [b d 0 f]
    // [0 0 1 0]
    // [0 0 0 1]
    val matrix = androidx.compose.ui.graphics.Matrix(floatArrayOf(
        a, b, 0f, 0f,
        c, d, 0f, 0f,
        0f, 0f, 1f, 0f,
        e, f, 0f, 1f
    ))

    withTransform({
        clipPath(path)
        transform(matrix)
    }) {
        drawImage(
            image = texture,
            srcOffset = androidx.compose.ui.unit.IntOffset(0, 0),
            srcSize = androidx.compose.ui.unit.IntSize(texture.width, texture.height),
            dstOffset = androidx.compose.ui.unit.IntOffset(0, 0),
            dstSize = androidx.compose.ui.unit.IntSize(texture.width, texture.height),
            alpha = brightness
        )
    }

    // Outline for definition
    drawPath(path, color = Color.Black.copy(alpha = 0.1f), style = Stroke(width = 0.5f))
}

private fun pathOf(points: List<Offset>): Path = Path().apply {
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    close()
}

private const val PI = 3.14159265358979323846f
