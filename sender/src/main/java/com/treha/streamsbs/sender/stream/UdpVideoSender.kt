package com.treha.streamsbs.sender.stream

import android.media.MediaCodec
import android.media.MediaFormat
import com.treha.streamsbs.common.video.Rtp
import com.treha.streamsbs.common.video.firstNal
import com.treha.streamsbs.common.video.ptsUsToRtpTimestamp
import com.treha.streamsbs.common.video.splitH264Nals
import com.treha.streamsbs.common.video.toByteArray
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.nio.ByteBuffer
import kotlin.random.Random

class UdpVideoSender(
    host: String,
    private val port: Int,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int,
    private val logger: (String) -> Unit,
) : AutoCloseable {
    private val socket = DatagramSocket().apply {
        sendBufferSize = Rtp.SOCKET_BUFFER_SIZE
        runCatching { trafficClass = 0x10 }
    }
    private val address = InetAddress.getByName(host)
    private var sequence = Random.nextInt(0, 0xFFFF)
    private val ssrc = Random.nextInt()
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun onOutputFormatChanged(format: MediaFormat) {
        sps = firstNal(format.getByteBuffer("csd-0")?.toByteArray())
        pps = firstNal(format.getByteBuffer("csd-1")?.toByteArray())
    }

    fun sendFrame(data: ByteArray, ptsUs: Long, flags: Int) {
        val timestamp = ptsUsToRtpTimestamp(ptsUs)
        val sentAtMs = System.currentTimeMillis()
        val nals = splitH264Nals(data)
        if (nals.isEmpty()) return
        if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            sps?.let { sendNal(it, timestamp, sentAtMs, marker = false) }
            pps?.let { sendNal(it, timestamp, sentAtMs, marker = false) }
        }
        nals.forEachIndexed { index, nal ->
            sendNal(nal, timestamp, sentAtMs, marker = index == nals.lastIndex)
        }
    }

    private fun sendNal(nal: ByteArray, timestamp: Int, sentAtMs: Long, marker: Boolean) {
        if (nal.size <= Rtp.MAX_PAYLOAD) {
            sendRtp(payload = nal, timestamp = timestamp, sentAtMs = sentAtMs, marker = marker)
            return
        }

        val nalHeader = nal[0].toInt() and 0xFF
        val fuIndicator = (nalHeader and 0xE0) or Rtp.FU_A
        val nalType = nalHeader and 0x1F
        var offset = 1
        while (offset < nal.size) {
            val chunkSize = minOf(Rtp.MAX_PAYLOAD - 2, nal.size - offset)
            val start = offset == 1
            val end = offset + chunkSize >= nal.size
            val payload = ByteArray(chunkSize + 2)
            payload[0] = fuIndicator.toByte()
            payload[1] = ((if (start) 0x80 else 0) or (if (end) 0x40 else 0) or nalType).toByte()
            nal.copyInto(payload, destinationOffset = 2, startIndex = offset, endIndex = offset + chunkSize)
            sendRtp(payload = payload, timestamp = timestamp, sentAtMs = sentAtMs, marker = marker && end)
            offset += chunkSize
        }
    }

    private fun sendRtp(payload: ByteArray, timestamp: Int, sentAtMs: Long, marker: Boolean) {
        val extensionSize = 4 + (Rtp.TIMESTAMP_EXTENSION_WORDS * 4)
        val packet = ByteArray(Rtp.HEADER_SIZE + extensionSize + payload.size)
        val header = ByteBuffer.wrap(packet)
        header.put(0x90.toByte())
        header.put(((if (marker) 0x80 else 0x00) or Rtp.PAYLOAD_TYPE_H264).toByte())
        header.putShort(sequence.toShort())
        header.putInt(timestamp)
        header.putInt(ssrc)
        header.putShort(Rtp.TIMESTAMP_EXTENSION_PROFILE.toShort())
        header.putShort(Rtp.TIMESTAMP_EXTENSION_WORDS.toShort())
        header.putLong(sentAtMs)
        header.putShort(width.coerceIn(0, 0xFFFF).toShort())
        header.putShort(height.coerceIn(0, 0xFFFF).toShort())
        header.putShort(frameRate.coerceIn(0, 0xFFFF).toShort())
        header.putShort((bitRate / 1000).coerceIn(0, 0xFFFF).toShort())
        payload.copyInto(packet, destinationOffset = Rtp.HEADER_SIZE + extensionSize)
        try {
            socket.send(DatagramPacket(packet, packet.size, address, port))
        } catch (_: SocketException) {
            return
        }
        sequence = (sequence + 1) and 0xFFFF
    }

    override fun close() {
        logger("video sender closed")
        socket.close()
    }
}
