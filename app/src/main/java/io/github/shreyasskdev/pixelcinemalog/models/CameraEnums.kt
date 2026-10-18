// File: CameraEnums.kt
// Path: app/src/main/java/io/github/shreyasskdev/pixelcinemalog/models/CameraEnums.kt
package io.github.shreyasskdev.pixelcinemalog.models

enum class CinemaAspectRatio(
    val label: String, val shortTag: String,
    val portraitRatio: Float, val sensorLandscapeRatio: Float
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

enum class ZoomEasing(val rustId: Int) {
    COSINE(0),
    EASE_IN_OUT_CUBIC(1),
    SMOOTHERSTEP(2),
    EASE_IN_OUT_QUINT(3),
    CUSTOM_BEZIER(4)
}
