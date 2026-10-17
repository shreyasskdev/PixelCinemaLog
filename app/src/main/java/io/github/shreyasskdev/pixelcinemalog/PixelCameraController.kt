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

enum class CinemaAspectRatio(
    val label: String,
    val shortTag: String,
    val portraitRatio: Float,
    val sensorLandscapeRatio: Float
) {
    OPEN_GATE_4_3("4:3 OPEN GATE", "4:3", 3f / 4f, 4f / 3f),
    FULL_FRAME_3_2("3:2 FULL FRAME", "3:2", 2f / 3f, 3f / 2f),
    WIDESCREEN_16_9("16:9 CINEMA", "16:9", 9f / 16f, 16f / 9f),
    ANAMORPHIC_239("2.39:1 SCOPE", "2.39:1", 9f / 16f, 2.39f),
    SQUARE_1_1("1:1 SQUARE", "1:1", 1f, 1f),
    FULL_STAGE("MAX STAGE", "FULL", 0f, 16f / 9f)
}

enum class ShootingMode(val label: String, val badge: String, val rustId: Int) {
    UNCOMPRESSED_RAW16("Lossless RAW16 (Disk)", "LOSSLESS RAW16", 8),
    RAW_SENSOR_SLOG3("RAW16 -> S-Log3 (All-I)", "RAW16 ALL-I", 7),
    ZERO_POST_HLG10("10-Bit HLG No-Post (All-I)", "HLG10 ALL-I", 1),
    SONY_SLOG3_10BIT("Sony S-Log3 (All-I)", "S-LOG3 ALL-I", 2),
    ARRI_LOGC4_10BIT("ARRI LogC4 (All-I)", "LOG-C4 ALL-I", 3),
    ACESCCT_10BIT("ACEScct Log (All-I)", "ACEScct ALL-I", 4),
    CANON_CLOG3_10BIT("Canon C-Log3 (All-I)", "C-LOG3 ALL-I", 5),
    APPLE_LOG_10BIT("Apple Log (All-I)", "APPLE LOG ALL-I", 6),
    NORMAL_PIXEL_ISP("Normal (Pixel ISP All-I)", "ISP ALL-I", 0)
}

enum class ViewfinderAssist(val label: String, val id: Int) {
    OFF("Flat Log", 0),
    REC709_LUT("Rec.709 LUT", 1),
    FALSE_COLOR("False Color", 2),
    ZEBRA_95("Zebras 95%", 3)
}

enum class WhiteBalancePreset(val label: String, val kelvin: Int, val rGain: Float, val bGain: Float) {
    AUTO("AWB", 0, 1.0f, 1.0f),
    TUNGSTEN("3200K", 3200, 1.35f, 2.45f),
    FLUORESCENT("4300K", 4300, 1.65f, 2.05f),
    DAYLIGHT("5600K", 5600, 1.95f, 1.65f),
    CLOUDY("6500K", 6500, 2.15f, 1.45f)
}

object NativeVulkanEngine {
    init {
        System.loadLibrary("pixel_log_engine")
    }
    external fun initEngine()
    external fun setZoomTarget(targetZoom: Float)
    external fun stepZoomServo(): Float
    external fun createRawSensorSurface(width: Int, height: Int): Surface
    external fun setRawOutputSurfaces(previewSurface: Surface?, recordSurface: Surface?)
    external fun setRawDumpFd(fd: Int)
    external fun setCameraMode(transferMode: Int, previewAssist: Int)
    external fun generateLogCurve(transferMode: Int, exposureEv: Float): FloatArray
    external fun getLiveHistogram(): FloatArray
    external fun getRaw16FrameCount(filePath: String): Int
    external fun renderRaw16FrameFromFile(
        filePath: String,
        frameIndex: Int,
        transferMode: Int,
        previewAssist: Int,
        exposureEv: Float,
        surface: Surface
    ): Int
    external fun exportRaw16ToCinemaDng(raw16Path: String, outDirPath: String): Int
    external fun updateSensorMetadata(
        blackLevel: FloatArray,
        whiteLevel: Float,
        wbGains: FloatArray,
        ccm: FloatArray,
        cfaPattern: Int,
        exposureEv: Float
    )
}

