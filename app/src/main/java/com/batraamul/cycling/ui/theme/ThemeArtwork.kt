package com.batraamul.cycling.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural landscape artwork for each theme. Drawn with vector paths at whatever size the
 * header has, so it is crisp on any screen and adds nothing to the APK size. It is static
 * (no animation loop), so it costs nothing when the screen is off or the header is not visible.
 */
@Composable
fun SceneArtwork(spec: ThemeSpec, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(spec.sky))
        when (spec.scene) {
            Scene.MOUNTAINS -> mountains(spec)
            Scene.MONSOON -> monsoon(spec)
            Scene.DUNES -> dunes(spec)
            Scene.COAST -> coast(spec)
            Scene.NEON -> neon(spec)
            Scene.AUTUMN -> autumn(spec)
            Scene.AURORA -> aurora(spec)
            Scene.VELODROME -> velodrome(spec)
            Scene.ARMY -> army(spec)
            Scene.AIR_FORCE -> airForce(spec)
        }
    }
}

/** A deterministic "noise" ridge: sum of sines with fixed phases (same picture every time). */
private fun ridge(w: Float, baseY: Float, amp: Float, seed: Int, sharp: Boolean = false): Path {
    val p = Path()
    p.moveTo(0f, baseY)
    val steps = 64
    for (i in 0..steps) {
        val x = w * i / steps
        val t = i.toFloat() / steps
        var v = sin(t * 2 * PI * 1.3 + seed) * 0.55 + sin(t * 2 * PI * 3.1 + seed * 1.7) * 0.3 + sin(t * 2 * PI * 7.3 + seed * 0.3) * 0.15
        if (sharp) v = 1 - abs(v) * 2
        p.lineTo(x, baseY - (amp * v).toFloat())
    }
    return p
}

private fun DrawScope.fillBelow(path: Path, color: Color) {
    path.lineTo(size.width, size.height); path.lineTo(0f, size.height); path.close()
    drawPath(path, color)
}

