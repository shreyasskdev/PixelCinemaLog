// File: NativeVulkanEngine.kt
// Path: app/src/main/java/io/github/shreyasskdev/pixelcinemalog/camera/NativeVulkanEngine.kt
package io.github.shreyasskdev.pixelcinemalog.camera

import android.view.Surface

object NativeVulkanEngine {
    init { System.loadLibrary("pixel_log_engine") }
    external fun initEngine()
    external fun setZoomTarget(targetZoom: Float)
    external fun getCurrentZoom(): Float
    external fun isZoomAnimating(): Boolean
    external fun resetZoomTo(zoom: Float)
    external fun snapshotZoom()
    external fun setZoomEasing(mode: Int)
    external fun setCustomBezierEasing(x1: Float, y1: Float, x2: Float, y2: Float)
    external fun setZoomDurationFixed(seconds: Float)
    external fun getZoomDurationFixed(): Float
    external fun setZoomDurationScale(scale: Float)
    external fun getZoomDurationScale(): Float

    external fun getCustomBezierEasing(): FloatArray
    external fun createRawSensorSurface(width: Int, height: Int): Surface
    external fun setRawOutputSurfaces(previewSurface: Surface?, recordSurface: Surface?)
    external fun setRawDumpFd(fd: Int)
    external fun setCameraMode(transferMode: Int, previewAssist: Int)
    external fun generateLogCurve(transferMode: Int, exposureEv: Float): FloatArray
    external fun getLiveHistogram(): FloatArray
    external fun getRaw16FrameCount(filePath: String): Int
    external fun renderRaw16FrameFromFile(
        filePath: String, frameIndex: Int, transferMode: Int,
        previewAssist: Int, exposureEv: Float, surface: Surface
    ): Int
    external fun exportRaw16ToCinemaDng(raw16Path: String, outDirPath: String): Int
    external fun updateSensorMetadata(
        blackLevel: FloatArray, whiteLevel: Float,
        wbGains: FloatArray, ccm: FloatArray,
        cfaPattern: Int, exposureEv: Float
    )
}
