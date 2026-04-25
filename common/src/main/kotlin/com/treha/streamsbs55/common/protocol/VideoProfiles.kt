package com.treha.streamsbs55.common.protocol

object VideoProfiles {
    val resolutions = listOf(
        ResolutionProfile("2220x1080", 2220, 1080),
        ResolutionProfile("1920x1080", 1920, 1080),
        ResolutionProfile("1600x900", 1600, 900),
        ResolutionProfile("1280x720", 1280, 720),
        ResolutionProfile("960x540", 960, 540),
        ResolutionProfile("854x480", 854, 480),
    )

    val frameRates = listOf(
        FrameRateProfile("30 fps", 30),
        FrameRateProfile("24 fps", 24),
        FrameRateProfile("20 fps", 20),
    )

    val bitRates = listOf(
        BitRateProfile("10 Mbps", 10_000_000),
        BitRateProfile("8 Mbps", 8_000_000),
        BitRateProfile("6 Mbps", 6_000_000),
        BitRateProfile("5 Mbps", 5_000_000),
        BitRateProfile("4 Mbps", 4_000_000),
        BitRateProfile("3 Mbps", 3_000_000),
    )

    fun resolutionIndex(width: Int, height: Int): Int =
        resolutions.indexOfFirst { it.width == width && it.height == height }.takeIf { it >= 0 } ?: DEFAULT_RESOLUTION_INDEX

    fun frameRateIndex(frameRate: Int): Int =
        frameRates.indexOfFirst { it.value == frameRate }.takeIf { it >= 0 } ?: DEFAULT_FRAME_RATE_INDEX

    fun bitRateIndex(bitRate: Int): Int =
        bitRates.indexOfFirst { it.value == bitRate }.takeIf { it >= 0 } ?: DEFAULT_BIT_RATE_INDEX

    const val DEFAULT_RESOLUTION_INDEX = 3
    const val DEFAULT_FRAME_RATE_INDEX = 0
    const val DEFAULT_BIT_RATE_INDEX = 0
}

data class ResolutionProfile(
    val label: String,
    val width: Int,
    val height: Int,
)

data class FrameRateProfile(
    val label: String,
    val value: Int,
)

data class BitRateProfile(
    val label: String,
    val value: Int,
)