private fun DrawScope.mountains(s: ThemeSpec) {
    val w = size.width; val h = size.height
    drawCircle(Color(0xFFFFF1D0), radius = h * 0.16f, center = Offset(w * 0.72f, h * 0.38f))
    drawCircle(Color.White.copy(alpha = 0.25f), radius = h * 0.24f, center = Offset(w * 0.72f, h * 0.38f))
    s.layers.forEachIndexed { i, c ->
        val base = h * (0.62f + i * 0.13f)
        val p = ridge(w, base, h * (0.26f - i * 0.06f), seed = i * 3 + 1, sharp = i == 0)
        fillBelow(p, c)
        if (i == 0) { // snow caps on the farthest range
            val snow = ridge(w, base, h * 0.26f, seed = 1, sharp = true)
            drawPath(snow, Color.White.copy(alpha = 0.85f), style = Stroke(width = h * 0.025f, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.monsoon(s: ThemeSpec) {
    val w = size.width; val h = size.height
    listOf(0.2f to 0.22f, 0.55f to 0.15f, 0.85f to 0.26f).forEach { (x, y) ->
        val c = Color.White.copy(alpha = 0.75f)
        drawCircle(c, h * 0.09f, Offset(w * x, h * y)); drawCircle(c, h * 0.12f, Offset(w * x + h * 0.11f, h * y - h * 0.03f))
        drawCircle(c, h * 0.08f, Offset(w * x + h * 0.23f, h * y))
    }
    for (i in 0 until 46) { // rain streaks
        val x = (i * 97 % 1000) / 1000f * w
        val y = (i * 53 % 1000) / 1000f * h * 0.6f + h * 0.15f
        drawLine(Color(0xFF5C7A89).copy(alpha = 0.35f), Offset(x, y), Offset(x - h * 0.02f, y + h * 0.07f), strokeWidth = 2f)
    }
    s.layers.forEachIndexed { i, c -> fillBelow(ridge(w, h * (0.66f + i * 0.12f), h * (0.12f - i * 0.02f), seed = i * 5 + 2), c) }
}

private fun DrawScope.dunes(s: ThemeSpec) {
    val w = size.width; val h = size.height
    drawCircle(Color(0xFFFFF4D6), h * 0.2f, Offset(w * 0.3f, h * 0.42f))
    s.layers.forEachIndexed { i, c ->
        val p = Path(); val base = h * (0.6f + i * 0.14f)
        p.moveTo(0f, base)
        p.cubicTo(w * 0.25f, base - h * 0.18f, w * 0.45f, base + h * 0.05f, w * 0.7f, base - h * (0.12f - i * 0.03f))
        p.cubicTo(w * 0.85f, base - h * 0.2f, w * 0.95f, base, w, base - h * 0.06f)
        fillBelow(p, c)
    }
    // a camel-free, dependency-free cyclist on the ridge
    cyclist(Offset(w * 0.62f, h * 0.58f), h * 0.11f, Color(0xFF5A2D0C))
}

private fun DrawScope.coast(s: ThemeSpec) {
    val w = size.width; val h = size.height
    drawCircle(s.accent, h * 0.13f, Offset(w * 0.8f, h * 0.3f))
    fillBelow(ridge(w, h * 0.55f, h * 0.06f, seed = 4), Color(0xFF4FB286).copy(alpha = 0.6f)) // far headland
    s.layers.forEachIndexed { i, c ->
        val base = h * (0.66f + i * 0.11f)
        val p = Path(); p.moveTo(0f, base)
        val waves = 6 + i * 2
        for (k in 0..waves * 8) {
            val x = w * k / (waves * 8f)
            p.lineTo(x, base + sin(k / 8f * 2 * PI + i).toFloat() * h * 0.015f)
        }
        fillBelow(p, c)
    }
    // palm
    val trunkBase = Offset(w * 0.14f, h * 0.9f)
    drawLine(Color(0xFF6B4F2A), trunkBase, Offset(w * 0.18f, h * 0.48f), strokeWidth = h * 0.025f, cap = StrokeCap.Round)
    for (a in 0 until 6) {
        val ang = PI * (1.05 + a * 0.18)
        val top = Offset(w * 0.18f, h * 0.48f)
        drawLine(Color(0xFF2D6A4F), top, Offset(top.x + (cos(ang) * h * 0.18).toFloat(), top.y + (sin(ang) * h * 0.1).toFloat() + h * 0.04f),
            strokeWidth = h * 0.02f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.neon(s: ThemeSpec) {
    val w = size.width; val h = size.height
    val sunC = Offset(w * 0.5f, h * 0.55f); val r = h * 0.3f
    drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFD166), Color(0xFFFF4FA3)), startY = sunC.y - r, endY = sunC.y + r), r, sunC)
    for (i in 0 until 6) { // synthwave sun stripes
        val y = sunC.y + r * (0.15f + i * 0.14f)
        drawRect(s.sky[1], Offset(0f, y), Size(w, r * 0.05f * (1 + i * 0.4f)))
    }
    fillBelow(ridge(w, h * 0.62f, h * 0.08f, seed = 9), s.layers[0])
    drawRect(s.layers[2], Offset(0f, h * 0.68f), Size(w, h * 0.32f))
    val horizon = h * 0.68f
    for (i in -10..10) { // perspective grid
        drawLine(s.secondary.copy(alpha = 0.55f), Offset(w / 2 + i * w * 0.02f, horizon), Offset(w / 2 + i * w * 0.16f, h), strokeWidth = 2f)
    }
    for (j in 1..6) {
        val y = horizon + (h - horizon) * (j * j) / 36f
        drawLine(s.primary.copy(alpha = 0.55f), Offset(0f, y), Offset(w, y), strokeWidth = 2f)
    }
}

private fun DrawScope.autumn(s: ThemeSpec) {
    val w = size.width; val h = size.height
    drawCircle(Color(0xFFFFF0D9), h * 0.12f, Offset(w * 0.2f, h * 0.3f))
    fillBelow(ridge(w, h * 0.7f, h * 0.08f, seed = 6), Color(0xFFD9A066))
    for (i in 0 until 9) { // conical trees in three tones
        val x = w * (0.05f + i * 0.115f)
        val th = h * (0.32f + (i % 3) * 0.06f)
        val base = h * 0.8f
        val p = Path(); p.moveTo(x, base - th); p.lineTo(x - th * 0.28f, base); p.lineTo(x + th * 0.28f, base); p.close()
        drawPath(p, s.layers[i % 3])
        drawLine(s.accent, Offset(x, base), Offset(x, base + h * 0.05f), strokeWidth = h * 0.015f)
    }
    drawRect(Color(0xFF8C5A3C), Offset(0f, h * 0.86f), Size(w, h * 0.14f))
    for (i in 0 until 18) { // leaves
        val x = (i * 131 % 1000) / 1000f * w; val y = (i * 71 % 1000) / 1000f * h * 0.7f
        drawCircle(s.layers[i % 3], h * 0.012f, Offset(x, y))
    }
}

private fun DrawScope.aurora(s: ThemeSpec) {
    val w = size.width; val h = size.height
    for (i in 0 until 40) { // stars
        val x = (i * 389 % 1000) / 1000f * w; val y = (i * 211 % 1000) / 1000f * h * 0.5f
        drawCircle(Color.White.copy(alpha = 0.3f + (i % 4) * 0.15f), 1.2f + (i % 3), Offset(x, y))
    }
    listOf(s.primary, s.secondary, s.tertiary).forEachIndexed { i, c ->
        val p = Path()
        val base = h * (0.28f + i * 0.08f)
        p.moveTo(0f, base)
        for (k in 0..40) p.lineTo(w * k / 40f, base + sin(k / 40f * 2 * PI * 1.4 + i).toFloat() * h * 0.08f)
        drawPath(p, Brush.verticalGradient(listOf(c.copy(alpha = 0.0f), c.copy(alpha = 0.55f), c.copy(alpha = 0f)),
            startY = base - h * 0.15f, endY = base + h * 0.15f), style = Stroke(width = h * 0.12f))
    }
    s.layers.forEachIndexed { i, c -> fillBelow(ridge(w, h * (0.7f + i * 0.1f), h * (0.08f - i * 0.02f), seed = i + 11), c) }
}

private fun DrawScope.velodrome(s: ThemeSpec) {
    val w = size.width; val h = size.height
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE29A), Color(0x00FFE29A)), center = Offset(w * 0.5f, h * 0.62f), radius = h * 0.5f),
        h * 0.5f, Offset(w * 0.5f, h * 0.62f))
    drawCircle(Color(0xFFFFD166), h * 0.14f, Offset(w * 0.5f, h * 0.62f))
    fillBelow(ridge(w, h * 0.66f, h * 0.03f, seed = 13), s.layers[2])
    // banked track: concentric ellipse arcs
    listOf(s.layers[0], s.secondary, s.primary, Color.White.copy(alpha = 0.7f)).forEachIndexed { i, c ->
        drawArc(c, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(-w * 0.2f + i * w * 0.04f, h * 0.72f + i * h * 0.03f),
            size = Size(w * 1.4f - i * w * 0.08f, h * 0.7f), style = Stroke(width = h * 0.035f))
    }
    cyclist(Offset(w * 0.3f, h * 0.73f), h * 0.1f, Color(0xFF1A1020))
}

