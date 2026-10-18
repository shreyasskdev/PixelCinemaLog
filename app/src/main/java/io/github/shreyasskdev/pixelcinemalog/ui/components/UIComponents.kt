// File: UIComponents.kt
// Path: app/src/main/java/io/github/shreyasskdev/pixelcinemalog/ui/components/UIComponents.kt
package io.github.shreyasskdev.pixelcinemalog.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shreyasskdev.pixelcinemalog.camera.NativeVulkanEngine
import kotlinx.coroutines.delay

val HyperGold = Color(0xFFFFB300)
val HyperRed = Color(0xFFFF2E2E)
val HyperBlack = Color(0xFF08080A)
val HyperSurface = Color(0xFF141519)
val HyperPillBg = Color(0xFF1E2026)
val HyperTextDim = Color(0xFF8E929B)
val ZoomStops = listOf(0.5f, 1.0f, 2.0f, 5.0f, 10.0f)
const val CROSSFADE_START = 4.0f
const val CROSSFADE_END = 5.0f

@Composable
fun RecordingTimecodePill() {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1000L); seconds++ } }
    Row(
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
            .background(HyperRed.copy(alpha = 0.2f))
            .border(1.dp, HyperRed, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(HyperRed))
        val mins = (seconds / 60).toString().padStart(2, '0')
        val secs = (seconds % 60).toString().padStart(2, '0')
        Text("00:$mins:$secs", color = Color.White, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun LiveHistogramScopeWidget(isRawMode: Boolean, modifier: Modifier = Modifier) {
    var bins by remember { mutableStateOf(FloatArray(64)) }
    LaunchedEffect(isRawMode) {
        if (isRawMode) while (true) { delay(250L); bins = NativeVulkanEngine.getLiveHistogram() }
    }
    HistogramScopeView(bins, modifier)
}

@Composable
fun HistogramScopeView(bins: FloatArray, modifier: Modifier = Modifier) {
    Canvas(Modifier.then(modifier).clip(RoundedCornerShape(6.dp)).background(HyperPillBg)
        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(2.dp)) {
        val w = size.width; val h = size.height
        val barWidth = w / 64f
        for (i in 0 until 64) {
            val v = bins.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val barH = v * h
            val barColor = when {
                i >= 60 -> HyperRed
                i in 24..28 -> Color(0xFF00E676)
                else -> HyperGold
            }
            drawRect(barColor, Offset(i * barWidth, h - barH),
                Size(barWidth.coerceAtLeast(1f), barH)
            )
        }
    }
}

@Composable
fun HyperProParamItem(title: String, value: String, active: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (active) HyperGold.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onClick() }.padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(title, color = if (active) HyperGold else HyperTextDim,
            fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = if (active) HyperGold else Color.White,
            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun HyperTopChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (active) HyperGold else HyperPillBg)
            .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (active) Color.Black else Color.White,
            fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
fun HyperDialPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) HyperGold else HyperPillBg)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) Color.Black else Color.White,
            fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}
