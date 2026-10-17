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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixelCinemaLogTheme {
                CinemaCameraScreen()
            }
        }
    }
}

enum class ProDialTab(val label: String) {
    NONE(""),
    LOG("PROFILE"),
    ISO("ISO"),
    SHUTTER("SHUTTER"),
    EV("EV"),
    WB("WB"),
    FOCUS("FOCUS"),
    ASPECT("RATIO")
}

private val HyperGold = Color(0xFFFFB300)
private val HyperRed = Color(0xFFFF2E2E)
private val HyperBlack = Color(0xFF08080A)
private val HyperSurface = Color(0xFF141519)
private val HyperPillBg = Color(0xFF1E2026)
private val HyperTextDim = Color(0xFF8E929B)

private val ZoomStops = listOf(0.5f, 1.0f, 2.0f, 5.0f, 10.0f)

@Composable
fun CinemaCameraScreen() {
    val context = LocalContext.current
    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAllFilesPerm by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasCameraPerm = perms[Manifest.permission.CAMERA] == true
    }

    val storageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasAllFilesPerm = Environment.isExternalStorageManager()
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPerm) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            )
        }
    }

    if (!hasCameraPerm || !hasAllFilesPerm) {
        Box(
            modifier = Modifier.fillMaxSize().background(HyperBlack),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(28.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(HyperSurface)
                    .padding(24.dp)
            ) {
                Text(
                    text = "PIXEL CINEMA PRO",
                    color = HyperGold,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    fontSize = 16.sp
                )
                if (!hasCameraPerm) {
                    Button(
                        onClick = {
                            launcher.launch(
                                arrayOf(
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.READ_MEDIA_VIDEO
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = HyperRed),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("1. Grant Camera & Mic Access", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                if (!hasAllFilesPerm) {
                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            storageLauncher.launch(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = HyperGold),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            "2. Enable Lossless RAW16 Storage",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
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

    // Reactive UI State
    var selectedMode by remember { mutableStateOf(controller.currentMode) }
    var selectedAssist by remember { mutableStateOf(controller.currentAssist) }
    var selectedAspect by remember { mutableStateOf(controller.selectedAspectRatio) }
    var activeDial by remember { mutableStateOf(ProDialTab.NONE) }
    var showGrid by remember { mutableStateOf(true) }

    // Smooth 120Hz GPU Shader Zoom State
    var targetZoomStop by remember { mutableFloatStateOf(1.0f) }
    var liveZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var gpuShaderScale by remember { mutableFloatStateOf(1.0f) }
    var isFrontCam by remember { mutableStateOf(controller.isFrontCamera) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HyperBlack)
            .statusBarsPadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // =========================================================================
        // ZONE 1: HYPEROS TOP UTILITY & TELEMETRY BAR
        // =========================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(2f)
                .background(HyperBlack)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(HyperPillBg)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (is4K) "4K" else "FHD",
                        color = HyperGold,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable(enabled = !isRecording) {
                            is4K = !is4K
                            controller.is4KResolution = is4K
                            controller.applyCurrentSettings()
                        }
                    )
                    Text(text = "|", color = HyperTextDim, fontSize = 11.sp)
                    Text(
                        text = "${selectedFps}FPS",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable(enabled = !isRecording) {
                            val nextFps = when (selectedFps) {
                                24 -> 30
                                30 -> 60
                                else -> 24
                            }
                            selectedFps = nextFps
                            controller.selectedFps = nextFps
                            controller.applyCurrentSettings()
                        }
                    )
                }

                if (isRecording) {
                    RecordingTimecodePill()
                } else {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(HyperPillBg)
                            .clickable {
                                activeDial = if (activeDial == ProDialTab.ASPECT) ProDialTab.NONE else ProDialTab.ASPECT
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = selectedAspect.label,
                            color = if (activeDial == ProDialTab.ASPECT) HyperGold else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HyperTopChip(text = "GRID", active = showGrid) { showGrid = !showGrid }
                    HyperTopChip(text = "RAW LAB", active = false) {
                        if (!isRecording) showRawPlayer = true
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(HyperGold.copy(alpha = 0.18f))
                            .border(1.dp, HyperGold.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = selectedMode.badge,
                            color = HyperGold,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(HyperPillBg)
                            .clickable {
                                val nextAssist = ViewfinderAssist.entries[
                                    (selectedAssist.ordinal + 1) % ViewfinderAssist.entries.size
                                ]
                                selectedAssist = nextAssist
                                controller.currentAssist = nextAssist
                                controller.applyCurrentSettings()
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "VIEW: ${selectedAssist.label.uppercase()}",
                            color = if (selectedAssist != ViewfinderAssist.OFF) HyperGold else HyperTextDim,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }

                LiveHistogramScopeWidget(
                    isRawMode = selectedMode == ShootingMode.RAW_SENSOR_SLOG3 ||
                            selectedMode == ShootingMode.UNCOMPRESSED_RAW16,
                    modifier = Modifier.width(88.dp).height(22.dp)
                )
            }
        }

        // =========================================================================
        // ZONE 2: 120HZ GPU-SHADER VIEWFINDER STAGE (Clipped & Bounded)
        // =========================================================================
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .zIndex(1f)
                .clipToBounds()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            val stageMaxW = maxWidth
            val stageMaxH = maxHeight

            val desiredRatio = if (selectedAspect == CinemaAspectRatio.FULL_STAGE) {
                stageMaxW / stageMaxH
            } else {
                selectedAspect.portraitRatio
            }

            val fitByWidthH = stageMaxW / desiredRatio
            val targetW = if (fitByWidthH <= stageMaxH) stageMaxW else stageMaxH * desiredRatio
            val targetH = if (fitByWidthH <= stageMaxH) fitByWidthH else stageMaxH

            Box(
                modifier = Modifier
                    .size(targetW, targetH)
                    .clip(RoundedCornerShape(16.dp))
                    .clipToBounds()
                    .border(
                        width = if (isRecording) 2.dp else 1.dp,
                        color = if (isRecording) HyperRed else Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .background(Color.Black)
            ) {
                // TextureView + GPU Hardware Layer Scale (120Hz Sub-Pixel Bilinear Shader Zoom!)
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    st.setDefaultBufferSize(1920, 1080)
                                    controller.startCamera(Surface(st))
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

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

                        val cx = w / 2f
                        val cy = h / 2f
                        drawLine(HyperGold.copy(alpha = 0.45f), Offset(cx - 14f, cy), Offset(cx + 14f, cy), 1.5f)
                        drawLine(HyperGold.copy(alpha = 0.45f), Offset(cx, cy - 14f), Offset(cx, cy + 14f), 1.5f)
                    }
                }

                // Live Focal / Zoom & Exposure Watermark Badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    val zoomFormatted = if (liveZoomRatio < 1f) {
                        "%.1fx".format(liveZoomRatio)
                    } else if (abs(liveZoomRatio - liveZoomRatio.toInt()) < 0.05f) {
                        "${liveZoomRatio.toInt()}x"
                    } else {
                        "%.1fx".format(liveZoomRatio)
                    }
                    Text(
                        text = "$zoomFormatted (${(liveZoomRatio * 24).toInt()}mm) • ${selectedAspect.shortTag} • ${if (isManualExp) "ISO $isoValue  $shutterLabel" else "AUTO AE"}",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // =========================================================================
        // ZONE 3: HYPEROS PRO CAMERA CONTROL DECK
        // =========================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(2f)
                .background(HyperBlack)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(HyperSurface)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    when (activeDial) {
                        ProDialTab.ASPECT -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("SENSOR ASPECT RATIO & FRAME GATE", color = HyperTextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(CinemaAspectRatio.entries) { ratio ->
                                        HyperDialPill(
                                            text = ratio.label,
                                            selected = selectedAspect == ratio
                                        ) {
                                            selectedAspect = ratio
                                            controller.selectedAspectRatio = ratio
                                            controller.applyCurrentSettings()
                                        }
                                    }
                                }
                            }
                        }

                        ProDialTab.LOG -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("COLOR SCIENCE & ALL-INTRA / RAW PIPELINE", color = HyperTextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(ShootingMode.entries) { mode ->
                                        HyperDialPill(
                                            text = mode.label,
                                            selected = selectedMode == mode
                                        ) {
                                            if (!isRecording) {
                                                selectedMode = mode
                                                controller.currentMode = mode
                                                controller.applyCurrentSettings()
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        ProDialTab.ISO -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("SENSOR ISO SENSITIVITY (ALL MODES)", color = HyperTextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    HyperTopChip(text = if (isManualExp) "MANUAL" else "AUTO AE", active = isManualExp) {
                                        isManualExp = !isManualExp
                                        controller.isManualExposure = isManualExp
                                        controller.applyCurrentSettings()
                                    }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)) { iso ->
                                        HyperDialPill(
                                            text = "ISO $iso",
                                            selected = isManualExp && isoValue == iso
                                        ) {
                                            isManualExp = true
                                            isoValue = iso
                                            controller.isManualExposure = true
                                            controller.manualIso = iso
                                            controller.applyCurrentSettings()
                                        }
                                    }
                                }
                            }
                        }

                        ProDialTab.SHUTTER -> {
                            val shutters = listOf(
                                "1/24" to 41_666_666L,
                                "1/48 (180°)" to 20_833_333L,
                                "1/60" to 16_666_666L,
                                "1/120" to 8_333_333L,
                                "1/250" to 4_000_000L,
                                "1/500" to 2_000_000L,
                                "1/1000" to 1_000_000L,
                                "1/2000" to 500_000L
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("SHUTTER SPEED / ANGLE (ALL MODES)", color = HyperTextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    HyperTopChip(text = if (isManualExp) "MANUAL" else "AUTO AE", active = isManualExp) {
                                        isManualExp = !isManualExp
                                        controller.isManualExposure = isManualExp
                                        controller.applyCurrentSettings()
                                    }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(shutters) { (label, ns) ->
                                        HyperDialPill(
                                            text = label,
                                            selected = isManualExp && shutterNs == ns
                                        ) {
                                            isManualExp = true
                                            shutterNs = ns
                                            controller.isManualExposure = true
                                            controller.manualShutterNs = ns
                                            controller.applyCurrentSettings()
                                        }
                                    }
                                }
                            }
                        }

                        ProDialTab.EV -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("EXP EV", color = HyperGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = exposureEv,
                                    onValueChange = {
                                        exposureEv = it
                                        controller.exposureCompEv = it
                                        controller.applyCurrentSettings()
                                    },
                                    valueRange = -2.0f..2.0f,
                                    steps = 7,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = HyperGold, activeTrackColor = HyperGold)
                                )
                                Text(
                                    text = "${if (exposureEv >= 0) "+" else ""}${"%.1f".format(exposureEv)} EV",
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        ProDialTab.WB -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("KELVIN WHITE BALANCE (ALL MODES)", color = HyperTextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(WhiteBalancePreset.entries) { preset ->
                                        HyperDialPill(
                                            text = preset.label,
                                            selected = wbPreset == preset
                                        ) {
                                            wbPreset = preset
                                            controller.wbPreset = preset
                                            controller.applyCurrentSettings()
                                        }
                                    }
                                }
                            }
                        }

                        ProDialTab.FOCUS -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                HyperTopChip(
                                    text = if (isManualFocus) "MF" else "AF-C",
                                    active = isManualFocus
                                ) {
                                    isManualFocus = !isManualFocus
                                    controller.isManualFocus = isManualFocus
                                    controller.applyCurrentSettings()
                                }
                                Text("∞", color = Color.White, fontSize = 15.sp)
                                Slider(
                                    value = focusDiopter,
                                    onValueChange = {
                                        isManualFocus = true
                                        focusDiopter = it
                                        controller.isManualFocus = true
                                        controller.manualFocusDiopter = it
                                        controller.applyCurrentSettings()
                                    },
                                    valueRange = 0f..controller.maxFocusDistance,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = HyperGold, activeTrackColor = HyperGold)
                                )
                                Text("MACRO", color = HyperGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        ProDialTab.NONE -> {}
                    }
                }
            }

            // =========================================================================
            // 3B. 120Hz GPU SHADER ZOOM RING (0.5x | 1x | 2x | 5x | 8x | 10x + Front)
            // =========================================================================
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(HyperSurface)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ZoomStops.forEach { stop ->
                    val isSelected = !isFrontCam && abs(targetZoomStop - stop) < 0.05f
                    val label = if (isSelected && abs(liveZoomRatio - stop) >= 0.08f) {
                        "%.1fx".format(liveZoomRatio)
                    } else if (stop < 1f) {
                        ".5x"
                    } else {
                        "${stop.toInt()}x"
                    }

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
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
                            }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.Black else Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                    }
                }

                if (!isRecording) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isFrontCam) HyperGold else HyperPillBg)
                            .clickable {
                                controller.toggleFrontBackCamera { z ->
                                    isFrontCam = controller.isFrontCamera
                                    targetZoomStop = z
                                    liveZoomRatio = z
                                    gpuShaderScale = 1.0f
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "FRONT",
                            color = if (isFrontCam) Color.Black else HyperTextDim,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // 3C. HyperOS Pro Parameter Strip (LOG | ISO | SHT | EV | WB | FOCUS)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(HyperSurface)
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HyperProParamItem(
                    title = "LOG",
                    value = selectedMode.badge.take(7),
                    active = activeDial == ProDialTab.LOG
                ) { activeDial = if (activeDial == ProDialTab.LOG) ProDialTab.NONE else ProDialTab.LOG }

                HyperProParamItem(
                    title = "ISO",
                    value = if (isManualExp) "$isoValue" else "AUTO",
                    active = activeDial == ProDialTab.ISO
                ) { activeDial = if (activeDial == ProDialTab.ISO) ProDialTab.NONE else ProDialTab.ISO }

                HyperProParamItem(
                    title = "SHT",
                    value = if (isManualExp) shutterLabel else "AUTO",
                    active = activeDial == ProDialTab.SHUTTER
                ) { activeDial = if (activeDial == ProDialTab.SHUTTER) ProDialTab.NONE else ProDialTab.SHUTTER }

                HyperProParamItem(
                    title = "EV",
                    value = "${if (exposureEv >= 0) "+" else ""}${"%.1f".format(exposureEv)}",
                    active = activeDial == ProDialTab.EV
                ) { activeDial = if (activeDial == ProDialTab.EV) ProDialTab.NONE else ProDialTab.EV }

                HyperProParamItem(
                    title = "WB",
                    value = wbPreset.label,
                    active = activeDial == ProDialTab.WB
                ) { activeDial = if (activeDial == ProDialTab.WB) ProDialTab.NONE else ProDialTab.WB }

                HyperProParamItem(
                    title = "FOCUS",
                    value = if (isManualFocus) "MF" else "AF-C",
                    active = activeDial == ProDialTab.FOCUS
                ) { activeDial = if (activeDial == ProDialTab.FOCUS) ProDialTab.NONE else ProDialTab.FOCUS }
            }

            // 3D. Bottom Shutter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val nextIdx = (selectedAspect.ordinal + 1) % CinemaAspectRatio.entries.size
                            val nextRatio = CinemaAspectRatio.entries[nextIdx]
                            selectedAspect = nextRatio
                            controller.selectedAspectRatio = nextRatio
                            controller.applyCurrentSettings()
                        }
                        .padding(8.dp)
                ) {
                    Text("GATE", color = HyperTextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = selectedAspect.shortTag,
                        color = HyperGold,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(3.5.dp, Color.White, CircleShape)
                        .padding(7.dp)
                        .clip(if (isRecording) RoundedCornerShape(16.dp) else CircleShape)
                        .background(HyperRed)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            controller.toggleRecording(context) { rec ->
                                isRecording = rec
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White)
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            isManualExp = !isManualExp
                            controller.isManualExposure = isManualExp
                            controller.applyCurrentSettings()
                        }
                        .padding(8.dp)
                ) {
                    Text("EXP", color = HyperTextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (isManualExp) "MAN" else "AUTO",
                        color = if (isManualExp) HyperGold else Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingTimecodePill() {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            seconds++
        }
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(HyperRed.copy(alpha = 0.2f))
            .border(1.dp, HyperRed, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(HyperRed))
        val mins = (seconds / 60).toString().padStart(2, '0')
        val secs = (seconds % 60).toString().padStart(2, '0')
        Text(
            text = "00:$mins:$secs",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

@Composable
fun LiveHistogramScopeWidget(isRawMode: Boolean, modifier: Modifier = Modifier) {
    var bins by remember { mutableStateOf(FloatArray(64)) }
    LaunchedEffect(isRawMode) {
        if (isRawMode) {
            while (true) {
                delay(250L)
                bins = NativeVulkanEngine.getLiveHistogram()
            }
        }
    }
    HistogramScopeView(bins = bins, modifier = modifier)
}

@Composable
private fun HyperProParamItem(
    title: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) HyperGold.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = title,
            color = if (active) HyperGold else HyperTextDim,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            color = if (active) HyperGold else Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun HyperTopChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) HyperGold else HyperPillBg)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun HyperDialPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) HyperGold else HyperPillBg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
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
        selectedFile?.let { file ->
            totalFrames = NativeVulkanEngine.getRaw16FrameCount(file.absolutePath).coerceAtLeast(1)
            currentFrame = 0
            exportStatusText = null
        }
    }

    LaunchedEffect(selectedFile, currentFrame, postTransferMode, postAssist, postExposureEv, playerSurface) {
        val file = selectedFile ?: return@LaunchedEffect
        val surf = playerSurface ?: return@LaunchedEffect
        if (!isExporting) {
            withContext(Dispatchers.IO) {
                NativeVulkanEngine.renderRaw16FrameFromFile(
                    file.absolutePath,
                    currentFrame,
                    postTransferMode,
                    postAssist,
                    postExposureEv,
                    surf
                )
                histogramBins = NativeVulkanEngine.getLiveHistogram()
            }
        }
    }

    LaunchedEffect(isPlaying, totalFrames) {
        while (isPlaying && totalFrames > 0 && !isExporting) {
            delay(41L)
            currentFrame = (currentFrame + 1) % totalFrames
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HyperBlack)
            .statusBarsPadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(HyperSurface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HyperTopChip("← CAMERA", false) {
                    if (!isExporting) onBackToCamera()
                }
                Text(
                    "16-BIT RAW LAB & EXPORTER",
                    color = HyperGold,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp
                )
                HistogramScopeView(bins = histogramBins, modifier = Modifier.width(80.dp).height(24.dp))
            }

            if (rawFiles.isEmpty()) {
                Text(
                    "No .raw16 files found in Movies/PixelCinemaLog yet. Record in 'Lossless RAW16' mode first!",
                    color = HyperTextDim,
                    fontSize = 12.sp
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rawFiles) { file ->
                        val sizeMb = file.length() / (1024 * 1024)
                        HyperDialPill("${file.name.takeLast(16)} (${sizeMb}MB)", selectedFile == file) {
                            if (!isExporting) {
                                isPlaying = false
                                selectedFile = file
                            }
                        }
                    }
                }
            }

            exportStatusText?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1B5E20))
                        .padding(8.dp)
                ) {
                    Text(
                        text = msg,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            val ratio = 3f / 4f
            val fitH = maxWidth / ratio
            val wDp = if (fitH <= maxHeight) maxWidth else maxHeight * ratio
            val hDp = if (fitH <= maxHeight) fitH else maxHeight

            Box(
                modifier = Modifier
                    .size(wDp, hDp)
                    .border(1.dp, Color.White.copy(alpha = 0.15f))
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    playerSurface = holder.surface
                                }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                                override fun surfaceDestroyed(h: SurfaceHolder) {
                                    playerSurface = null
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (selectedFile != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(HyperSurface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HyperTopChip(if (isPlaying) "PAUSE" else "PLAY", isPlaying) {
                        if (!isExporting) isPlaying = !isPlaying
                    }
                    Slider(
                        value = currentFrame.toFloat(),
                        onValueChange = {
                            if (!isExporting) {
                                isPlaying = false
                                currentFrame = it.toInt()
                            }
                        },
                        valueRange = 0f..(totalFrames - 1).coerceAtLeast(1).toFloat(),
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = HyperGold, activeTrackColor = HyperGold)
                    )
                    Text(
                        "F ${currentFrame + 1}/$totalFrames",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("RAW EV:", color = HyperGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Slider(
                        value = postExposureEv,
                        onValueChange = { if (!isExporting) postExposureEv = it },
                        valueRange = -3.0f..3.0f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = HyperGold, activeTrackColor = HyperGold)
                    )
                    Text(
                        "${"%.1f".format(postExposureEv)} EV",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val profiles = listOf(
                        "S-Log3" to 2,
                        "ARRI LogC4" to 3,
                        "C-Log3" to 5,
                        "Apple Log" to 6,
                        "Rec.709" to 0
                    )
                    profiles.forEach { (name, modeId) ->
                        HyperDialPill(name, postTransferMode == modeId) {
                            if (!isExporting) postTransferMode = modeId
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val file = selectedFile ?: return@Button
                            if (isExporting) return@Button
                            isPlaying = false
                            isExporting = true
                            val folderName = "DNG_${file.nameWithoutExtension}"
                            val outDir = File(moviesDir, folderName)
                            exportStatusText = "Exporting 16-Bit CinemaDNG sequence..."

                            scope.launch {
                                val count = withContext(Dispatchers.IO) {
                                    NativeVulkanEngine.exportRaw16ToCinemaDng(
                                        file.absolutePath,
                                        outDir.absolutePath
                                    )
                                }
                                isExporting = false
                                exportStatusText = "Exported $count CinemaDNG frames -> $folderName"
                            }
                        },
                        enabled = !isExporting,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = HyperGold),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isExporting) "EXPORTING..." else "EXPORT CINEMADNG (.DNG)",
                            color = Color.Black,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                    }

                    Button(
                        onClick = {
                            val file = selectedFile ?: return@Button
                            if (isExporting) return@Button
                            isPlaying = false
                            isExporting = true
                            val outMp4 = File(
                                moviesDir,
                                "${file.nameWithoutExtension}_GRADED_${System.currentTimeMillis()}.mp4"
                            )
                            exportStatusText = "Rendering Graded HEVC .MP4..."

                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    try {
                                        val recorder = MediaRecorder(context).apply {
                                            setVideoSource(MediaRecorder.VideoSource.SURFACE)
                                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                            setOutputFile(outMp4.absolutePath)
                                            setVideoEncodingBitRate(120_000_000)
                                            setVideoFrameRate(24)
                                            setVideoSize(1920, 1080)
                                            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                                            prepare()
                                            start()
                                        }
                                        val recSurface = recorder.surface
                                        for (fIdx in 0 until totalFrames) {
                                            NativeVulkanEngine.renderRaw16FrameFromFile(
                                                file.absolutePath,
                                                fIdx,
                                                postTransferMode,
                                                if (postAssist == 1) 1 else 0,
                                                postExposureEv,
                                                recSurface
                                            )
                                            delay(41L)
                                        }
                                        recorder.stop()
                                        recorder.release()
                                        true
                                    } catch (e: Exception) {
                                        false
                                    }
                                }
                                isExporting = false
                                exportStatusText = if (ok) "Saved -> ${outMp4.name}" else "Export failed"
                            }
                        },
                        enabled = !isExporting,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = HyperRed),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "EXPORT GRADED .MP4",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistogramScopeView(bins: FloatArray, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(HyperPillBg)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(2.dp)
    ) {
        val w = size.width
        val h = size.height
        val barWidth = w / 64f
        for (i in 0 until 64) {
            val v = bins.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val barH = v * h
            val barColor = when {
                i >= 60 -> HyperRed
                i in 24..28 -> Color(0xFF00E676)
                else -> HyperGold
            }
            drawRect(
                color = barColor,
                topLeft = Offset(i * barWidth, h - barH),
                size = Size(barWidth.coerceAtLeast(1f), barH)
            )
        }
    }
}