package com.treha.streamsbs.receiver.network

import com.treha.streamsbs.common.protocol.DiscoveryProtocol
import com.treha.streamsbs.common.protocol.Ports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

class DiscoveryResponder(
    private val deviceName: String,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        DatagramSocket(Ports.DISCOVERY).use { socket ->
            socket.soTimeout = 1000
            val buffer = ByteArray(128)
            while (true) {
                coroutineContext.ensureActive()
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (message != DiscoveryProtocol.REQUEST) continue
                    val payload = DiscoveryProtocol.buildResponse(deviceName).toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(payload, payload.size, packet.address, packet.port))
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: SocketException) {
                    return@withContext
                }
            }
        }
    }
}