/** Olive ranges at dusk; the nearest ridge is filled with a woodland camouflage pattern. */
private fun DrawScope.army(s: ThemeSpec) {
    val w = size.width; val h = size.height
    drawCircle(Color(0xFFFFF3D0).copy(alpha = 0.9f), h * 0.12f, Offset(w * 0.78f, h * 0.3f))
    drawCircle(Color.White.copy(alpha = 0.18f), h * 0.2f, Offset(w * 0.78f, h * 0.3f))
    s.layers.take(2).forEachIndexed { i, c -> fillBelow(ridge(w, h * (0.58f + i * 0.12f), h * (0.2f - i * 0.06f), seed = i * 4 + 3, sharp = i == 0), c) }
    // Foreground ridge, clipped so the camouflage blotches stay inside it.
    val near = ridge(w, h * 0.84f, h * 0.06f, seed = 21)
    near.lineTo(w, h); near.lineTo(0f, h); near.close()
    drawPath(near, s.layers[2])
    clipPath(near) { camoBlotches(Color(0xFF6B7A3A), Color(0xFF2E3519), Color(0xFF8C7A4E), scale = h * 0.07f) }
    // Pine silhouettes along the middle ridge.
    for (i in 0 until 7) {
        val x = w * (0.06f + i * 0.14f) + (i % 2) * w * 0.03f
        val base = h * 0.8f; val th = h * (0.18f + (i % 3) * 0.04f)
        val p = Path(); p.moveTo(x, base - th); p.lineTo(x - th * 0.22f, base); p.lineTo(x + th * 0.22f, base); p.close()
        drawPath(p, Color(0xFF2B3318))
    }
}

/** Three jets in arrowhead formation trailing saffron, white and green smoke over the clouds. */
private fun DrawScope.airForce(s: ThemeSpec) {
    val w = size.width; val h = size.height
    for (k in 0 until 4) { // soft cloud banks
        val cx = w * (0.15f + k * 0.27f); val cy = h * (0.72f + (k % 2) * 0.06f)
        val c = Color.White.copy(alpha = 0.55f)
        drawCircle(c, h * 0.1f, Offset(cx, cy)); drawCircle(c, h * 0.13f, Offset(cx + h * 0.12f, cy - h * 0.04f)); drawCircle(c, h * 0.09f, Offset(cx + h * 0.25f, cy))
    }
    fillBelow(ridge(w, h * 0.9f, h * 0.04f, seed = 17), s.layers[2])
    val lead = Offset(w * 0.72f, h * 0.3f)
    val smoke = listOf(Color(0xFFFF9933), Color.White, Color(0xFF138808))
    val jets = listOf(Offset(lead.x - w * 0.09f, lead.y + h * 0.1f), lead, Offset(lead.x - w * 0.09f, lead.y - h * 0.1f))
    jets.forEachIndexed { i, j ->
        // Smoke trail sweeping back to the left, widening as it ages.
        val trail = Path(); trail.moveTo(j.x - h * 0.06f, j.y)
        trail.cubicTo(j.x - w * 0.25f, j.y + h * 0.02f, j.x - w * 0.45f, j.y + h * 0.12f, 0f, j.y + h * 0.22f)
        // Fade the smoke out towards the left so it never crosses the screen title and status text.
        val fade = Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent, smoke[i].copy(alpha = 0.9f)), startX = 0f, endX = j.x)
        val haze = Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent, smoke[i].copy(alpha = 0.3f)), startX = 0f, endX = j.x)
        drawPath(trail, fade, style = Stroke(width = h * 0.045f, cap = StrokeCap.Round))
        drawPath(trail, haze, style = Stroke(width = h * 0.09f, cap = StrokeCap.Round))
        jet(j, h * 0.09f)
    }
}

