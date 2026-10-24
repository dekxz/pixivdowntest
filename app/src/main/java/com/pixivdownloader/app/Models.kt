package com.pixivdownloader.app

enum class ContentType(val label: String) {
    AUTO("Auto-detect"), IMAGE("Image"), UGOIRA("Ugoira Animation")
}

enum class OutFormat(val label: String, val ext: String) {
    ORIGINAL("Original", ""), JPEG("JPEG", "jpeg"), PNG("PNG", "png"), WEBP("WEBP", "webp")
}

enum class ResizeMode(val label: String) {
    NONE("None"), AUTO("Auto (Single Dimension)"), WIDTH("Width"), HEIGHT("Height"), BOTH("Both")
}

data class SpeedOption(val label: String, val fps: Int)

object Presets {
    val QUALITIES = listOf(50, 60, 70, 80, 90, 95, 100)
    const val DEFAULT_QUALITY = 95

    val SPEEDS = listOf(
        SpeedOption("Very Slow (Content: 8 FPS)", 8),
        SpeedOption("Slow (Content: 12 FPS)", 12),
        SpeedOption("Normal (Content: 15 FPS)", 15),
        SpeedOption("Slightly Fast (Content: 20 FPS)", 20),
        SpeedOption("Fast (Content: 24 FPS)", 24),
        SpeedOption("Very Fast (Content: 30 FPS)", 30),
        SpeedOption("Original Speed (Content: 60 FPS)", 60),
    )
    const val DEFAULT_SPEED_INDEX = 2
}
