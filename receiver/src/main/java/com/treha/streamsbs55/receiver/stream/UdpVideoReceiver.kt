package com.treha.streamsbs55.receiver.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.treha.streamsbs55.common.protocol.Ports
import com.treha.streamsbs55.common.video.LowLatencyFrameDropper
import com.treha.streamsbs55.common.video.NAL_START_CODE
import com.treha.streamsbs55.common.video.Rtp
import com.treha.streamsbs55.common.video.nalType
import com.treha.streamsbs55.common.video.parseSpsSize
import com.treha.streamsbs55.common.video.rtpTimestampToPtsUs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

class UdpVideoReceiver(
    private val surface: Surface,
    private val onStatus: (String) -> Unit,
    private val onVideoStats: (VideoStats) -> Unit = {},
) : AutoCloseable {
    private var socket: DatagramSocket? = null
    private var decoder: MediaCodec? = null
    private var configured = false
    private var latestSps: ByteArray? = null
    private var latestPps: ByteArray? = null
    private var accessUnit = ByteArrayOutputStream()
    private var currentTimestamp: Int? = null
    private var currentSentAtMs: Long? = null
    private var currentFlags = 0
    private var currentFrameCorrupt = false
    private var fuBuffer: FuBuffer? = null
    private var lastSeq = -1
    private var currentSsrc: Int? = null
    private val frameDropper = LowLatencyFrameDropper()
    private val queuedFrameSentAtMs = LinkedHashMap<Long, Long>()
    private var statsWindowStartMs = System.currentTimeMillis()
    private var renderedFramesInWindow = 0
    private var latencySumInWindow = 0L
    private var latestLatencyMs = -1L
    private var latestProfile = StreamProfile()

    suspend fun run() = withContext(Dispatchers.IO) {
        socket = DatagramSocket(Ports.STREAM).apply {
            receiveBufferSize = Rtp.SOCKET_BUFFER_SIZE
            runCatching { trafficClass = 0x10 }
        }
        onStatus("listening on ${Ports.STREAM}")
        val packetBuffer = ByteArray(Rtp.PACKET_BUFFER_SIZE)
        try {
            while (true) {
                coroutineContext.ensureActive()
                val packet = DatagramPacket(packetBuffer, packetBuffer.size)
                socket?.receive(packet)
                handlePacket(packet.data, packet.length)
            }
        } catch (_: SocketException) {
            return@withContext
        }
    }

    private fun handlePacket(packet: ByteArray, packetLength: Int) {
        if (packetLength < Rtp.HEADER_SIZE) return
        val header = ByteBuffer.wrap(packet, 0, packetLength)
        val first = header.get().toInt() and 0xFF
        val second = header.get().toInt() and 0xFF
        if ((first shr 6) != 2) return
        val csrcCount = first and 0x0F
        val hasExtension = (first and 0x10) != 0
        val marker = (second and 0x80) != 0
        val sequence = header.short.toInt() and 0xFFFF
        val timestamp = header.int
        val ssrc = header.int

        if (currentSsrc != null && currentSsrc != ssrc) {
            resetPacketState("stream source changed")
        }
        currentSsrc = ssrc

        val extensionStart = Rtp.HEADER_SIZE + (csrcCount * 4)
        if (extensionStart >= packetLength) return
        val (payloadOffset, metadata) = readPayloadOffsetAndMetadata(packet, packetLength, extensionStart, hasExtension)
        if (payloadOffset >= packetLength) return
        metadata.profile?.let { latestProfile = it }
        if (!frameDropper.shouldAcceptPacket(timestamp, sequence)) return
        if (lastSeq != -1 && ((lastSeq + 1) and 0xFFFF) != sequence) {
            currentFrameCorrupt = true
            accessUnit.reset()
            fuBuffer = null
        }
        lastSeq = sequence

        val payloadNalType = packet[payloadOffset].toInt() and 0x1F
        when {
            payloadNalType in 1..23 -> onNal(timestamp, packet.copyOfRange(payloadOffset, packetLength), metadata.sentAtMs, marker)
            payloadNalType == Rtp.FU_A -> handleFuA(packet, payloadOffset, packetLength, timestamp, metadata.sentAtMs, marker)
        }
    }

    private fun readPayloadOffsetAndMetadata(
        packet: ByteArray,
        packetLength: Int,
        extensionStart: Int,
        hasExtension: Boolean,
    ): Pair<Int, RtpMetadata> {
        if (!hasExtension) return extensionStart to RtpMetadata()
        if (extensionStart + 4 > packetLength) return packetLength to RtpMetadata()
        val ext = ByteBuffer.wrap(packet, extensionStart, packetLength - extensionStart)
        val profile = ext.short.toInt() and 0xFFFF
        val words = ext.short.toInt() and 0xFFFF
        val extensionBytes = words * 4
        val payloadOffset = extensionStart + 4 + extensionBytes
        val sentAtMs = if (
            profile == Rtp.TIMESTAMP_EXTENSION_PROFILE &&
            words >= Rtp.TIMESTAMP_EXTENSION_WORDS &&
            extensionStart + 12 <= packetLength
        ) {
            ext.long
        } else {
            null
        }
        val streamProfile = if (
            profile == Rtp.TIMESTAMP_EXTENSION_PROFILE &&
            words >= 4 &&
            extensionStart + 20 <= packetLength
        ) {
            StreamProfile(
                width = ext.short.toInt() and 0xFFFF,
                height = ext.short.toInt() and 0xFFFF,
                targetFps = ext.short.toInt() and 0xFFFF,
                bitrateKbps = ext.short.toInt() and 0xFFFF,
            )
        } else {
            null
        }
        return payloadOffset to RtpMetadata(sentAtMs = sentAtMs, profile = streamProfile)
    }

    private fun handleFuA(
        packet: ByteArray,
        payloadOffset: Int,
        packetLength: Int,
        timestamp: Int,
        sentAtMs: Long?,
        marker: Boolean,
    ) {
        if (packetLength - payloadOffset < 2) return
        val fuIndicator = packet[payloadOffset].toInt() and 0xFF
        val fuHeader = packet[payloadOffset + 1].toInt() and 0xFF
        val start = (fuHeader and 0x80) != 0
        val end = (fuHeader and 0x40) != 0
        val unitType = fuHeader and 0x1F
        val reconstructedHeader = ((fuIndicator and 0xE0) or unitType).toByte()
        val fragment = packet.copyOfRange(payloadOffset + 2, packetLength)

        if (start) {
            fuBuffer = FuBuffer(timestamp, sentAtMs, ByteArrayOutputStream()).also {
                it.buffer.write(byteArrayOf(reconstructedHeader))
                it.buffer.write(fragment)
            }
            return
        }

        val pending = fuBuffer ?: return
        if (pending.timestamp != timestamp) {
            fuBuffer = null
            return
        }
        pending.buffer.write(fragment)
        if (end) {
            fuBuffer = null
            onNal(timestamp, pending.buffer.toByteArray(), pending.sentAtMs ?: sentAtMs, marker)
        }
    }

    private fun onNal(timestamp: Int, nal: ByteArray, sentAtMs: Long?, marker: Boolean) {
        if (currentTimestamp != null && currentTimestamp != timestamp) flush(forceDrop = true)
        currentTimestamp = timestamp
        if (sentAtMs != null) currentSentAtMs = sentAtMs
        if (currentFrameCorrupt) return

        when (nalType(nal)) {
            7 -> {
                if (latestSps != null && !latestSps.contentEquals(nal)) {
                    resetDecoder("video format changed")
                    latestPps = null
                }
                latestSps = nal
                configureDecoderIfReady()
            }

            8 -> {
                if (latestPps != null && !latestPps.contentEquals(nal)) {
                    resetDecoder("video parameter set changed")
                }
                latestPps = nal
                configureDecoderIfReady()
            }

            5 -> {
                currentFlags = currentFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                appendNal(nal)
            }

            else -> appendNal(nal)
        }
        if (marker) flush(forceDrop = false)
    }

    private fun appendNal(nal: ByteArray) {
        accessUnit.write(NAL_START_CODE)
        accessUnit.write(nal)
    }

    private fun configureDecoderIfReady() {
        if (configured) return
        val sps = latestSps ?: return
        val pps = latestPps ?: return
        val (width, height) = parseSpsSize(sps) ?: (1280 to 720)
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(NAL_START_CODE + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(NAL_START_CODE + pps))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }
        decoder = MediaCodec.createDecoderByType(MIME).apply {
            configure(format, surface, null, 0)
            start()
        }
        configured = true
        onStatus("decoder ready ${width}x${height} latency=0")
    }

    private fun resetDecoder(reason: String) {
        resetPacketState(reason)
        configured = false
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
    }

    private fun resetPacketState(reason: String) {
        accessUnit.reset()
        currentTimestamp = null
        currentSentAtMs = null
        currentFlags = 0
        currentFrameCorrupt = false
        fuBuffer = null
        lastSeq = -1
        queuedFrameSentAtMs.clear()
        frameDropper.reset()
        onStatus(reason)
    }

    private fun flush(forceDrop: Boolean) {
        val timestamp = currentTimestamp ?: return
        val frame = accessUnit.toByteArray()
        val sentAtMs = currentSentAtMs
        val flags = currentFlags
        val corrupt = currentFrameCorrupt
        accessUnit.reset()
        currentTimestamp = null
        currentSentAtMs = null
        currentFlags = 0
        currentFrameCorrupt = false
        if (forceDrop || corrupt || frame.isEmpty() || !frameDropper.shouldAcceptFrame(timestamp)) return
        queueFrame(frame, rtpTimestampToPtsUs(timestamp), sentAtMs, flags)
    }

    private fun queueFrame(frame: ByteArray, ptsUs: Long, sentAtMs: Long?, flags: Int) {
        val codec = decoder ?: return
        drain(codec)
        val inputIndex = codec.dequeueInputBuffer(0)
        if (inputIndex < 0) return
        codec.getInputBuffer(inputIndex)?.apply {
            clear()
            put(frame)
        }
        sentAtMs?.let {
            queuedFrameSentAtMs[ptsUs] = it
            while (queuedFrameSentAtMs.size > 12) {
                val first = queuedFrameSentAtMs.keys.firstOrNull() ?: break
                queuedFrameSentAtMs.remove(first)
            }
        }
        codec.queueInputBuffer(inputIndex, 0, frame.size, ptsUs, flags)
        drain(codec)
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var pendingOutputIndex = -1
        var pendingPresentationTimeUs = 0L
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (pendingOutputIndex >= 0) {
                        codec.releaseOutputBuffer(pendingOutputIndex, true)
                        recordRenderedFrame(pendingPresentationTimeUs)
                    }
                    return
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    if (pendingOutputIndex >= 0) {
                        codec.releaseOutputBuffer(pendingOutputIndex, false)
                        queuedFrameSentAtMs.remove(pendingPresentationTimeUs)
                    }
                    pendingOutputIndex = outputIndex
                    pendingPresentationTimeUs = info.presentationTimeUs
                }
            }
        }
    }

    private fun recordRenderedFrame(presentationTimeUs: Long) {
        val sentAtMs = queuedFrameSentAtMs.remove(presentationTimeUs) ?: return
        val latencyMs = (System.currentTimeMillis() - sentAtMs).coerceAtLeast(0)
        latestLatencyMs = latencyMs
        renderedFramesInWindow += 1
        latencySumInWindow += latencyMs

        val now = System.currentTimeMillis()
        val elapsedMs = now - statsWindowStartMs
        if (elapsedMs >= 1000L) {
            val fps = renderedFramesInWindow * 1000f / elapsedMs.toFloat()
            val averageLatencyMs = if (renderedFramesInWindow > 0) {
                latencySumInWindow / renderedFramesInWindow
            } else {
                latestLatencyMs
            }
            onVideoStats(
                VideoStats(
                    latencyMs = latestLatencyMs,
                    averageLatencyMs = averageLatencyMs,
                    fps = fps,
                    profile = latestProfile,
                ),
            )
            statsWindowStartMs = now
            renderedFramesInWindow = 0
            latencySumInWindow = 0L
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
    }

    companion object {
        private const val MIME = "video/avc"
    }
}

private data class FuBuffer(
    val timestamp: Int,
    val sentAtMs: Long?,
    val buffer: ByteArrayOutputStream,
)

data class VideoStats(
    val latencyMs: Long,
    val averageLatencyMs: Long,
    val fps: Float,
    val profile: StreamProfile = StreamProfile(),
)

data class StreamProfile(
    val width: Int = 0,
    val height: Int = 0,
    val targetFps: Int = 0,
    val bitrateKbps: Int = 0,
)

private data class RtpMetadata(
    val sentAtMs: Long? = null,
    val profile: StreamProfile? = null,
)
