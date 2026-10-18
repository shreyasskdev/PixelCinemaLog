// File: Raw16CinemaPlayerScreen.kt
// Path: app/src/main/java/io/github/shreyasskdev/pixelcinemalog/ui/Raw16CinemaPlayerScreen.kt
package io.github.shreyasskdev.pixelcinemalog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import io.github.shreyasskdev.pixelcinemalog.ui.theme.PixelCinemaLogTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.shreyasskdev.pixelcinemalog.models.*
import io.github.shreyasskdev.pixelcinemalog.*
import io.github.shreyasskdev.pixelcinemalog.models.*
import io.github.shreyasskdev.pixelcinemalog.camera.*
import io.github.shreyasskdev.pixelcinemalog.ui.components.*

@Composable
fun Raw16CinemaPlayerScreen(onBackToCamera: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val moviesDir = remember {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "PixelCinemaLog")
    }
    var rawFiles by remember {
        mutableStateOf(
            moviesDir.listFiles { f -> f.extension.equals("raw16", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        )
    }
    var selectedFile by remember { mutableStateOf(rawFiles.firstOrNull()) }
    var totalFrames by remember { mutableIntStateOf(0) }
    var currentFrame by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var postTransferMode by remember { mutableIntStateOf(2) }
    var postAssist by remember { mutableIntStateOf(0) }
    var postExposureEv by remember { mutableFloatStateOf(0.0f) }
    var playerSurface by remember { mutableStateOf<Surface?>(null) }
    var histogramBins by remember { mutableStateOf(FloatArray(64)) }
    var isExporting by remember { mutableStateOf(false) }
    var exportStatusText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedFile) {
        selectedFile?.let {
            totalFrames = NativeVulkanEngine.getRaw16FrameCount(it.absolutePath).coerceAtLeast(1)
            currentFrame = 0
            exportStatusText = null
        }
    }
    LaunchedEffect(selectedFile, currentFrame, postTransferMode, postAssist, postExposureEv, playerSurface) {
        val file = selectedFile ?: return@LaunchedEffect
        val surf = playerSurface ?: return@LaunchedEffect
        if (!isExporting) withContext(Dispatchers.IO) {
            NativeVulkanEngine.renderRaw16FrameFromFile(
                file.absolutePath, currentFrame, postTransferMode, postAssist, postExposureEv, surf
            )
            histogramBins = NativeVulkanEngine.getLiveHistogram()
        }
    }
    LaunchedEffect(isPlaying, totalFrames) {
        while (isPlaying && totalFrames > 0 && !isExporting) {
            delay(41L); currentFrame = (currentFrame + 1) % totalFrames
        }
    }

    Column(Modifier.fillMaxSize().background(HyperBlack).statusBarsPadding().navigationBarsPadding(),
        verticalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp))
            .background(HyperSurface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                HyperTopChip("← CAMERA", false) { if (!isExporting) onBackToCamera() }
                Text("16-BIT RAW LAB & EXPORTER", color = HyperGold,
                    fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                HistogramScopeView(histogramBins, Modifier.width(80.dp).height(24.dp))
            }
            if (rawFiles.isEmpty()) Text(
                "No .raw16 files. Record in 'Lossless RAW16' first!",
                color = HyperTextDim, fontSize = 12.sp
            ) else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rawFiles) { file ->
                    val sizeMb = file.length() / (1024 * 1024)
                    HyperDialPill("${file.name.takeLast(16)} (${sizeMb}MB)", selectedFile == file) {
                        if (!isExporting) { isPlaying = false; selectedFile = file }
                    }
                }
            }
            exportStatusText?.let { msg ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1B5E20)).padding(8.dp)) {
                    Text(msg, color = Color.White, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()
            .padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            val ratio = 3f / 4f
            val fitH = maxWidth / ratio
            val wDp = if (fitH <= maxHeight) maxWidth else maxHeight * ratio
            val hDp = if (fitH <= maxHeight) fitH else maxHeight
            Box(Modifier.size(wDp, hDp).border(1.dp, Color.White.copy(alpha = 0.15f))) {
                AndroidView(factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) { playerSurface = h.surface }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) { playerSurface = null }
                        })
                    }
                }, modifier = Modifier.fillMaxSize())
            }
        }
        if (selectedFile != null) {
            Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(18.dp))
                .background(HyperSurface).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HyperTopChip(if (isPlaying) "PAUSE" else "PLAY", isPlaying) {
                        if (!isExporting) isPlaying = !isPlaying
                    }
                    Slider(currentFrame.toFloat(), {
                        if (!isExporting) { isPlaying = false; currentFrame = it.toInt() }
                    }, valueRange = 0f..(totalFrames - 1).coerceAtLeast(1).toFloat(),
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = HyperGold, activeTrackColor = HyperGold))
                    Text("F ${currentFrame + 1}/$totalFrames", color = Color.White,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("RAW EV:", color = HyperGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Slider(postExposureEv, { if (!isExporting) postExposureEv = it },
                        valueRange = -3.0f..3.0f, modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = HyperGold, activeTrackColor = HyperGold))
                    Text("${"%.1f".format(postExposureEv)} EV", color = Color.White,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val profiles = listOf("S-Log3" to 2, "ARRI LogC4" to 3,
                        "C-Log3" to 5, "Apple Log" to 6, "Rec.709" to 0)
                    profiles.forEach { (name, modeId) ->
                        HyperDialPill(name, postTransferMode == modeId) {
                            if (!isExporting) postTransferMode = modeId
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({
                        val file = selectedFile ?: return@Button
                        if (isExporting) return@Button
                        isPlaying = false; isExporting = true
                        val outDir = File(moviesDir, "DNG_${file.nameWithoutExtension}")
                        exportStatusText = "Exporting 16-Bit CinemaDNG..."
                        scope.launch {
                            val count = withContext(Dispatchers.IO) {
                                NativeVulkanEngine.exportRaw16ToCinemaDng(file.absolutePath, outDir.absolutePath)
                            }
                            isExporting = false
                            exportStatusText = "Exported $count frames"
                        }
                    }, enabled = !isExporting, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = HyperGold),
                        shape = RoundedCornerShape(12.dp)) {
                        Text(if (isExporting) "EXPORTING..." else "EXPORT CINEMADNG",
                            color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                    }
                    Button({
                        val file = selectedFile ?: return@Button
                        if (isExporting) return@Button
                        isPlaying = false; isExporting = true
                        val outMp4 = File(moviesDir,
                            "${file.nameWithoutExtension}_GRADED_${System.currentTimeMillis()}.mp4")
                        exportStatusText = "Rendering Graded HEVC..."
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                try {
                                    val recorder = MediaRecorder(context).apply {
                                        setVideoSource(MediaRecorder.VideoSource.SURFACE)
                                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                        setOutputFile(outMp4.absolutePath)
                                        setVideoEncodingBitRate(120_000_000)
                                        setVideoFrameRate(24); setVideoSize(1920, 1080)
                                        setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                                        prepare(); start()
                                    }
                                    val recSurface = recorder.surface
                                    for (fIdx in 0 until totalFrames) {
                                        NativeVulkanEngine.renderRaw16FrameFromFile(
                                            file.absolutePath, fIdx, postTransferMode,
                                            if (postAssist == 1) 1 else 0, postExposureEv, recSurface)
                                        delay(41L)
                                    }
                                    recorder.stop(); recorder.release(); true
                                } catch (_: Exception) { false }
                            }
                            isExporting = false
                            exportStatusText = if (ok) "Saved -> ${outMp4.name}" else "Export failed"
                        }
                    }, enabled = !isExporting, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = HyperRed),
                        shape = RoundedCornerShape(12.dp)) {
                        Text("EXPORT GRADED .MP4", color = Color.White,
                            fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
