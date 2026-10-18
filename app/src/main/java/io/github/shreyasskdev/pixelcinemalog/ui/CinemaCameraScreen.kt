// File: CinemaCameraScreen.kt
// Path: app/src/main/java/io/github/shreyasskdev/pixelcinemalog/ui/CinemaCameraScreen.kt
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
fun CinemaCameraScreen() {
    val context = LocalContext.current
    var hasCameraPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED)
    }
    var hasAllFilesPerm by remember { mutableStateOf(Environment.isExternalStorageManager()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms -> hasCameraPerm = perms[Manifest.permission.CAMERA] == true }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { hasAllFilesPerm = Environment.isExternalStorageManager() }

    LaunchedEffect(Unit) {
        if (!hasCameraPerm) launcher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        )
    }

    if (!hasCameraPerm || !hasAllFilesPerm) {
        Box(Modifier.fillMaxSize().background(HyperBlack), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(28.dp).clip(RoundedCornerShape(24.dp))
                    .background(HyperSurface).padding(24.dp)
            ) {
                Text("PIXEL CINEMA PRO", color = HyperGold, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp, fontSize = 16.sp)
                if (!hasCameraPerm) {
                    Button(onClick = {
                        launcher.launch(arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_MEDIA_VIDEO
                        ))
                    }, colors = ButtonDefaults.buttonColors(containerColor = HyperRed),
                        shape = RoundedCornerShape(14.dp)) {
                        Text("1. Grant Camera & Mic Access", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                if (!hasAllFilesPerm) {
                    Button(onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        storageLauncher.launch(intent)
                    }, colors = ButtonDefaults.buttonColors(containerColor = HyperGold),
                        shape = RoundedCornerShape(14.dp)) {
                        Text("2. Enable Lossless RAW16 Storage", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    var showRawPlayer by remember { mutableStateOf(false) }
    if (showRawPlayer) {
        Raw16CinemaPlayerScreen(onBackToCamera = { showRawPlayer = false })
        return
    }

    val controller = remember { PixelCameraController(context) }

    var selectedMode by remember { mutableStateOf(controller.currentMode) }
    var selectedAssist by remember { mutableStateOf(controller.currentAssist) }
    var selectedAspect by remember { mutableStateOf(controller.selectedAspectRatio) }
    var activeDial by remember { mutableStateOf(ProDialTab.NONE) }
    var showGrid by remember { mutableStateOf(true) }

    var targetZoomStop by remember { mutableFloatStateOf(1.0f) }
    var liveZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var isFrontCam by remember { mutableStateOf(controller.isFrontCamera) }
    var isConcurrentMode by remember { mutableStateOf(false) }

    var zoomEasingMode by remember { mutableStateOf(controller.zoomEasing) }
    var showCurveEditor by remember { mutableStateOf(false) }
    var quickSpeed by remember { mutableFloatStateOf(controller.zoomDurationScale) }

    var sessionRebuilding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        controller.setSessionRebuildingListener { sessionRebuilding = it }
    }

    // ── Dual TextureView refs for crossfade ──
    var primaryTextureView by remember { mutableStateOf<TextureView?>(null) }
    var secondaryTextureView by remember { mutableStateOf<TextureView?>(null) }
    var surfacesStarted by remember { mutableStateOf(false) }

    // Crossfade alpha, driven by the live zoom ratio (recomputed every frame the UI poller fires)
    val secondaryAlpha = remember(liveZoomRatio, isConcurrentMode) {
        if (!isConcurrentMode) 0f
        else when {
            liveZoomRatio <= CROSSFADE_START -> 0f
            liveZoomRatio >= CROSSFADE_END   -> 1f
            else -> {
                val t = (liveZoomRatio - CROSSFADE_START) / (CROSSFADE_END - CROSSFADE_START)
                // smoothstep for a gentle in/out — feels more like iOS than linear
                t * t * (3f - 2f * t)
            }
        }.coerceIn(0f, 1f)
    }

    // Once both TextureViews are attached, kick off the concurrent session.
    LaunchedEffect(primaryTextureView, secondaryTextureView) {
        if (primaryTextureView != null && secondaryTextureView != null && !surfacesStarted) {
            surfacesStarted = true
            val p = primaryTextureView?.surfaceTexture
            val s = secondaryTextureView?.surfaceTexture
            if (p != null && s != null) {
                p.setDefaultBufferSize(1920, 1080)
                s.setDefaultBufferSize(1920, 1080)
                controller.startCamera(Surface(p), Surface(s))
                // give the controller a moment to decide if concurrent mode is on
                delay(200)
                isConcurrentMode = controller.isConcurrentModeEnabled
            }
        }
    }

    // Keep isConcurrentMode in sync (session might rebuild after front switch)
    LaunchedEffect(sessionRebuilding) {
        if (!sessionRebuilding) {
            isConcurrentMode = controller.isConcurrentModeEnabled
        }
    }

    var isManualExp by remember { mutableStateOf(controller.isManualExposure) }
    var isoValue by remember { mutableIntStateOf(controller.manualIso) }
    var shutterNs by remember { mutableLongStateOf(controller.manualShutterNs) }
    var exposureEv by remember { mutableFloatStateOf(controller.exposureCompEv) }
    var wbPreset by remember { mutableStateOf(controller.wbPreset) }
    var isManualFocus by remember { mutableStateOf(controller.isManualFocus) }
    var focusDiopter by remember { mutableFloatStateOf(controller.manualFocusDiopter) }
    var selectedFps by remember { mutableIntStateOf(controller.selectedFps) }
    var is4K by remember { mutableStateOf(controller.is4KResolution) }
    var isRecording by remember { mutableStateOf(false) }

    val shutterLabel = remember(shutterNs) {
        val denom = (1_000_000_000L / shutterNs).toInt()
        "1/$denom"
    }

    Box(Modifier.fillMaxSize().background(HyperBlack)) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ZONE 1 ────────────────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth().zIndex(2f).background(HyperBlack)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(HyperPillBg)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(if (is4K) "4K" else "FHD", color = HyperGold,
                            fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                            modifier = Modifier.clickable(enabled = !isRecording) {
                                is4K = !is4K; controller.is4KResolution = is4K
                                controller.applyCurrentSettings()
                            })
                        Text("|", color = HyperTextDim, fontSize = 11.sp)
                        Text("${selectedFps}FPS", color = Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            modifier = Modifier.clickable(enabled = !isRecording) {
                                val next = when (selectedFps) { 24 -> 30; 30 -> 60; else -> 24 }
                                selectedFps = next; controller.selectedFps = next
                                controller.applyCurrentSettings()
                            })
                    }
                    if (isRecording) RecordingTimecodePill()
                    else Row(
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(HyperPillBg)
                            .clickable {
                                activeDial = if (activeDial == ProDialTab.ASPECT) ProDialTab.NONE else ProDialTab.ASPECT
                            }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(selectedAspect.label,
                            color = if (activeDial == ProDialTab.ASPECT) HyperGold else Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HyperTopChip("GRID", showGrid) { showGrid = !showGrid }
                        HyperTopChip("RAW LAB", false) { if (!isRecording) showRawPlayer = true }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(HyperGold.copy(alpha = 0.18f))
                                .border(1.dp, HyperGold.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(selectedMode.badge, color = HyperGold,
                                fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
                        }
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(HyperPillBg)
                                .clickable {
                                    val nextAssist = ViewfinderAssist.entries[
                                        (selectedAssist.ordinal + 1) % ViewfinderAssist.entries.size
                                    ]
                                    selectedAssist = nextAssist
                                    controller.currentAssist = nextAssist
                                    controller.applyCurrentSettings()
                                }.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("VIEW: ${selectedAssist.label.uppercase()}",
                                color = if (selectedAssist != ViewfinderAssist.OFF) HyperGold else HyperTextDim,
                                fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                    LiveHistogramScopeWidget(
                        isRawMode = selectedMode == ShootingMode.RAW_SENSOR_SLOG3 ||
                                selectedMode == ShootingMode.UNCOMPRESSED_RAW16,
                        modifier = Modifier.width(88.dp).height(22.dp)
                    )
                }
            }

            // ZONE 2 ────────────────────────────────────────────────────────────
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f).zIndex(1f).clipToBounds()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                val stageMaxW = maxWidth
                val stageMaxH = maxHeight
                val desiredRatio = if (selectedAspect == CinemaAspectRatio.FULL_STAGE) {
                    stageMaxW / stageMaxH
                } else selectedAspect.portraitRatio
                val fitByWidthH = stageMaxW / desiredRatio
                val targetW = if (fitByWidthH <= stageMaxH) stageMaxW else stageMaxH * desiredRatio
                val targetH = if (fitByWidthH <= stageMaxH) fitByWidthH else stageMaxH

                Box(
                    modifier = Modifier.size(targetW, targetH)
                        .clip(RoundedCornerShape(16.dp)).clipToBounds()
                        .border(
                            width = if (isRecording) 2.dp else 1.dp,
                            color = if (isRecording) HyperRed else Color.White.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(16.dp)
                        ).background(Color.Black)
                ) {
                    // Primary TextureView (wide / logical)
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                primaryTextureView = this
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {}
                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Secondary TextureView (tele) — crossfaded on top
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                secondaryTextureView = this
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {}
                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize().alpha(secondaryAlpha)
                    )

                    // Grid / anamorphic matte
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width; val h = size.height
                        if (selectedAspect == CinemaAspectRatio.ANAMORPHIC_239) {
                            val scopeHeight = w * (1f / 2.39f)
                            val matteH = ((h - scopeHeight) / 2f).coerceAtLeast(0f)
                            val matteColor = Color.Black.copy(alpha = 0.88f)
                            drawRect(matteColor, topLeft = Offset(0f, 0f), size = Size(w, matteH))
                            drawRect(matteColor, topLeft = Offset(0f, h - matteH), size = Size(w, matteH))
                            drawLine(HyperGold.copy(alpha = 0.6f), Offset(0f, matteH), Offset(w, matteH), 1.5f)
                            drawLine(HyperGold.copy(alpha = 0.6f), Offset(0f, h - matteH), Offset(w, h - matteH), 1.5f)
                        }
                        if (showGrid) {
                            val gridColor = Color.White.copy(alpha = 0.22f)
                            drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1.2f)
                            drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = 1.2f)
                            drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1.2f)
                            drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = 1.2f)
                            val cx = w / 2f; val cy = h / 2f
                            drawLine(HyperGold.copy(alpha = 0.45f), Offset(cx - 14f, cy), Offset(cx + 14f, cy), 1.5f)
                            drawLine(HyperGold.copy(alpha = 0.45f), Offset(cx, cy - 14f), Offset(cx, cy + 14f), 1.5f)
                        }
                    }

                    // Watermark with live zoom + concurrent mode indicator
                    Box(
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        val zoomFormatted = if (liveZoomRatio < 0.99f) "%.2fx".format(liveZoomRatio)
                        else if (abs(liveZoomRatio - liveZoomRatio.toInt()) < 0.02f) "${liveZoomRatio.toInt()}.0x"
                        else "%.2fx".format(liveZoomRatio)
                        val modeTag = if (isConcurrentMode) " · DUAL" else ""
                        Text(
                            text = "$zoomFormatted (${(liveZoomRatio * 24).toInt()}mm)${modeTag} • ${selectedAspect.shortTag} • ${
                                if (isManualExp) "ISO $isoValue  $shutterLabel" else "AUTO AE"
                            }",
                            color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                        )
                    }

                    // Session rebuild veil (only during raw/ISP mode switch)
                    val veilAlpha by animateFloatAsState(
                        targetValue = if (sessionRebuilding) 0.55f else 0f,
                        animationSpec = tween(120), label = "veil"
                    )
                    if (veilAlpha > 0.001f) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = veilAlpha)))
                    }
                }
            }

            // ZONE 3 ────────────────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth().zIndex(2f).background(HyperBlack)
                    .padding(top = 6.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedVisibility(
                    visible = activeDial != ProDialTab.NONE,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                            .clip(RoundedCornerShape(18.dp)).background(HyperSurface)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                            .padding(14.dp)
                    ) {
                        when (activeDial) {
                            ProDialTab.ASPECT -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("SENSOR ASPECT RATIO & FRAME GATE",
                                    color = HyperTextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(CinemaAspectRatio.entries) { ratio ->
                                        HyperDialPill(ratio.label, selectedAspect == ratio) {
                                            selectedAspect = ratio
                                            controller.selectedAspectRatio = ratio
                                            controller.applyCurrentSettings()
                                        }
                                    }
                                }
                            }
                            ProDialTab.LOG -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("COLOR SCIENCE & ALL-INTRA / RAW PIPELINE",
                                    color = HyperTextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(ShootingMode.entries) { mode ->
                                        HyperDialPill(mode.label, selectedMode == mode) {
                                            if (!isRecording) {
                                                selectedMode = mode
                                                controller.currentMode = mode
                                                controller.applyCurrentSettings()
                                            }
                                        }
                                    }
                                }
                            }
                            ProDialTab.ISO -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("SENSOR ISO SENSITIVITY", color = HyperTextDim,
                                        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    HyperTopChip(if (isManualExp) "MANUAL" else "AUTO AE", isManualExp) {
                                        isManualExp = !isManualExp
                                        controller.isManualExposure = isManualExp
                                        controller.applyCurrentSettings()
                                    }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)) { iso ->
                                        HyperDialPill("ISO $iso", isManualExp && isoValue == iso) {
                                            isManualExp = true; isoValue = iso
                                            controller.isManualExposure = true
                                            controller.manualIso = iso
                                            controller.applyCurrentSettings()
                                        }
                                    }
                                }
                            }
                            ProDialTab.SHUTTER -> {
                                val shutters = listOf(
                                    "1/24" to 41_666_666L, "1/48 (180°)" to 20_833_333L,
                                    "1/60" to 16_666_666L, "1/120" to 8_333_333L,
                                    "1/250" to 4_000_000L, "1/500" to 2_000_000L,
                                    "1/1000" to 1_000_000L, "1/2000" to 500_000L
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text("SHUTTER SPEED / ANGLE", color = HyperTextDim,
                                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        HyperTopChip(if (isManualExp) "MANUAL" else "AUTO AE", isManualExp) {
                                            isManualExp = !isManualExp
                                            controller.isManualExposure = isManualExp
                                            controller.applyCurrentSettings()
                                        }
                                    }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(shutters) { (label, ns) ->
                                            HyperDialPill(label, isManualExp && shutterNs == ns) {
                                                isManualExp = true; shutterNs = ns
                                                controller.isManualExposure = true
                                                controller.manualShutterNs = ns
                                                controller.applyCurrentSettings()
                                            }
                                        }
                                    }
                                }
                            }
                            ProDialTab.EV -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("EXP EV", color = HyperGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Slider(exposureEv, { exposureEv = it
                                    controller.exposureCompEv = it
                                    controller.applyCurrentSettings()
                                }, valueRange = -2.0f..2.0f, steps = 7,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = HyperGold,
                                        activeTrackColor = HyperGold))
                                Text("${if (exposureEv >= 0) "+" else ""}${"%.1f".format(exposureEv)} EV",
                                    color = Color.White, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            ProDialTab.WB -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("KELVIN WHITE BALANCE", color = HyperTextDim,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(WhiteBalancePreset.entries) { preset ->
                                        HyperDialPill(preset.label, wbPreset == preset) {
                                            wbPreset = preset; controller.wbPreset = preset
                                            controller.applyCurrentSettings()
                                        }
                                    }
                                }
                            }
                            ProDialTab.FOCUS -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                HyperTopChip(if (isManualFocus) "MF" else "AF-C", isManualFocus) {
                                    isManualFocus = !isManualFocus
                                    controller.isManualFocus = isManualFocus
                                    controller.applyCurrentSettings()
                                }
                                Text("∞", color = Color.White, fontSize = 15.sp)
                                Slider(focusDiopter, {
                                    isManualFocus = true; focusDiopter = it
                                    controller.isManualFocus = true
                                    controller.manualFocusDiopter = it
                                    controller.applyCurrentSettings()
                                }, valueRange = 0f..controller.maxFocusDistance,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = HyperGold,
                                        activeTrackColor = HyperGold))
                                Text("MACRO", color = HyperGold, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold)
                            }
                            ProDialTab.NONE -> {}
                        }
                    }
                }

                // Zoom ring — static stop labels
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(28.dp)).background(HyperSurface)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ZoomStops.forEach { stop ->
                        val isSelected = !isFrontCam && abs(targetZoomStop - stop) < 0.05f
                        val label = if (stop < 1f) ".5x" else "${stop.toInt()}x"
                        Box(
                            modifier = Modifier.clip(CircleShape)
                                .background(if (isSelected) HyperGold else Color.Transparent)
                                .clickable {
                                    if (isFrontCam && !isRecording) {
                                        controller.toggleFrontBackCamera { _ ->
                                            isFrontCam = controller.isFrontCamera
                                            targetZoomStop = stop
                                            controller.animateZoomTo(stop) { z -> liveZoomRatio = z }
                                        }
                                    } else {
                                        targetZoomStop = stop
                                        controller.animateZoomTo(stop) { z -> liveZoomRatio = z }
                                    }
                                }.padding(horizontal = 11.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (isSelected) Color.Black else Color.White,
                                fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                        }
                    }

                    Box(
                        modifier = Modifier.clip(CircleShape)
                            .background(if (zoomEasingMode == ZoomEasing.CUSTOM_BEZIER) HyperGold else HyperPillBg)
                            .clickable { showCurveEditor = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (zoomEasingMode == ZoomEasing.CUSTOM_BEZIER) "CURVE ✎" else "CURVE",
                            color = if (zoomEasingMode == ZoomEasing.CUSTOM_BEZIER) Color.Black else HyperTextDim,
                            fontWeight = FontWeight.ExtraBold, fontSize = 9.sp)
                    }

                    Box(
                        modifier = Modifier.clip(CircleShape)
                            .background(
                                if (abs(quickSpeed - 1f) > 0.05f) HyperGold.copy(alpha = 0.28f)
                                else HyperPillBg
                            ).clickable {
                                val next = when {
                                    quickSpeed < 0.75f -> 1.0f
                                    quickSpeed < 1.25f -> 1.5f
                                    quickSpeed < 1.75f -> 2.0f
                                    else -> 0.5f
                                }
                                controller.setZoomDurationScale(next)
                                quickSpeed = next
                            }.padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${"%.1f".format(quickSpeed)}×",
                            color = if (abs(quickSpeed - 1f) > 0.05f) HyperGold else HyperTextDim,
                            fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
                    }

                    if (!isRecording) {
                        Box(
                            modifier = Modifier.clip(CircleShape)
                                .background(if (isFrontCam) HyperGold else HyperPillBg)
                                .clickable {
                                    controller.toggleFrontBackCamera { z ->
                                        isFrontCam = controller.isFrontCamera
                                        targetZoomStop = z
                                        liveZoomRatio = z
                                    }
                                }.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("FRONT",
                                color = if (isFrontCam) Color.Black else HyperTextDim,
                                fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
                        }
                    }
                }

                // Parameter strip
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(14.dp)).background(HyperSurface)
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HyperProParamItem("LOG", selectedMode.badge.take(7), activeDial == ProDialTab.LOG) {
                        activeDial = if (activeDial == ProDialTab.LOG) ProDialTab.NONE else ProDialTab.LOG
                    }
                    HyperProParamItem("ISO", if (isManualExp) "$isoValue" else "AUTO", activeDial == ProDialTab.ISO) {
                        activeDial = if (activeDial == ProDialTab.ISO) ProDialTab.NONE else ProDialTab.ISO
                    }
                    HyperProParamItem("SHT", if (isManualExp) shutterLabel else "AUTO", activeDial == ProDialTab.SHUTTER) {
                        activeDial = if (activeDial == ProDialTab.SHUTTER) ProDialTab.NONE else ProDialTab.SHUTTER
                    }
                    HyperProParamItem("EV",
                        "${if (exposureEv >= 0) "+" else ""}${"%.1f".format(exposureEv)}",
                        activeDial == ProDialTab.EV) {
                        activeDial = if (activeDial == ProDialTab.EV) ProDialTab.NONE else ProDialTab.EV
                    }
                    HyperProParamItem("WB", wbPreset.label, activeDial == ProDialTab.WB) {
                        activeDial = if (activeDial == ProDialTab.WB) ProDialTab.NONE else ProDialTab.WB
                    }
                    HyperProParamItem("FOCUS", if (isManualFocus) "MF" else "AF-C",
                        activeDial == ProDialTab.FOCUS) {
                        activeDial = if (activeDial == ProDialTab.FOCUS) ProDialTab.NONE else ProDialTab.FOCUS
                    }
                }

                // Shutter row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                            val nextIdx = (selectedAspect.ordinal + 1) % CinemaAspectRatio.entries.size
                            val nextRatio = CinemaAspectRatio.entries[nextIdx]
                            selectedAspect = nextRatio
                            controller.selectedAspectRatio = nextRatio
                            controller.applyCurrentSettings()
                        }.padding(8.dp)
                    ) {
                        Text("GATE", color = HyperTextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(selectedAspect.shortTag, color = HyperGold,
                            fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                    Box(
                        modifier = Modifier.size(76.dp).clip(CircleShape)
                            .border(3.5.dp, Color.White, CircleShape).padding(7.dp)
                            .clip(if (isRecording) RoundedCornerShape(16.dp) else CircleShape)
                            .background(HyperRed)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { controller.toggleRecording(context) { isRecording = it } },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRecording) Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                            isManualExp = !isManualExp
                            controller.isManualExposure = isManualExp
                            controller.applyCurrentSettings()
                        }.padding(8.dp)
                    ) {
                        Text("EXP", color = HyperTextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(if (isManualExp) "MAN" else "AUTO",
                            color = if (isManualExp) HyperGold else Color.White,
                            fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
            }
        }

        // Curve editor modal
        if (showCurveEditor) {
            val current = remember { controller.getCustomBezier() }
            val currentFixedDuration = remember { controller.zoomDurationFixed }
            val currentDurationScale = remember { controller.zoomDurationScale }
            Box(
                modifier = Modifier.fillMaxSize().zIndex(20f)
                    .background(Color.Black.copy(alpha = 0.88f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showCurveEditor = false },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.padding(20.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                ) {
                    ZoomEasingEditorPanel(
                        initialX1 = current[0], initialY1 = current[1],
                        initialX2 = current[2], initialY2 = current[3],
                        initialDurationFixed = currentFixedDuration,
                        initialDurationScale = currentDurationScale,
                        onApply = { nx1, ny1, nx2, ny2, fixedSecs, scale ->
                            controller.setCustomBezier(nx1, ny1, nx2, ny2)
                            controller.setZoomDurationFixed(fixedSecs)
                            controller.setZoomDurationScale(scale)
                            quickSpeed = scale
                            zoomEasingMode = ZoomEasing.CUSTOM_BEZIER
                        },
                        onLiveTest = {
                            showCurveEditor = false
                            targetZoomStop = 2f
                            controller.animateZoomTo(2f) { liveZoomRatio = it }
                        },
                        onClose = { showCurveEditor = false }
                    )
                }
            }
        }
    }
}
