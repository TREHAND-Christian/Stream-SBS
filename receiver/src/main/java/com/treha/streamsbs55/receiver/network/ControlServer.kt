package com.treha.streamsbs55.receiver.network

import com.treha.streamsbs55.common.protocol.Ports
import com.treha.streamsbs55.common.protocol.ReceiverRenderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.coroutines.coroutineContext

class ControlServer(
    private val onConfig: (ReceiverRenderConfig, InetAddress) -> Unit,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        DatagramSocket(Ports.CONTROL).use { socket ->
            val buffer = ByteArray(256)
            while (true) {
                coroutineContext.ensureActive()
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val config = ReceiverRenderConfig.parse(
                        String(packet.data, 0, packet.length, Charsets.UTF_8),
                    ) ?: continue
                    onConfig(config, packet.address)
                } catch (_: SocketException) {
                    return@withContext
                }
            }
        }
    }
}
