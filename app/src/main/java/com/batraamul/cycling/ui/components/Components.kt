package com.batraamul.cycling.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batraamul.cycling.core.Confidence
import com.batraamul.cycling.core.GpsFix
import com.batraamul.cycling.ui.theme.NumberStyle
import kotlin.math.cos
import kotlin.math.max

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) { Column(Modifier.padding(18.dp), content = content) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground, modifier = modifier.padding(top = 8.dp, bottom = 6.dp))
}

@Composable
fun StatTile(label: String, value: String, unit: String = "", modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label $value $unit" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = NumberStyle.copy(fontSize = 22.sp), color = MaterialTheme.colorScheme.onSurface)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/** Circular progress ring. Animates only when the value changes (no continuous animation). */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, size: Dp = 190.dp, stroke: Dp = 16.dp,
                 content: @Composable () -> Unit) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(700), label = "ring")
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            val arc = Size(this.size.width - s, this.size.height - s)
            drawArc(track, -90f, 360f, false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(secondary, primary, secondary)),
                -90f, 360f * animated, false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.32f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ConfidenceChip(confidence: Confidence?, modifier: Modifier = Modifier) {
    val (label, color) = when (confidence) {
        Confidence.HIGH -> "High confidence" to Color(0xFF2E9E5B)
        Confidence.MEDIUM -> "Medium confidence" to Color(0xFFE0A100)
        Confidence.LOW -> "Low confidence" to Color(0xFFD9534F)
        else -> "Not calibrated" to Color(0xFF8A8A8A)
    }
    Row(modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun Banner(text: String, modifier: Modifier = Modifier, warning: Boolean = true, action: (@Composable () -> Unit)? = null) {
    val c = if (warning) Color(0xFFE0A100) else MaterialTheme.colorScheme.primary
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = 0.14f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (action != null) { Spacer(Modifier.width(8.dp)); action() }
    }
}

/** Simple bar chart drawn on a Canvas (no chart library needed). */
@Composable
fun BarChart(values: List<Double>, labels: List<String>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary,
             highlightIndex: Int = -1, valueFormatter: (Double) -> String = { "" }) {
    val maxV = max(values.maxOrNull() ?: 0.0, 1e-9)
    val faded = color.copy(alpha = 0.35f)
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            values.forEach { v ->
                Text(if (v > 0) valueFormatter(v) else "", style = MaterialTheme.typography.labelSmall, color = onSurface,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 4.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val bw = slot * 0.56f
            values.forEachIndexed { i, v ->
                val bh = (v / maxV * size.height).toFloat().coerceAtLeast(if (v > 0) 4f else 2f)
                val x = i * slot + (slot - bw) / 2
                drawRoundRect(if (i == highlightIndex || highlightIndex < 0) color else faded, Offset(x, size.height - bh), Size(bw, bh),
                    androidx.compose.ui.geometry.CornerRadius(bw / 3, bw / 3))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = onSurface, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
    }
}

/**
 * Draws a ride route from GPS points (no map tiles, no API key). Points are projected onto a
 * flat local grid (fine for a ride-sized area) and scaled to fit. Gaps between segments are not joined.
 */
@Composable
fun RouteCanvas(points: List<GpsFix>, modifier: Modifier = Modifier, segments: List<Int>? = null) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Canvas(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        for (i in 1..5) {
            drawLine(grid, Offset(size.width * i / 6, 0f), Offset(size.width * i / 6, size.height))
            drawLine(grid, Offset(0f, size.height * i / 6), Offset(size.width, size.height * i / 6))
        }
        if (points.size < 2) return@Canvas
        val lat0 = points.first().lat
        val k = cos(Math.toRadians(lat0))
        val xs = points.map { it.lon * k }; val ys = points.map { it.lat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
        val pad = 24f
        val scale = (minOf(size.width, size.height) - 2 * pad) / span
        val ox = (size.width - (maxX - minX) * scale) / 2; val oy = (size.height - (maxY - minY) * scale) / 2
        fun pt(i: Int) = Offset((ox + (xs[i] - minX) * scale).toFloat(), (size.height - oy - (ys[i] - minY) * scale).toFloat())
        val path = Path()
        points.indices.forEach { i ->
            val newSegment = i == 0 || (segments != null && segments[i] != segments[i - 1])
            val p = pt(i)
            if (newSegment) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, line, style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color(0xFF2E9E5B), 10f, pt(0)); drawCircle(Color.White, 4f, pt(0))
        drawCircle(Color(0xFFD9534F), 10f, pt(points.lastIndex)); drawCircle(Color.White, 4f, pt(points.lastIndex))
    }
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}
