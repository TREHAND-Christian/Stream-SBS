package com.treha.streamsbs55.common.video

object Rtp {
    const val HEADER_SIZE = 12
    const val PAYLOAD_TYPE_H264 = 96
    const val FU_A = 28
    const val MAX_PAYLOAD = 1200
    const val SOCKET_BUFFER_SIZE = 4 * 1024 * 1024
    const val PACKET_BUFFER_SIZE = 2048
    const val TIMESTAMP_EXTENSION_PROFILE = 0x5453
    const val TIMESTAMP_EXTENSION_WORDS = 4
}

fun ptsUsToRtpTimestamp(ptsUs: Long): Int = ((ptsUs * 90L) / 1000L).toInt()

fun rtpTimestampToPtsUs(timestamp: Int): Long = timestamp.toLong() * 1000L / 90L
