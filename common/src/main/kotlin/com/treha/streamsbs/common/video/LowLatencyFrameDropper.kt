package com.treha.streamsbs.common.video

/**
 * Keeps the receiver real-time by rejecting packets/frames that belong to an
 * older RTP timestamp than the newest timestamp already accepted.
 */
class LowLatencyFrameDropper {
    private var newestTimestamp: Int? = null
    private var lastSequenceForTimestamp: Int? = null

    fun shouldAcceptPacket(timestamp: Int, sequence: Int): Boolean {
        val newest = newestTimestamp
        if (newest != null && isOlderRtpTimestamp(timestamp, newest)) return false

        if (newest == null || timestamp != newest) {
            newestTimestamp = timestamp
            lastSequenceForTimestamp = sequence
            return true
        }

        val lastSequence = lastSequenceForTimestamp
        if (lastSequence != null && isOlderOrSameRtpSequence(sequence, lastSequence)) return false
        lastSequenceForTimestamp = sequence
        return true
    }

    fun shouldAcceptFrame(timestamp: Int): Boolean {
        val newest = newestTimestamp ?: return true
        return !isOlderRtpTimestamp(timestamp, newest)
    }

    fun reset() {
        newestTimestamp = null
        lastSequenceForTimestamp = null
    }
}

fun isOlderRtpTimestamp(candidate: Int, reference: Int): Boolean {
    if (candidate == reference) return false
    return ((candidate - reference) < 0)
}

fun isOlderOrSameRtpSequence(candidate: Int, reference: Int): Boolean {
    if (candidate == reference) return true
    val diff = (candidate - reference) and 0xFFFF
    return diff > 0x8000
}
