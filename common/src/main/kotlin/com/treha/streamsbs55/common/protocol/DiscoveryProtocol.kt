package com.treha.streamsbs55.common.protocol

data class ReceiverEndpoint(
    val host: String,
    val deviceName: String,
)

object DiscoveryProtocol {
    const val REQUEST = "STREAM_SBS_55_DISCOVER"
    private const val RESPONSE_PREFIX = "STREAM_SBS_55_RECEIVER"

    fun buildResponse(deviceName: String): String = "$RESPONSE_PREFIX|${deviceName.ifBlank { "Receiver" }}"

    fun parseResponse(message: String): String? {
        if (!message.startsWith("$RESPONSE_PREFIX|")) return null
        return message.substringAfter('|').ifBlank { "Receiver" }
    }
}