/** A generic delta-wing jet silhouette (not any specific aircraft or marking). */
private fun DrawScope.jet(at: Offset, sz: Float) {
    val c = Color(0xFF14243A)
    val body = Path().apply {
        moveTo(at.x + sz, at.y)                       // nose
        lineTo(at.x - sz * 0.2f, at.y - sz * 0.12f)
        lineTo(at.x - sz * 0.55f, at.y - sz * 0.62f)  // wing tip
        lineTo(at.x - sz * 0.75f, at.y - sz * 0.6f)
        lineTo(at.x - sz * 0.62f, at.y - sz * 0.1f)
        lineTo(at.x - sz * 0.9f, at.y - sz * 0.3f)   // tail fin
        lineTo(at.x - sz * 0.98f, at.y)
        lineTo(at.x - sz * 0.9f, at.y + sz * 0.3f)
        lineTo(at.x - sz * 0.62f, at.y + sz * 0.1f)
        lineTo(at.x - sz * 0.75f, at.y + sz * 0.6f)
        lineTo(at.x - sz * 0.55f, at.y + sz * 0.62f)
        lineTo(at.x - sz * 0.2f, at.y + sz * 0.12f)
        close()
    }
    drawPath(body, c)
    drawCircle(Color(0xFF9FD3FF), sz * 0.09f, Offset(at.x + sz * 0.45f, at.y))
}

/** Irregular rounded blotches in three tones — the classic woodland camouflage look. */
internal fun DrawScope.camoBlotches(a: Color, b: Color, c: Color, scale: Float, alpha: Float = 1f) {
    val colors = listOf(a, b, c)
    val cols = (size.width / (scale * 1.6f)).toInt() + 2
    val rows = (size.height / (scale * 1.4f)).toInt() + 2
    for (r in 0 until rows) for (k in 0 until cols) {
        val i = r * 31 + k * 17
        val x = k * scale * 1.6f + ((i * 37) % 100) / 100f * scale
        val y = r * scale * 1.4f + ((i * 53) % 100) / 100f * scale * 0.6f
        val col = colors[i % 3].let { it.copy(alpha = it.alpha * alpha) }
        rotate(((i * 47) % 180).toFloat(), Offset(x, y)) {
            drawOval(col, Offset(x - scale * 0.7f, y - scale * 0.35f), Size(scale * 1.4f, scale * 0.7f))
            drawOval(col, Offset(x - scale * 0.1f, y - scale * 0.5f), Size(scale * 0.9f, scale * 0.6f))
        }
    }
}

/** Tiny stylised rider used in a couple of scenes. */
private fun DrawScope.cyclist(at: Offset, sz: Float, c: Color) {
    val stroke = sz * 0.11f
    val back = Offset(at.x - sz * 0.55f, at.y); val front = Offset(at.x + sz * 0.55f, at.y)
    drawCircle(c, sz * 0.38f, back, style = Stroke(stroke)); drawCircle(c, sz * 0.38f, front, style = Stroke(stroke))
    val seat = Offset(at.x - sz * 0.15f, at.y - sz * 0.55f); val bar = Offset(at.x + sz * 0.42f, at.y - sz * 0.6f)
    drawLine(c, back, seat, stroke); drawLine(c, seat, Offset(at.x, at.y), stroke); drawLine(c, Offset(at.x, at.y), back, stroke)
    drawLine(c, seat, bar, stroke); drawLine(c, bar, front, stroke)
    val hip = Offset(seat.x, seat.y - sz * 0.08f); val shoulder = Offset(at.x + sz * 0.2f, at.y - sz * 1.0f)
    drawLine(c, hip, shoulder, stroke * 1.3f, cap = StrokeCap.Round); drawLine(c, shoulder, bar, stroke, cap = StrokeCap.Round)
    drawLine(c, hip, Offset(at.x + sz * 0.05f, at.y - sz * 0.05f), stroke, cap = StrokeCap.Round)
    drawCircle(c, sz * 0.16f, Offset(shoulder.x + sz * 0.12f, shoulder.y - sz * 0.18f))
}
