package com.treha.streamsbs.common.protocol

import java.util.Locale

data class ReceiverRenderConfig(
    val sbsEnabled: Boolean = true,
    val fullFrameEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    val cameraOpacity: Float = 1f,
    val zoom: Float = 1f,
    val verticalZoom: Float = 1f,
    val horizontalOffset: Float = 0f,
    val verticalOffset: Float = 0f,
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val videoFrameRate: Int = 30,
    val videoBitRate: Int = 10_000_000,
) {
    fun serialize(): String = String.format(
        Locale.US,
        "sbs=%d;full=%d;camera=%d;opacity=%.3f;zoom=%.3f;vzoom=%.3f;hoff=%.3f;voff=%.3f;vwidth=%d;vheight=%d;vfps=%d;vbitrate=%d",
        if (sbsEnabled) 1 else 0,
        if (fullFrameEnabled) 1 else 0,
        if (cameraEnabled) 1 else 0,
        cameraOpacity.coerceIn(0f, 1f),
        zoom,
        verticalZoom,
        horizontalOffset,
        verticalOffset,
        videoWidth,
        videoHeight,
        videoFrameRate,
        videoBitRate,
    )

    companion object {
        fun parse(message: String): ReceiverRenderConfig? {
            val values = message.split(';').mapNotNull { entry ->
                val pair = entry.split('=', limit = 2)
                if (pair.size != 2) null else pair[0] to pair[1]
            }.toMap()
            return ReceiverRenderConfig(
                sbsEnabled = values["sbs"].asBool(default = true),
                fullFrameEnabled = values["full"].asBool(default = false),
                cameraEnabled = values["camera"].asBool(default = false),
                cameraOpacity = values["opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                zoom = values["zoom"]?.toFloatOrNull() ?: 1f,
                verticalZoom = values["vzoom"]?.toFloatOrNull() ?: 1f,
                horizontalOffset = values["hoff"]?.toFloatOrNull() ?: 0f,
                verticalOffset = values["voff"]?.toFloatOrNull() ?: 0f,
                videoWidth = values["vwidth"]?.toIntOrNull() ?: 1280,
                videoHeight = values["vheight"]?.toIntOrNull() ?: 720,
                videoFrameRate = values["vfps"]?.toIntOrNull() ?: 30,
                videoBitRate = values["vbitrate"]?.toIntOrNull() ?: 10_000_000,
            )
        }
    }
}

private fun String?.asBool(default: Boolean): Boolean = when {
    this == null -> default
    this == "1" -> true
    this == "0" -> false
    this.equals("true", ignoreCase = true) -> true
    this.equals("false", ignoreCase = true) -> false
    else -> default
}