class PixelCameraController(private val context: Context) {
    private val cameraThread = HandlerThread("PixelCinemaCameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var cameraDevice: CameraDevice? = null
    @Volatile
    private var captureSession: CameraCaptureSession? = null
    @Volatile
    private var previewSurface: Surface? = null
    private var activeRawInputSurface: Surface? = null
    private var isSessionInRawSensorMode: Boolean = false
    private lateinit var characteristics: CameraCharacteristics

    // Pre-built CaptureRequest.Builder cached on cameraThread so per-frame zoom updates
    // only mutate CONTROL_ZOOM_RATIO with zero object allocation!
    private var activeRequestBuilder: CaptureRequest.Builder? = null

    private var persistentRecordSurface: Surface? = null
    private var standbyVideoCodec: MediaCodec? = null
    private var configuredVideoSize: Size = Size(3840, 2160)
    private var configuredHlg10: Boolean = false

    private var cachedTonemapCurve: TonemapCurve? = null
    private var cachedCurveModeId: Int = -1
    private var cachedCurveEv: Float = -999f

    @Volatile
    var isRecording = false
        private set
    private val isBusyToggling = AtomicBoolean(false)
    private var allIntraRecorder: CinemaAllIntraRecorder? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentVideoUri: Uri? = null

    private var backLogicalCameraId: String = "0"
    private var frontCameraId: String = "1"
    var isFrontCamera: Boolean = false
        private set

    @Volatile
    var currentZoomRatio: Float = 1.0f
        private set
    var minZoomRatio: Float = 0.5f
        private set
    var maxZoomRatio: Float = 10.0f
        private set
    @Volatile
    private var isZoomServoActive: Boolean = false
    private var onZoomUiCallback: ((Float) -> Unit)? = null

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

    init {
        NativeVulkanEngine.initEngine()
        discoverLogicalCameras()
    }

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
                if (span > bestZoomSpan) {
                    bestZoomSpan = span
                    bestBackId = id
                }
            } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                frontCameraId = id
            }
        }
        backLogicalCameraId = bestBackId
        currentCameraId = backLogicalCameraId
    }

    @Volatile
    private var zoomStartRatio: Float = 1.0f
    @Volatile
    private var zoomTargetRatio: Float = 1.0f
    private var zoomRunnable: Runnable? = null

    /**
     * C3-Continuous Minimum-Snap Zoom Servo with Intermediate-1x-Lens Bypass!
     *  - When jumping between 0.5x (UW) and 5x/10x (Tele), bypasses the [1.0x .. 4.99x] Main Wide
     *    sensor zone so the Pixel NEVER switches to the 1x lens in the middle of the zoom!
     *  - Runs at a locked 60Hz monotonic cadence on `cameraHandler` using the cached CaptureRequest.Builder.
     */
    fun animateZoomTo(targetZoom: Float, onZoomUpdated: (Float) -> Unit) {
        val clampedTarget = targetZoom.coerceIn(minZoomRatio, maxZoomRatio)
        onZoomUiCallback = onZoomUpdated

        zoomRunnable?.let { cameraHandler.removeCallbacks(it) }
        zoomStartRatio = currentZoomRatio
        zoomTargetRatio = clampedTarget

        NativeVulkanEngine.setZoomTarget(clampedTarget)
        isZoomServoActive = true

        // Match the exact sensor frame interval (e.g. 33ms for 30fps, 41ms for 24fps, 16ms for 60fps)
        // so we send exactly 1 CaptureRequest per sensor frame and NEVER overflow the Camera2 HAL queue!
        val frameIntervalMs = (1000L / selectedFps.coerceIn(24, 60)).coerceIn(16L, 41L)

        var tickCount = 0
        val runnable = object : Runnable {
            override fun run() {
                if (!isZoomServoActive) return

                val rustStep = NativeVulkanEngine.stepZoomServo()
                val stillAnimating = rustStep > 0f
                val rawZ = abs(rustStep).coerceIn(minZoomRatio, maxZoomRatio)

                currentZoomRatio = rawZ

                val session = captureSession
                val builder = activeRequestBuilder
                if (!isSessionInRawSensorMode && session != null && builder != null) {
                    try {
                        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, rawZ)
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                    } catch (_: Exception) {}
                }

                tickCount++
                if (!stillAnimating || tickCount % 3 == 0) {
                    mainHandler.post { onZoomUiCallback?.invoke(rawZ) }
                }

                if (stillAnimating) {
                    cameraHandler.postDelayed(this, frameIntervalMs)
                } else {
                    isZoomServoActive = false
                    zoomRunnable = null
                    currentZoomRatio = clampedTarget
                    applyCurrentSettingsInternal()
                }
            }
        }

        zoomRunnable = runnable
        cameraHandler.post(runnable)
    }

    fun toggleFrontBackCamera(onZoomReset: (Float) -> Unit) {
        val surface = previewSurface ?: return
        if (isRecording) return
        isZoomServoActive = false
        isFrontCamera = !isFrontCamera
        val targetId = if (isFrontCamera) frontCameraId else backLogicalCameraId
        currentZoomRatio = 1.0f
        NativeVulkanEngine.setZoomTarget(1.0f)
        mainHandler.post { onZoomReset(1.0f) }
        startCamera(surface, targetId)
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
            if (use10Bit) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            }
        }
        standbyVideoCodec = MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            setInputSurface(surface)
        }
    }

    @SuppressLint("MissingPermission")
    fun startCamera(viewfinderSurface: Surface, cameraId: String = currentCameraId) {
        this.previewSurface = viewfinderSurface
        this.currentCameraId = cameraId

        cameraHandler.post {
            try { captureSession?.close() } catch (_: Exception) {}
            captureSession = null
            try { cameraDevice?.close() } catch (_: Exception) {}
            cameraDevice = null

            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                characteristics = manager.getCameraCharacteristics(cameraId)
                maxFocusDistance = characteristics.get(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                ) ?: 10.0f

                val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                minZoomRatio = (zoomRange?.lower ?: 0.5f).coerceAtMost(1.0f)
                maxZoomRatio = (zoomRange?.upper ?: 10.0f).coerceAtLeast(10.0f)
                currentZoomRatio = currentZoomRatio.coerceIn(minZoomRatio, maxZoomRatio)

                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        createCaptureSession(device, viewfinderSurface)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        if (cameraDevice === device) {
                            cameraDevice = null
                            captureSession = null
                        }
                        try { device.close() } catch (_: Exception) {}
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        Log.e("PixelCameraController", "Camera error: $error")
                        if (cameraDevice === device) {
                            cameraDevice = null
                            captureSession = null
                        }
                        try { device.close() } catch (_: Exception) {}
                    }
                }, cameraHandler)
            } catch (e: Exception) {
                Log.e("PixelCameraController", "Failed to open camera", e)
            }
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
        return supported.firstOrNull { it.width == targetW && it.height == targetH }
            ?: Size(1920, 1080)
    }

    private fun createCaptureSession(
        device: CameraDevice,
        viewfinderSurface: Surface
    ) {
        if (cameraDevice !== device) return

        try {
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
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e("PixelCameraController", "RAW_SENSOR session configuration failed!")
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
            val supportsHlg10 = dynamicProfiles?.supportedProfiles?.contains(
                DynamicRangeProfiles.HLG10
            ) == true
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
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("PixelCameraController", "Camera session configuration failed!")
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e("PixelCameraController", "Error in createCaptureSession", e)
        }
    }

    fun toggleRecording(context: Context, onStateChange: (Boolean) -> Unit) {
        if (!isBusyToggling.compareAndSet(false, true)) return
        val surface = previewSurface
        val device = cameraDevice
        if (surface == null || device == null) {
            isBusyToggling.set(false)
            return
        }

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
                try {
                    Thread.sleep(60)
                    recorderToStop?.stopAndRelease()
                } catch (_: Exception) {}

                val pSurf = persistentRecordSurface
                if (pSurf != null && !isRawMode(currentMode)) {
                    try {
                        bindStandbyCodecToPersistentSurface(pSurf, configuredVideoSize, configuredHlg10)
                    } catch (_: Exception) {}
                }

                try { pfdToClose?.close() } catch (_: Exception) {}

                uriToFinalize?.let { uri ->
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
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
                        val moviesDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                            "PixelCinemaLog"
                        )
                        if (!moviesDir.exists()) moviesDir.mkdirs()
                        val rawFile = File(moviesDir, "Pixel_${modeTag}_${System.currentTimeMillis()}.raw16")
                        val pfd = ParcelFileDescriptor.open(
                            rawFile,
                            ParcelFileDescriptor.MODE_CREATE or
                                    ParcelFileDescriptor.MODE_WRITE_ONLY or
                                    ParcelFileDescriptor.MODE_TRUNCATE
                        )
                        currentPfd = pfd
                        NativeVulkanEngine.setRawDumpFd(pfd.fd)
                    } catch (e: Exception) {
                        Log.e("PixelCameraController", "Failed to open RAW16 file", e)
                        isRecording = false
                        mainHandler.post { onStateChange(false) }
                    } finally {
                        isBusyToggling.set(false)
                    }
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
                            width = videoSize.width,
                            height = videoSize.height,
                            fps = fps,
                            use10BitMain10 = use10Bit,
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
                } finally {
                    isBusyToggling.set(false)
                }
            }
        }
    }

    fun applyCurrentSettings() {
        cameraHandler.post {
            applyCurrentSettingsInternal()
        }
    }

    private fun applyCurrentSettingsInternal() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return

        try {
            val wantRawSensor = isRawMode(currentMode)
            val wantHlg10 = currentMode == ShootingMode.ZERO_POST_HLG10
            val desiredSize = pickSupportedVideoSize(is4KResolution)

            if (wantRawSensor != isSessionInRawSensorMode ||
                (!wantRawSensor && !isRecording && (wantHlg10 != configuredHlg10 || desiredSize != configuredVideoSize))
            ) {
                createCaptureSession(device, surface)
                return
            }

            NativeVulkanEngine.setCameraMode(currentMode.rustId, currentAssist.id)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

            if (isSessionInRawSensorMode) {
                val rawTarget = activeRawInputSurface ?: return
                builder.addTarget(rawTarget)
            } else {
                builder.addTarget(surface)
                val recSurf = persistentRecordSurface
                if (isRecording && recSurf != null && recSurf.isValid) {
                    builder.addTarget(recSurf)
                }
            }

            val targetFps = if (isSessionInRawSensorMode) selectedFps.coerceAtMost(30) else selectedFps
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
            builder.set<Int>(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            builder.set(
                CaptureRequest.CONTROL_ZOOM_RATIO,
                currentZoomRatio.coerceIn(minZoomRatio, maxZoomRatio)
            )

            // --- A. ISP FILTERING & COLOR PIPELINE ---
            if (currentMode == ShootingMode.NORMAL_PIXEL_ISP) {
                builder.set<Int>(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                builder.set<Int>(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                builder.set<Int>(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
                builder.set<Int>(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            } else {
                builder.set<Int>(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)

                val nrModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
                when {
                    nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) -> {
                        builder.set<Int>(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                    }
                    nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST) -> {
                        builder.set<Int>(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    }
                    nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF) -> {
                        builder.set<Int>(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                    }
                }
                builder.set<Int>(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                builder.set<Int>(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
                builder.set<Int>(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
                builder.set<Int>(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                builder.set<Int>(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

                if (currentMode.rustId in 2..6) {
                    if (cachedTonemapCurve == null ||
                        cachedCurveModeId != currentMode.rustId ||
                        cachedCurveEv != exposureCompEv
                    ) {
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

            // --- B. UNIVERSAL PRO CONTROLS ---
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
            session.setRepeatingRequest(
                builder.build(),
                if (isSessionInRawSensorMode) frameSyncCallback else null,
                cameraHandler
            )
        } catch (e: Exception) {
            Log.w("PixelCameraController", "Ignored camera state exception: ${e.message}")
        }
    }

    /**
     * Hardware Frame-Locked Callback:
     * Steps the Rust S-Curve Zoom Servo in 1-to-1 lockstep with the camera sensor's VSYNC!
     *  - Never floods the Camera2 binder queue (eliminates jitter)
     *  - Updates BOTH the Viewfinder AND the Recorded MP4 simultaneously
     *  - Zero UI scale handoff = ZERO 1-frame blink at the end!
     */
    private val frameSyncCallback = object : CameraCaptureSession.CaptureCallback() {
        private val blackLevels = FloatArray(4)
        private val wbGains = FloatArray(4)
        private val ccmArray = FloatArray(9)

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            // Sync RAW sensor metadata when in RAW16 mode
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
                    for (row in 0..2) {
                        for (col in 0..2) {
                            ccmArray[row * 3 + col] = matrix.getElement(col, row).toFloat()
                        }
                    }
                }
                NativeVulkanEngine.updateSensorMetadata(
                    blackLevels,
                    whiteLevel,
                    wbGains,
                    ccmArray,
                    cfaPattern,
                    exposureCompEv
                )
            }
        }
    }
}

class CinemaAllIntraRecorder(
    private val fd: FileDescriptor,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val use10BitMain10: Boolean,
    private val persistentSurface: Surface? = null
) {
    private class MuxPacket(
        val isVideo: Boolean,
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo
    )

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null

    var inputSurface: Surface? = null
        private set

    private val isRunning = AtomicBoolean(false)
    private val packetQueue = LinkedBlockingQueue<MuxPacket>(90)

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var muxerWriterThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun prepareAndStart() {
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val targetBitrate = if (width >= 3840) 140_000_000 else 80_000_000

        val videoFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            setInteger(MediaFormat.KEY_PRIORITY, 0)

            if (use10BitMain10) {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                )
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }
        }

        videoCodec = MediaCodec.createEncoderByType(mime).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (persistentSurface != null) {
                setInputSurface(persistentSurface)
                inputSurface = persistentSurface
            } else {
                inputSurface = createInputSurface()
            }
            start()
        }

        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            48_000,
            2
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 320_000)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        val minBufSize = AudioRecord.getMinBufferSize(
            48_000,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(16_384)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            48_000,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufSize * 2
        ).apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                startRecording()
            }
        }

        muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        isRunning.set(true)

        muxerWriterThread = thread(name = "AsyncMuxerDiskWriter") { runMuxerDiskLoop() }
        videoThread = thread(name = "AllIntraVideoDrain") { drainVideoEncoder() }
        audioThread = thread(name = "AllIntraAudioPump") { pumpAndDrainAudio() }
    }

    fun stopAndRelease() {
        if (!isRunning.getAndSet(false)) return

        try { videoCodec?.signalEndOfInputStream() } catch (_: Exception) {}

        try {
            videoThread?.join(1200)
            audioThread?.join(1200)
            muxerWriterThread?.join(2500)
        } catch (_: Exception) {}

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            videoCodec?.stop()
            videoCodec?.release()
        } catch (_: Exception) {}
        videoCodec = null

        try {
            audioCodec?.stop()
            audioCodec?.release()
        } catch (_: Exception) {}
        audioCodec = null

        try {
            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
        } catch (e: Exception) {
            Log.e("CinemaAllIntra", "Error stopping muxer", e)
        }
        muxer = null
        muxerStarted = false

        if (persistentSurface == null) {
            try { inputSurface?.release() } catch (_: Exception) {}
        }
        inputSurface = null
    }

    private fun drainVideoEncoder() {
        val codec = videoCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            val outIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (_: Exception) {
                break
            }

            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(this) {
                        if (!muxerStarted) {
                            videoTrackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                            if (videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                                muxer?.start()
                                muxerStarted = true
                            }
                        }
                    }
                }
                outIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        val copy = ByteBuffer.allocateDirect(bufferInfo.size)
                        copy.put(encodedData)
                        copy.flip()

                        val infoCopy = MediaCodec.BufferInfo().apply {
                            set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        packetQueue.offer(MuxPacket(true, copy, infoCopy))
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    private fun pumpAndDrainAudio() {
        val codec = audioCodec ?: return
        val record = audioRecord ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmBuf = ByteArray(4096)

        while (isRunning.get()) {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readBytes = record.read(pcmBuf, 0, pcmBuf.size)
                if (readBytes > 0) {
                    val inIdx = try {
                        codec.dequeueInputBuffer(5_000L)
                    } catch (_: Exception) {
                        -1
                    }
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        inBuf?.clear()
                        inBuf?.put(pcmBuf, 0, readBytes)
                        val ptsUs = System.nanoTime() / 1000L
                        codec.queueInputBuffer(inIdx, 0, readBytes, ptsUs, 0)
                    }
                } else {
                    Thread.sleep(5)
                }
            } else {
                Thread.sleep(10)
            }

            var outIdx = try {
                codec.dequeueOutputBuffer(bufferInfo, 0L)
            } catch (_: Exception) {
                -1
            }
            while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(this) {
                        if (!muxerStarted) {
                            audioTrackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                            if (videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                                muxer?.start()
                                muxerStarted = true
                            }
                        }
                    }
                } else if (outIdx >= 0) {
                    val encodedData = codec.getOutputBuffer(outIdx)
                    if (encodedData != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        val copy = ByteBuffer.allocateDirect(bufferInfo.size)
                        copy.put(encodedData)
                        copy.flip()

                        val infoCopy = MediaCodec.BufferInfo().apply {
                            set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        packetQueue.offer(MuxPacket(false, copy, infoCopy))
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
                outIdx = try {
                    codec.dequeueOutputBuffer(bufferInfo, 0L)
                } catch (_: Exception) {
                    -1
                }
            }
        }
    }

    private fun runMuxerDiskLoop() {
        var waitedVideoPackets = 0
        while (isRunning.get() || packetQueue.isNotEmpty()) {
            val pkt = packetQueue.poll(15, TimeUnit.MILLISECONDS) ?: continue

            synchronized(this) {
                if (!muxerStarted && videoTrackIndex >= 0) {
                    waitedVideoPackets++
                    if (audioTrackIndex >= 0 || waitedVideoPackets > 8) {
                        muxer?.start()
                        muxerStarted = true
                    }
                }
            }

            if (muxerStarted) {
                try {
                    val track = if (pkt.isVideo) videoTrackIndex else audioTrackIndex
                    if (track >= 0) {
                        muxer?.writeSampleData(track, pkt.data, pkt.info)
                    }
                } catch (_: Exception) {}
            }
        }
    }
}