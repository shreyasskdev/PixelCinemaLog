// File: PixelCameraController.kt
// Path: app/src/main/java/io/github/shreyasskdev/pixelcinemalog/PixelCameraController.kt
package io.github.shreyasskdev.pixelcinemalog

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.*
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

import io.github.shreyasskdev.pixelcinemalog.models.*

import io.github.shreyasskdev.pixelcinemalog.camera.NativeVulkanEngine
import io.github.shreyasskdev.pixelcinemalog.camera.CinemaAllIntraRecorder

class PixelCameraController(private val context: Context) {
    private val cameraThread = HandlerThread("PixelCinemaCameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var captureSession: CameraCaptureSession? = null
    @Volatile private var previewSurface: Surface? = null
    private var activeRawInputSurface: Surface? = null
    private var isSessionInRawSensorMode: Boolean = false
    private lateinit var characteristics: CameraCharacteristics

    private var activeRequestBuilder: CaptureRequest.Builder? = null

    // ── Concurrent (dual physical) camera state ──
    @Volatile var isConcurrentModeEnabled: Boolean = false
        private set
    private var dualCamera: DualCamera? = null
    private var secondarySurface: Surface? = null
    /** Telephoto native FOV relative to wide (Pixel 9 Pro XL = 5×). */
    private val teleNativeZoom: Float = 5.0f

    private var persistentRecordSurface: Surface? = null
    private var standbyVideoCodec: MediaCodec? = null
    private var configuredVideoSize: Size = Size(3840, 2160)
    private var configuredHlg10: Boolean = false

    private var cachedTonemapCurve: TonemapCurve? = null
    private var cachedCurveModeId: Int = -1
    private var cachedCurveEv: Float = -999f

    @Volatile var isRecording = false
        private set
    private val isBusyToggling = AtomicBoolean(false)
    private var allIntraRecorder: CinemaAllIntraRecorder? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentVideoUri: Uri? = null

    private var backLogicalCameraId: String = "0"
    private var frontCameraId: String = "1"
    var isFrontCamera: Boolean = false
        private set

    @Volatile var currentZoomRatio: Float = 1.0f
        private set
    var minZoomRatio: Float = 0.5f
        private set
    var maxZoomRatio: Float = 10.0f
        private set

    @Volatile private var lastSubmittedZoom: Float = 1.0f
    @Volatile private var zoomSubmitterActive: Boolean = false
    private var uiPollerActive: Boolean = false
    private var lastUiZoom: Float = 1.0f
    private var uiZoomCallback: ((Float) -> Unit)? = null

    private var onSessionRebuilding: ((Boolean) -> Unit)? = null
    private var onLensBoundaryCross: ((Float) -> Unit)? = null
    private var lensBoundaries: FloatArray = floatArrayOf(0.95f, 4.9f)
    private var lastZoomForBoundary: Float = 1.0f
    private var lastCrossedBoundary: Float = -1f

    var currentCameraId: String = "0"
    var currentMode: ShootingMode = ShootingMode.SONY_SLOG3_10BIT
    var currentAssist: ViewfinderAssist = ViewfinderAssist.OFF
    var selectedAspectRatio: CinemaAspectRatio = CinemaAspectRatio.OPEN_GATE_4_3

    var isManualExposure: Boolean = false
    var manualIso: Int = 800
    var manualShutterNs: Long = 20_833_333L
    var exposureCompEv: Float = 0.0f

    var selectedFps: Int = 24
    var is4KResolution: Boolean = true
    var wbPreset: WhiteBalancePreset = WhiteBalancePreset.AUTO

    var isManualFocus: Boolean = false
    var manualFocusDiopter: Float = 0.0f
    var maxFocusDistance: Float = 10.0f
        private set

    private var _zoomEasing: ZoomEasing = ZoomEasing.EASE_IN_OUT_CUBIC
    val zoomEasing: ZoomEasing get() = _zoomEasing

    private var cachedCustomBezier: FloatArray = floatArrayOf(0.25f, 0.10f, 0.25f, 1.0f)
    private var _zoomDurationFixed: Float = 0f
    private var _zoomDurationScale: Float = 1f
    val zoomDurationFixed: Float get() = _zoomDurationFixed
    val zoomDurationScale: Float get() = _zoomDurationScale

    init {
        NativeVulkanEngine.initEngine()
        NativeVulkanEngine.setZoomEasing(_zoomEasing.rustId)
        discoverLogicalCameras()
    }

    fun setZoomEasing(easing: ZoomEasing) {
        if (easing == ZoomEasing.CUSTOM_BEZIER) {
            NativeVulkanEngine.setCustomBezierEasing(
                cachedCustomBezier[0], cachedCustomBezier[1],
                cachedCustomBezier[2], cachedCustomBezier[3]
            )
            _zoomEasing = ZoomEasing.CUSTOM_BEZIER
            return
        }
        NativeVulkanEngine.setZoomEasing(easing.rustId)
        _zoomEasing = easing
    }

    fun setCustomBezier(x1: Float, y1: Float, x2: Float, y2: Float) {
        val cx1 = x1.coerceIn(0f, 1f); val cy1 = y1.coerceIn(0f, 1f)
        val cx2 = x2.coerceIn(0f, 1f); val cy2 = y2.coerceIn(0f, 1f)
        NativeVulkanEngine.setCustomBezierEasing(cx1, cy1, cx2, cy2)
        cachedCustomBezier = floatArrayOf(cx1, cy1, cx2, cy2)
        _zoomEasing = ZoomEasing.CUSTOM_BEZIER
    }

    fun setZoomDurationFixed(seconds: Float) {
        val clamped = if (seconds > 0f) seconds.coerceIn(0.10f, 5f) else 0f
        NativeVulkanEngine.setZoomDurationFixed(clamped)
        _zoomDurationFixed = clamped
    }

    fun setZoomDurationScale(scale: Float) {
        val clamped = scale.coerceIn(0.10f, 5f)
        NativeVulkanEngine.setZoomDurationScale(clamped)
        _zoomDurationScale = clamped
    }

    fun getCustomBezier(): FloatArray = cachedCustomBezier.copyOf()
    fun setSessionRebuildingListener(cb: (Boolean) -> Unit) { onSessionRebuilding = cb }
    fun setLensBoundaryCrossListener(cb: (Float) -> Unit) { onLensBoundaryCross = cb }
    fun setLensBoundaries(bounds: FloatArray) { lensBoundaries = bounds.clone() }

    private fun discoverLogicalCameras() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var bestBackId = "0"
        var bestZoomSpan = 1.0f
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (facing == CameraCharacteristics.LENS_FACING_BACK && zoomRange != null) {
                val span = zoomRange.upper / zoomRange.lower.coerceAtLeast(0.1f)
                if (span > bestZoomSpan) { bestZoomSpan = span; bestBackId = id }
            } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                frontCameraId = id
            }
        }
        backLogicalCameraId = bestBackId
        currentCameraId = backLogicalCameraId
    }

    fun animateZoomTo(targetZoom: Float, onZoomUpdated: (Float) -> Unit) {
        val clampedTarget = targetZoom.coerceIn(minZoomRatio, maxZoomRatio)
        NativeVulkanEngine.setZoomTarget(clampedTarget)
        uiZoomCallback = onZoomUpdated
        startUiPoller()
        startZoomSubmitter()
    }

    private fun startUiPoller() {
        if (uiPollerActive) return
        uiPollerActive = true
        val poller = object : Runnable {
            override fun run() {
                if (!uiPollerActive) return
                val z = NativeVulkanEngine.getCurrentZoom()
                if (abs(z - lastUiZoom) > 0.0008f) {
                    lastUiZoom = z
                    uiZoomCallback?.invoke(z)
                }
                if (NativeVulkanEngine.isZoomAnimating()) {
                    mainHandler.postDelayed(this, 33L)
                } else {
                    val finalZ = NativeVulkanEngine.getCurrentZoom()
                    if (abs(finalZ - lastUiZoom) > 0.0008f) {
                        lastUiZoom = finalZ
                        uiZoomCallback?.invoke(finalZ)
                    }
                    uiPollerActive = false
                }
            }
        }
        mainHandler.post(poller)
    }

    private fun startZoomSubmitter() {
        if (zoomSubmitterActive) return
        zoomSubmitterActive = true
        cameraHandler.post(zoomSubmitter)
    }

    private fun checkLensBoundaryCrossing(newZoom: Float) {
        if (lensBoundaries.isEmpty()) { lastZoomForBoundary = newZoom; return }
        val lo = minOf(lastZoomForBoundary, newZoom)
        val hi = maxOf(lastZoomForBoundary, newZoom)
        for (boundary in lensBoundaries) {
            if (lo < boundary && hi > boundary && abs(lastCrossedBoundary - boundary) > 0.05f) {
                lastCrossedBoundary = boundary
                mainHandler.post { onLensBoundaryCross?.invoke(boundary) }
                break
            }
        }
        if (lensBoundaries.all { abs(newZoom - it) > 0.15f }) lastCrossedBoundary = -1f
        lastZoomForBoundary = newZoom
    }

    private val zoomSubmitter = object : Runnable {
        override fun run() {
            if (!zoomSubmitterActive) return

            val z = NativeVulkanEngine.getCurrentZoom().coerceIn(minZoomRatio, maxZoomRatio)
            currentZoomRatio = z
            checkLensBoundaryCrossing(z)

            val session = captureSession
            val builder = activeRequestBuilder
            if (session != null && builder != null) {
                val changed = abs(z - lastSubmittedZoom) > 0.0005f
                if (changed) {
                    try {
                        val dc = dualCamera
                        if (isConcurrentModeEnabled && dc != null && !isSessionInRawSensorMode) {
                            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
                            builder.setPhysicalCameraKey(CaptureRequest.CONTROL_ZOOM_RATIO, z, dc.wideId)
                            val teleZoom = (z / teleNativeZoom).coerceIn(1.0f, 4.0f)
                            builder.setPhysicalCameraKey(CaptureRequest.CONTROL_ZOOM_RATIO, teleZoom, dc.teleId)
                        } else if (!isSessionInRawSensorMode) {
                            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
                        }
                        session.setRepeatingRequest(builder.build(), frameSyncCallback, cameraHandler)
                        lastSubmittedZoom = z
                    } catch (_: Exception) {}
                }
            }

            val animating = NativeVulkanEngine.isZoomAnimating()
            if (animating) {
                val interval = (1000L / selectedFps.coerceIn(24, 60)).coerceIn(16L, 42L)
                cameraHandler.postDelayed(this, interval)
            } else {
                zoomSubmitterActive = false
            }
        }
    }

    fun toggleFrontBackCamera(onZoomReset: (Float) -> Unit) {
        val surface = previewSurface ?: return
        if (isRecording) return
        zoomSubmitterActive = false
        uiPollerActive = false

        isFrontCamera = !isFrontCamera
        val targetId = if (isFrontCamera) frontCameraId else backLogicalCameraId

        NativeVulkanEngine.resetZoomTo(1.0f)
        currentZoomRatio = 1.0f
        lastSubmittedZoom = 1.0f
        lastUiZoom = 1.0f
        lastZoomForBoundary = 1.0f
        lastCrossedBoundary = -1f
        mainHandler.post { onZoomReset(1.0f) }
        onSessionRebuilding?.invoke(true)
        startCamera(surface, secondarySurface, targetId)
    }

    private fun preparePersistentVideoSurface(size: Size, use10Bit: Boolean): Surface {
        if (persistentRecordSurface != null && configuredVideoSize == size && configuredHlg10 == use10Bit) {
            return persistentRecordSurface!!
        }
        try { standbyVideoCodec?.release() } catch (_: Exception) {}
        standbyVideoCodec = null
        try { persistentRecordSurface?.release() } catch (_: Exception) {}

        val surface = MediaCodec.createPersistentInputSurface()
        bindStandbyCodecToPersistentSurface(surface, size, use10Bit)
        persistentRecordSurface = surface
        configuredVideoSize = size
        configuredHlg10 = use10Bit
        return surface
    }

    private fun bindStandbyCodecToPersistentSurface(surface: Surface, size: Size, use10Bit: Boolean) {
        try { standbyVideoCodec?.release() } catch (_: Exception) {}
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val format = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 120_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, selectedFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (use10Bit) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
        }
        standbyVideoCodec = MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            setInputSurface(surface)
        }
    }

    @SuppressLint("MissingPermission")
    fun startCamera(
        viewfinderSurface: Surface,
        secondaryViewfinderSurface: Surface? = null,
        cameraId: String = currentCameraId
    ) {
        this.previewSurface = viewfinderSurface
        this.secondarySurface = secondaryViewfinderSurface
        this.currentCameraId = cameraId

        isConcurrentModeEnabled = false
        dualCamera = null
        zoomSubmitterActive = false

        cameraHandler.post {
            try { captureSession?.close() } catch (_: Exception) {}
            captureSession = null
            try { cameraDevice?.close() } catch (_: Exception) {}
            cameraDevice = null

            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                characteristics = manager.getCameraCharacteristics(cameraId)
                maxFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f

                val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                minZoomRatio = (zoomRange?.lower ?: 0.5f).coerceAtMost(1.0f)
                maxZoomRatio = (zoomRange?.upper ?: 10.0f).coerceAtLeast(10.0f)
                currentZoomRatio = currentZoomRatio.coerceIn(minZoomRatio, maxZoomRatio)
                lastSubmittedZoom = currentZoomRatio

                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val canTryDual = secondaryViewfinderSurface != null &&
                        facing == CameraCharacteristics.LENS_FACING_BACK
                if (canTryDual) {
                    dualCamera = findWideTelePair(manager, cameraId)
                    Log.i("PixelCameraController", "Dual cam candidate: $dualCamera")
                }
                val dualCamForOpen = dualCamera                 // ← capture field to a local val
                val secondaryForOpen = secondaryViewfinderSurface  // ← ditto (param, but explicit)

                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        if (dualCamForOpen != null && secondaryForOpen != null) {
                            createConcurrentCaptureSession(device, viewfinderSurface, secondaryForOpen, dualCamForOpen)
                        } else {
                            createCaptureSession(device, viewfinderSurface)
                        }
                    }
                    override fun onDisconnected(device: CameraDevice) {
                        if (cameraDevice === device) { cameraDevice = null; captureSession = null }
                        try { device.close() } catch (_: Exception) {}
                    }
                    override fun onError(device: CameraDevice, error: Int) {
                        Log.e("PixelCameraController", "Camera error: $error")
                        if (cameraDevice === device) { cameraDevice = null; captureSession = null }
                        try { device.close() } catch (_: Exception) {}
                    }
                }, cameraHandler)
            } catch (e: Exception) {
                Log.e("PixelCameraController", "Failed to open camera", e)
            }
        }
    }

    // ─── Dual camera discovery ────────────────────────────────────────────────
    /**
     * Finds the wide + tele pair for a given logical camera. On Pixel 9 Pro XL
     * that's physical IDs "3" (wide) and "4" (tele), but the fallback heuristic
     * works on any device with two back physical cameras.
     */
    private fun findWideTelePair(manager: CameraManager, logicalId: String): DualCamera? {
        return try {
            val logicalChars = manager.getCameraCharacteristics(logicalId)
            val physicalIds = logicalChars.physicalCameraIds.toList()
            if (physicalIds.size < 2) return null

            // Focal lengths to find the widest and longest.
            data class PhysInfo(val id: String, val minFocal: Float, val maxFocal: Float)
            val infos = physicalIds.mapNotNull { pid ->
                try {
                    val c = manager.getCameraCharacteristics(pid)
                    val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    if (focals == null || focals.isEmpty()) null
                    else PhysInfo(pid, focals.min(), focals.max())
                } catch (_: Exception) { null }
            }
            if (infos.size < 2) return null

            val wide = infos.minByOrNull { it.minFocal } ?: return null
            val tele = infos.maxByOrNull { it.maxFocal } ?: return null
            if (wide.id == tele.id) return null

            // Sanity check: tele must be noticeably tighter than wide
            if (tele.minFocal / wide.minFocal < 1.8f) return null

            DualCamera(logicalId, wide.id, tele.id)
        } catch (e: Exception) {
            Log.w("PixelCameraController", "findWideTelePair failed", e)
            null
        }
    }

    // ─── Concurrent session (logical + 2 physical) ────────────────────────────
    private fun createConcurrentCaptureSession(
        device: CameraDevice,
        primarySurface: Surface,
        secondarySurface: Surface,
        dualCam: DualCamera
    ) {
        if (cameraDevice !== device) return
        try {
            // Logical output on primary surface — this is what the primary
            // TextureView shows when we're below the crossfade region
            val logicalConfig = OutputConfiguration(primarySurface)

            // Physical wide output on primary surface — will overlay the logical
            // stream once we start per-physical zooming (they render the same
            // scene below the boundary anyway)
            val wideConfig = OutputConfiguration(primarySurface).apply {
                setPhysicalCameraId(dualCam.wideId)
            }

            // Physical tele output on secondary surface — hidden until crossfade
            val teleConfig = OutputConfiguration(secondarySurface).apply {
                setPhysicalCameraId(dualCam.teleId)
            }

            val configs = listOf(logicalConfig, wideConfig, teleConfig)
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                configs,
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice !== device) { session.close(); return }
                        captureSession = session
                        isConcurrentModeEnabled = true
                        isSessionInRawSensorMode = false
                        applyCurrentSettingsInternal()
                        mainHandler.post { onSessionRebuilding?.invoke(false) }
                        Log.i("PixelCameraController", "Concurrent session ready (${dualCam.wideId}/${dualCam.teleId})")
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.w("PixelCameraController", "Concurrent session failed; falling back to single")
                        isConcurrentModeEnabled = false
                        dualCamera = null
                        // Fall back — the surface callbacks will re-enter via createCaptureSession
                        createCaptureSession(device, primarySurface)
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e("PixelCameraController", "createConcurrentCaptureSession threw", e)
            isConcurrentModeEnabled = false
            dualCamera = null
            createCaptureSession(device, primarySurface)
        }
    }

    private fun isRawMode(mode: ShootingMode): Boolean {
        return mode == ShootingMode.RAW_SENSOR_SLOG3 || mode == ShootingMode.UNCOMPRESSED_RAW16
    }

    private fun pickSupportedVideoSize(want4K: Boolean): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supported = map?.getOutputSizes(MediaCodec::class.java) ?: emptyArray()
        val targetW = if (want4K) 3840 else 1920
        val targetH = if (want4K) 2160 else 1080
        return supported.firstOrNull { it.width == targetW && it.height == targetH } ?: Size(1920, 1080)
    }

    private fun createCaptureSession(device: CameraDevice, viewfinderSurface: Surface) {
        if (cameraDevice !== device) return
        try {
            isConcurrentModeEnabled = false
            val wantRawSensor = isRawMode(currentMode)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)

            if (wantRawSensor && !rawSizes.isNullOrEmpty()) {
                isSessionInRawSensorMode = true
                val rawSize = rawSizes.maxByOrNull { it.width * it.height } ?: rawSizes.first()
                NativeVulkanEngine.setRawOutputSurfaces(viewfinderSurface, null)
                val rawSurface = activeRawInputSurface
                    ?: NativeVulkanEngine.createRawSensorSurface(rawSize.width, rawSize.height).also {
                        activeRawInputSurface = it
                    }
                val rawOutputConfig = OutputConfiguration(rawSurface)
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(rawOutputConfig),
                    cameraExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice === device) {
                                captureSession = session
                                applyCurrentSettingsInternal()
                                mainHandler.post { onSessionRebuilding?.invoke(false) }
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e("PixelCameraController", "RAW_SENSOR session configuration failed!")
                            mainHandler.post { onSessionRebuilding?.invoke(false) }
                        }
                    }
                )
                device.createCaptureSession(sessionConfig)
                return
            }

            isSessionInRawSensorMode = false
            NativeVulkanEngine.setRawOutputSurfaces(null, null)

            val dynamicProfiles = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
            )
            val supportsHlg10 = dynamicProfiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
            val useHardwareHlg10 = supportsHlg10 && currentMode == ShootingMode.ZERO_POST_HLG10

            val videoSize = pickSupportedVideoSize(is4KResolution)
            val recordSurf = preparePersistentVideoSurface(videoSize, useHardwareHlg10)

            val previewConfig = OutputConfiguration(viewfinderSurface)
            val recordConfig = OutputConfiguration(recordSurf).apply {
                if (useHardwareHlg10) dynamicRangeProfile = DynamicRangeProfiles.HLG10
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(previewConfig, recordConfig),
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice === device) {
                            captureSession = session
                            applyCurrentSettingsInternal()
                            mainHandler.post { onSessionRebuilding?.invoke(false) }
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("PixelCameraController", "Camera session configuration failed!")
                        mainHandler.post { onSessionRebuilding?.invoke(false) }
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e("PixelCameraController", "Error in createCaptureSession", e)
            mainHandler.post { onSessionRebuilding?.invoke(false) }
        }
    }

    fun toggleRecording(context: Context, onStateChange: (Boolean) -> Unit) {
        if (!isBusyToggling.compareAndSet(false, true)) return
        val surface = previewSurface
        val device = cameraDevice
        if (surface == null || device == null) { isBusyToggling.set(false); return }

        if (isRecording) {
            isRecording = false
            mainHandler.post { onStateChange(false) }
            NativeVulkanEngine.setRawDumpFd(-1)

            val recorderToStop = allIntraRecorder
            allIntraRecorder = null
            val pfdToClose = currentPfd
            currentPfd = null
            val uriToFinalize = currentVideoUri
            currentVideoUri = null

            if (currentMode == ShootingMode.RAW_SENSOR_SLOG3) {
                NativeVulkanEngine.setRawOutputSurfaces(surface, null)
            } else if (currentMode != ShootingMode.UNCOMPRESSED_RAW16) {
                cameraHandler.post { applyCurrentSettingsInternal() }
            }

            ioExecutor.execute {
                try { Thread.sleep(60); recorderToStop?.stopAndRelease() } catch (_: Exception) {}
                val pSurf = persistentRecordSurface
                if (pSurf != null && !isRawMode(currentMode)) {
                    try { bindStandbyCodecToPersistentSurface(pSurf, configuredVideoSize, configuredHlg10) } catch (_: Exception) {}
                }
                try { pfdToClose?.close() } catch (_: Exception) {}
                uriToFinalize?.let { uri ->
                    try {
                        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        context.contentResolver.update(uri, values, null, null)
                    } catch (_: Exception) {}
                }
                isBusyToggling.set(false)
            }
        } else {
            val modeTag = currentMode.badge.replace(" ", "_")

            if (currentMode == ShootingMode.UNCOMPRESSED_RAW16) {
                isRecording = true
                mainHandler.post { onStateChange(true) }
                ioExecutor.execute {
                    try {
                        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "PixelCinemaLog")
                        if (!moviesDir.exists()) moviesDir.mkdirs()
                        val rawFile = File(moviesDir, "Pixel_${modeTag}_${System.currentTimeMillis()}.raw16")
                        val pfd = ParcelFileDescriptor.open(rawFile,
                            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
                        currentPfd = pfd
                        NativeVulkanEngine.setRawDumpFd(pfd.fd)
                    } catch (e: Exception) {
                        Log.e("PixelCameraController", "Failed to open RAW16 file", e)
                        isRecording = false
                        mainHandler.post { onStateChange(false) }
                    } finally { isBusyToggling.set(false) }
                }
                return
            }

            ioExecutor.execute {
                try {
                    val filename = "Pixel_${modeTag}_${selectedFps}fps_${System.currentTimeMillis()}.mp4"
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PixelCinemaLog")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    currentVideoUri = uri
                    val pfd = uri?.let { resolver.openFileDescriptor(it, "rw") }
                    currentPfd = pfd

                    if (pfd != null) {
                        val isRawSlog = currentMode == ShootingMode.RAW_SENSOR_SLOG3
                        val videoSize = if (isRawSlog) Size(1920, 1080) else configuredVideoSize
                        val fps = if (isRawSlog) selectedFps.coerceAtMost(30) else selectedFps
                        val use10Bit = !isRawSlog && configuredHlg10

                        if (!isRawSlog) {
                            try { standbyVideoCodec?.release() } catch (_: Exception) {}
                            standbyVideoCodec = null
                        }

                        val recorder = CinemaAllIntraRecorder(
                            fd = pfd.fileDescriptor,
                            width = videoSize.width, height = videoSize.height,
                            fps = fps, use10BitMain10 = use10Bit,
                            persistentSurface = if (isRawSlog) null else persistentRecordSurface
                        )
                        recorder.prepareAndStart()
                        allIntraRecorder = recorder
                        isRecording = true
                        mainHandler.post { onStateChange(true) }

                        if (isRawSlog) {
                            NativeVulkanEngine.setRawOutputSurfaces(surface, recorder.inputSurface)
                        } else {
                            cameraHandler.post { applyCurrentSettingsInternal() }
                        }
                    } else {
                        isRecording = false
                        mainHandler.post { onStateChange(false) }
                    }
                } catch (e: Exception) {
                    Log.e("PixelCameraController", "Failed to start recording", e)
                    isRecording = false
                    try { currentPfd?.close() } catch (_: Exception) {}
                    currentPfd = null
                    mainHandler.post { onStateChange(false) }
                } finally { isBusyToggling.set(false) }
            }
        }
    }

    fun applyCurrentSettings() { cameraHandler.post { applyCurrentSettingsInternal() } }

    private fun applyCurrentSettingsInternal() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return

        try {
            val wantRawSensor = isRawMode(currentMode)
            val wantHlg10 = currentMode == ShootingMode.ZERO_POST_HLG10
            val desiredSize = pickSupportedVideoSize(is4KResolution)

            // Mode rebuild — don't tear down concurrent session unless RAW or HLG changed
            if (wantRawSensor != isSessionInRawSensorMode ||
                (!wantRawSensor && !isRecording && !isConcurrentModeEnabled &&
                        (wantHlg10 != configuredHlg10 || desiredSize != configuredVideoSize))
            ) {
                NativeVulkanEngine.snapshotZoom()
                zoomSubmitterActive = false
                onSessionRebuilding?.invoke(true)
                createCaptureSession(device, surface)
                return
            }

            NativeVulkanEngine.setCameraMode(currentMode.rustId, currentAssist.id)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

            val secSurf = secondarySurface
            if (isConcurrentModeEnabled && secSurf != null) {
                builder.addTarget(surface)
                builder.addTarget(secSurf)                   // ✅ local val — safe to smart-cast
            } else if (isSessionInRawSensorMode) {
                val rawTarget = activeRawInputSurface ?: return
                builder.addTarget(rawTarget)
            } else {
                builder.addTarget(surface)
                val recSurf = persistentRecordSurface
                if (isRecording && recSurf != null && recSurf.isValid) builder.addTarget(recSurf)
            }

            val targetFps = if (isSessionInRawSensorMode) selectedFps.coerceAtMost(30) else selectedFps
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
            builder.set<Int>(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            val servoZoom = NativeVulkanEngine.getCurrentZoom().coerceIn(minZoomRatio, maxZoomRatio)
            currentZoomRatio = servoZoom
            lastSubmittedZoom = servoZoom
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, servoZoom)

            if (currentMode == ShootingMode.NORMAL_PIXEL_ISP) {
                builder.set<Int>(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                builder.set<Int>(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                builder.set<Int>(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
                builder.set<Int>(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            } else {
                builder.set<Int>(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                val nrModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
                when {
                    nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) ->
                        builder.set<Int>(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                    nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST) ->
                        builder.set<Int>(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF) ->
                        builder.set<Int>(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                }
                builder.set<Int>(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                builder.set<Int>(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
                builder.set<Int>(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
                builder.set<Int>(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                builder.set<Int>(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

                if (currentMode.rustId in 2..6) {
                    if (cachedTonemapCurve == null || cachedCurveModeId != currentMode.rustId || cachedCurveEv != exposureCompEv) {
                        val rustLogCurve = NativeVulkanEngine.generateLogCurve(currentMode.rustId, exposureCompEv)
                        cachedTonemapCurve = TonemapCurve(rustLogCurve, rustLogCurve, rustLogCurve)
                        cachedCurveModeId = currentMode.rustId
                        cachedCurveEv = exposureCompEv
                    }
                    builder.set<Int>(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                    builder.set<TonemapCurve>(CaptureRequest.TONEMAP_CURVE, cachedTonemapCurve)
                } else {
                    builder.set<Int>(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
                }
            }

            if (isManualExposure) {
                val frameDurationNs = 1_000_000_000L / targetFps.toLong()
                val safeShutterNs = manualShutterNs.coerceAtMost(frameDurationNs)
                builder.set<Int>(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                builder.set<Int>(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                builder.set<Long>(CaptureRequest.SENSOR_EXPOSURE_TIME, safeShutterNs)
                builder.set<Long>(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
            } else {
                builder.set<Int>(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                val aeStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.33f
                builder.set<Int>(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (exposureCompEv / aeStep).toInt())
            }

            if (wbPreset == WhiteBalancePreset.AUTO) {
                builder.set<Int>(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            } else {
                val awbMode = when (wbPreset) {
                    WhiteBalancePreset.TUNGSTEN -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                    WhiteBalancePreset.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                    WhiteBalancePreset.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                    WhiteBalancePreset.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                    else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
                }
                builder.set<Int>(CaptureRequest.CONTROL_AWB_MODE, awbMode)
            }

            if (isManualFocus) {
                builder.set<Int>(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set<Float>(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDiopter)
            } else {
                builder.set<Int>(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            activeRequestBuilder = builder
            session.setRepeatingRequest(builder.build(), frameSyncCallback, cameraHandler)

            if (!isSessionInRawSensorMode) startZoomSubmitter()
        } catch (e: Exception) {
            Log.w("PixelCameraController", "Ignored camera state exception: ${e.message}")
        }
    }

    private val frameSyncCallback = object : CameraCaptureSession.CaptureCallback() {
        private val blackLevels = FloatArray(4)
        private val wbGains = FloatArray(4)
        private val ccmArray = FloatArray(9)

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (isSessionInRawSensorMode && result.frameNumber % 6L == 0L) {
                result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let {
                    System.arraycopy(it, 0, blackLevels, 0, 4)
                }
                val whiteLevel = (characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
                val cfaPattern = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
                result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { rggb ->
                    wbGains[0] = rggb.red
                    wbGains[1] = rggb.greenEven
                    wbGains[2] = rggb.greenOdd
                    wbGains[3] = rggb.blue
                }
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { matrix ->
                    for (row in 0..2) for (col in 0..2) ccmArray[row * 3 + col] = matrix.getElement(col, row).toFloat()
                }
                NativeVulkanEngine.updateSensorMetadata(blackLevels, whiteLevel, wbGains, ccmArray, cfaPattern, exposureCompEv)
            }
        }
    }
}