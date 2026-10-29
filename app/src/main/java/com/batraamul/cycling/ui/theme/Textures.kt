package com.batraamul.cycling.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Draws the theme's background texture behind the content.
 *
 * Why drawWithCache: the pattern geometry is built once per screen size and reused for every frame,
 * so scrolling never rebuilds it. All patterns are a few hundred simple shapes drawn at very low
 * opacity — they add depth without reducing text contrast, and cost no APK size (no image files).
 */
fun Modifier.themeTexture(spec: ThemeSpec): Modifier = drawWithCache {
    val ink = (if (spec.dark) Color.White else spec.onSurface)
    val faint = ink.copy(alpha = if (spec.dark) 0.05f else 0.045f)
    val w = size.width; val h = size.height
    val unit = 24.dp.toPx()
    val path = Path()
    when (spec.texture) {
        Texture.DIAGONAL -> {
            var x = -h
            while (x < w) { path.moveTo(x, h); path.lineTo(x + h, 0f); x += unit * 0.75f }
        }
        Texture.GRID -> {
            var x = 0f; while (x < w) { path.moveTo(x, 0f); path.lineTo(x, h); x += unit * 1.5f }
            var y = 0f; while (y < h) { path.moveTo(0f, y); path.lineTo(w, y); y += unit * 1.5f }
        }
        Texture.TOPO -> {   // contour lines, like a trekking map
            var y = 0f; var k = 0
            while (y < h + unit * 4) {
                path.moveTo(0f, y)
                var x = 0f
                while (x <= w) {
                    path.lineTo(x, y + (sin(x / w * 2 * PI * 1.7 + k * 0.9) * unit * 1.2 + sin(x / w * 2 * PI * 4.3 + k) * unit * 0.4).toFloat())
                    x += unit / 2
                }
                y += unit * 1.6f; k++
            }
        }
        Texture.CONTRAILS -> {  // long shallow streaks, like vapour trails
            var y = unit; var k = 0
            while (y < h) {
                val x0 = ((k * 73) % 100) / 100f * w * 0.5f
                path.moveTo(x0, y); path.lineTo(x0 + w * 0.6f, y - unit * 1.6f)
                y += unit * 2.6f; k++
            }
        }
        else -> Unit
    }
    onDrawBehind {
        when (spec.texture) {
            Texture.GRAIN -> for (i in 0 until 900) {   // fixed pseudo-random speckle
                val x = ((i * 7919) % 10007) / 10007f * w
                val y = ((i * 104729) % 10009) / 10009f * h
                drawCircle(faint, (1f + (i % 3)) * density * 0.6f, Offset(x, y))
            }
            Texture.DOTS -> {
                var y = unit / 2; var row = 0
                while (y < h) {
                    var x = if (row % 2 == 0) unit / 2 else unit
                    while (x < w) { drawCircle(faint, 1.6f * density, Offset(x, y)); x += unit }
                    y += unit * 0.87f; row++
                }
            }
            Texture.CAMO -> camoBlotches(ink.copy(alpha = 0.035f), ink.copy(alpha = 0.06f), ink.copy(alpha = 0.02f), scale = unit * 2.2f)
            else -> drawPath(path, faint, style = Stroke(width = 1.2f * density))
        }
    }
}
