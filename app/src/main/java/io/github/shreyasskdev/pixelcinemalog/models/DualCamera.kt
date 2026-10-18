// File: DualCamera.kt
// Path: app/src/main/java/io/github/shreyasskdev/pixelcinemalog/models/DualCamera.kt
package io.github.shreyasskdev.pixelcinemalog.models

/** Identifies a concurrent (physical) camera pair. */
data class DualCamera(val logicalId: String, val wideId: String, val teleId: String)
