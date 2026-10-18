// File: UiModels.kt
// Path: app/src/main/java/io/github/shreyasskdev/pixelcinemalog/models/UiModels.kt
package io.github.shreyasskdev.pixelcinemalog.models

enum class ProDialTab(val label: String) {
    NONE(""), LOG("PROFILE"), ISO("ISO"), SHUTTER("SHUTTER"),
    EV("EV"), WB("WB"), FOCUS("FOCUS"), ASPECT("RATIO")
}

enum class ZoomDurationMode { AUTO, FIXED }

enum class BezierPreset(val label: String, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    CINEMATIC("CINEMATIC", 0.25f, 0.10f, 0.25f, 1.00f),
    SMOOTH   ("SMOOTH",    0.42f, 0.00f, 0.58f, 1.00f),
    LINEAR   ("LINEAR",    0.25f, 0.25f, 0.75f, 0.75f),
    SNAP     ("SNAP",      0.15f, 0.85f, 0.25f, 1.00f),
    GLIDE    ("GLIDE",     0.15f, 0.00f, 0.15f, 1.00f),
    APPLE_CAM("APPLE CAM", 0.40f, 0.00f, 0.20f, 1.00f)
}