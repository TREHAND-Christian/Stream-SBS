package com.treha.streamsbs55.sender.network

import com.treha.streamsbs55.common.protocol.Ports
import com.treha.streamsbs55.common.protocol.ReceiverRenderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import kotlin.coroutines.coroutineContext

class ReceiverRenderStatusListener(
    private val onStatus: (ReceiverRenderConfig, Map<String, String>) -> Unit,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        DatagramSocket(Ports.STATUS).use { socket ->
            val buffer = ByteArray(1024)
            while (true) {
                coroutineContext.ensureActive()
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val config = ReceiverRenderConfig.parse(message) ?: continue
                    onStatus(config, parseFields(message))
                } catch (_: SocketException) {
                    return@withContext
                }
            }
        }
    }

    private fun parseFields(message: String): Map<String, String> =
        message.split(';').mapNotNull { field ->
            val index = field.indexOf('=')
            if (index <= 0) return@mapNotNull null
            field.substring(0, index) to field.substring(index + 1)
        }.toMap()
}
