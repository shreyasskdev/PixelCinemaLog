// File: ZoomEasingEditorPanel.kt
// Path: app/src/main/java/io/github/shreyasskdev/pixelcinemalog/ui/ZoomEasingEditorPanel.kt
package io.github.shreyasskdev.pixelcinemalog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shreyasskdev.pixelcinemalog.models.BezierPreset
import io.github.shreyasskdev.pixelcinemalog.models.ZoomDurationMode
import io.github.shreyasskdev.pixelcinemalog.ui.components.*
import kotlin.math.abs
import kotlin.math.hypot

@Composable
fun ZoomEasingEditorPanel(
    initialX1: Float, initialY1: Float,
    initialX2: Float, initialY2: Float,
    initialDurationFixed: Float,
    initialDurationScale: Float,
    onApply: (Float, Float, Float, Float, Float, Float) -> Unit,
    onLiveTest: () -> Unit,
    onClose: () -> Unit
) {
    var x1 by remember { mutableFloatStateOf(initialX1) }
    var y1 by remember { mutableFloatStateOf(initialY1) }
    var x2 by remember { mutableFloatStateOf(initialX2) }
    var y2 by remember { mutableFloatStateOf(initialY2) }
    var durationMode by remember {
        mutableStateOf(if (initialDurationFixed > 0f) ZoomDurationMode.FIXED else ZoomDurationMode.AUTO)
    }
    var durationSecs by remember {
        mutableFloatStateOf(if (initialDurationFixed > 0f) initialDurationFixed else 0.75f)
    }
    var durationScale by remember { mutableFloatStateOf(initialDurationScale.coerceIn(0.3f, 3f)) }

    val previewTime = remember { Animatable(0f) }
    var previewTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(previewTrigger, durationMode, durationSecs, durationScale) {
        if (previewTrigger > 0) {
            previewTime.snapTo(0f)
            val ms = if (durationMode == ZoomDurationMode.FIXED)
                (durationSecs * 1000f).toInt().coerceIn(150, 5000)
            else (750f * durationScale).toInt().coerceIn(150, 5000)
            previewTime.animateTo(1f, tween(ms, easing = LinearEasing))
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(HyperSurface)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("ZOOM CURVE EDITOR", color = HyperGold, fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Box(Modifier.clip(CircleShape).background(HyperPillBg).clickable { onClose() }
                .padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("×", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        BezierCurveEditor(x1, y1, x2, y2, previewTime.value, previewTrigger > 0) { nx1, ny1, nx2, ny2 ->
            x1 = nx1; y1 = ny1; x2 = nx2; y2 = ny2
        }
        Text("cubic-bezier(${"%.2f".format(x1)}, ${"%.2f".format(y1)}, " +
                "${"%.2f".format(x2)}, ${"%.2f".format(y2)})",
            color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(BezierPreset.entries) { preset ->
                HyperDialPill(preset.label, false) {
                    x1 = preset.x1; y1 = preset.y1; x2 = preset.x2; y2 = preset.y2
                    previewTrigger++
                }
            }
        }
        Divider(color = Color.White.copy(alpha = 0.08f))
        Text("DURATION", color = HyperTextDim, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            HyperDialPill("AUTO", durationMode == ZoomDurationMode.AUTO) { durationMode = ZoomDurationMode.AUTO }
            HyperDialPill("FIXED", durationMode == ZoomDurationMode.FIXED) { durationMode = ZoomDurationMode.FIXED }
            Spacer(Modifier.width(4.dp))
            Text(
                text = when {
                    durationMode == ZoomDurationMode.FIXED -> "${"%.2f".format(durationSecs)}s"
                    abs(durationScale - 1f) < 0.05f -> "× 1.0 (normal)"
                    durationScale < 1f -> "× ${"%.2f".format(durationScale)} (faster)"
                    else -> "× ${"%.2f".format(durationScale)} (slower)"
                },
                color = when {
                    durationMode == ZoomDurationMode.FIXED -> HyperGold
                    abs(durationScale - 1f) < 0.05f -> HyperTextDim
                    else -> HyperGold
                },
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
        }
        if (durationMode == ZoomDurationMode.FIXED) {
            Slider(durationSecs, { durationSecs = it }, valueRange = 0.15f..3.0f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = HyperGold, activeTrackColor = HyperGold))
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${"%.2f".format(durationScale)}×", color = HyperGold, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(48.dp))
                Slider(durationScale, { durationScale = it }, valueRange = 0.3f..3.0f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = HyperGold, activeTrackColor = HyperGold))
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(HyperPillBg)
                    .clickable { durationScale = 1.0f }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("1×", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("FAST", color = HyperTextDim, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text("NORMAL", color = HyperTextDim, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text("SLOW", color = HyperTextDim, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ previewTrigger++ }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = HyperPillBg),
                shape = RoundedCornerShape(10.dp)) {
                Text("▶ PREVIEW", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Button({
                onApply(x1, y1, x2, y2,
                    if (durationMode == ZoomDurationMode.FIXED) durationSecs else 0f,
                    durationScale)
                onLiveTest()
            }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = HyperGold),
                shape = RoundedCornerShape(10.dp)) {
                Text("APPLY + TEST", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun BezierCurveEditor(
    x1: Float, y1: Float, x2: Float, y2: Float,
    previewTime: Float, previewActive: Boolean,
    onChanged: (Float, Float, Float, Float) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
    ) {
        val plotPadding = 24f
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val plotW = (wPx - 2f * plotPadding).coerceAtLeast(1f)
        val plotH = (hPx - 2f * plotPadding).coerceAtLeast(1f)
        var dragging by remember { mutableIntStateOf(0) }

        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    val p1x = plotPadding + x1 * plotW
                    val p1y = hPx - plotPadding - y1 * plotH
                    val p2x = plotPadding + x2 * plotW
                    val p2y = hPx - plotPadding - y2 * plotH
                    val d1 = hypot(offset.x - p1x, offset.y - p1y)
                    val d2 = hypot(offset.x - p2x, offset.y - p2y)
                    dragging = when {
                        minOf(d1, d2) > 90f -> 0
                        d1 <= d2 -> 1
                        else -> 2
                    }
                },
                onDragEnd = { dragging = 0 },
                onDragCancel = { dragging = 0 },
                onDrag = { change, _ ->
                    if (dragging != 0) {
                        val nx = ((change.position.x - plotPadding) / plotW).coerceIn(0f, 1f)
                        val ny = ((hPx - plotPadding - change.position.y) / plotH).coerceIn(0f, 1f)
                        if (dragging == 1) onChanged(nx, ny, x2, y2) else onChanged(x1, y1, nx, ny)
                    }
                }
            )
        }) {
            for (i in 1..4) {
                val gx = plotPadding + i * plotW / 5f
                val gy = plotPadding + i * plotH / 5f
                drawLine(Color.White.copy(alpha = 0.05f), Offset(gx, plotPadding), Offset(gx, hPx - plotPadding), 1f)
                drawLine(Color.White.copy(alpha = 0.05f), Offset(plotPadding, gy), Offset(wPx - plotPadding, gy), 1f)
            }
            drawLine(Color.White.copy(alpha = 0.18f),
                Offset(plotPadding, hPx - plotPadding), Offset(wPx - plotPadding, plotPadding), 1.2f)
            val path = Path()
            val samples = 96
            for (i in 0..samples) {
                val t = i / samples.toFloat()
                val bx = bezierX(t, x1, x2); val by = bezierY(t, y1, y2)
                val px = plotPadding + bx * plotW; val py = hPx - plotPadding - by * plotH
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, HyperGold, style = Stroke(3f, cap = StrokeCap.Round))
            val p1x = plotPadding + x1 * plotW; val p1y = hPx - plotPadding - y1 * plotH
            val p2x = plotPadding + x2 * plotW; val p2y = hPx - plotPadding - y2 * plotH
            drawLine(HyperGold.copy(alpha = 0.45f),
                Offset(plotPadding, hPx - plotPadding), Offset(p1x, p1y), 1.4f)
            drawLine(HyperRed.copy(alpha = 0.45f),
                Offset(wPx - plotPadding, plotPadding), Offset(p2x, p2y), 1.4f)
            drawCircle(HyperGold, radius = 11f, center = Offset(p1x, p1y))
            drawCircle(Color.White, radius = 4f, center = Offset(p1x, p1y))
            drawCircle(HyperRed, radius = 11f, center = Offset(p2x, p2y))
            drawCircle(Color.White, radius = 4f, center = Offset(p2x, p2y))
            if (previewActive && previewTime in 0f..1f) {
                val outY = bezierYForX(previewTime, x1, y1, x2, y2)
                val px = plotPadding + previewTime * plotW
                val py = hPx - plotPadding - outY * plotH
                drawCircle(Color.White.copy(alpha = 0.30f), radius = 12f, center = Offset(px, py))
                drawCircle(Color.White, radius = 7f, center = Offset(px, py))
                drawCircle(HyperGold, radius = 3.5f, center = Offset(px, py))
            }
        }
    }
}

private fun bezierX(t: Float, x1: Float, x2: Float): Float {
    val mt = 1f - t
    return 3f * mt * mt * t * x1 + 3f * mt * t * t * x2 + t * t * t
}
private fun bezierY(t: Float, y1: Float, y2: Float): Float {
    val mt = 1f - t
    return 3f * mt * mt * t * y1 + 3f * mt * t * t * y2 + t * t * t
}
private fun bezierDX(t: Float, x1: Float, x2: Float): Float {
    val mt = 1f - t
    return 3f * mt * mt * x1 + 6f * mt * t * (x2 - x1) + 3f * t * t * (1f - x2)
}
private fun bezierYForX(x: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    if (abs(x1 - y1) < 1e-6f && abs(x2 - y2) < 1e-6f) return x
    var t = x
    for (i in 0 until 8) {
        val err = bezierX(t, x1, x2) - x
        if (abs(err) < 1e-5f) break
        val d = bezierDX(t, x1, x2)
        if (abs(d) < 1e-5f) break
        t = (t - err / d).coerceIn(0f, 1f)
    }
    return bezierY(t, y1, y2)
}
