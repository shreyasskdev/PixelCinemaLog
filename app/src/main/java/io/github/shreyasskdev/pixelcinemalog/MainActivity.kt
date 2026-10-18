package io.github.shreyasskdev.pixelcinemalog

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PixelCinemaLogTheme { CinemaCameraScreen() } }
    }
}

enum class ProDialTab(val label: String) {
    NONE(""), LOG("PROFILE"), ISO("ISO"), SHUTTER("SHUTTER"),
    EV("EV"), WB("WB"), FOCUS("FOCUS"), ASPECT("RATIO")
}

private val HyperGold = Color(0xFFFFB300)
private val HyperRed = Color(0xFFFF2E2E)
private val HyperBlack = Color(0xFF08080A)
private val HyperSurface = Color(0xFF141519)
private val HyperPillBg = Color(0xFF1E2026)
private val HyperTextDim = Color(0xFF8E929B)
private val ZoomStops = listOf(0.5f, 1.0f, 2.0f, 5.0f, 10.0f)

// Pixel 9 Pro XL: telephoto native FOV ≈ 5× wide. Tune these two constants
// if you switch devices or want a wider/narrower crossfade band.
private const val CROSSFADE_START = 4.0f
private const val CROSSFADE_END   = 5.5f

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

// ── Shared UI helpers ──────────────────────────────────────────────────────

@Composable
private fun RecordingTimecodePill() {
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
private fun HyperProParamItem(title: String, value: String, active: Boolean, onClick: () -> Unit) {
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
private fun HyperTopChip(text: String, active: Boolean, onClick: () -> Unit) {
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
private fun HyperDialPill(text: String, selected: Boolean, onClick: () -> Unit) {
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

// ── Curve editor ──────────────────────────────────────────────────────────

enum class ZoomDurationMode { AUTO, FIXED }

enum class BezierPreset(val label: String, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    CINEMATIC("CINEMATIC", 0.25f, 0.10f, 0.25f, 1.00f),
    SMOOTH   ("SMOOTH",    0.42f, 0.00f, 0.58f, 1.00f),
    LINEAR   ("LINEAR",    0.25f, 0.25f, 0.75f, 0.75f),
    SNAP     ("SNAP",      0.15f, 0.85f, 0.25f, 1.00f),
    GLIDE    ("GLIDE",     0.15f, 0.00f, 0.15f, 1.00f),
    APPLE_CAM("APPLE CAM", 0.40f, 0.00f, 0.20f, 1.00f)
}

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
                Size(barWidth.coerceAtLeast(1f), barH))
        }
    }
}