package com.treha.streamsbs55.sender.network

import com.treha.streamsbs55.common.protocol.DiscoveryProtocol
import com.treha.streamsbs55.common.protocol.Ports
import com.treha.streamsbs55.common.protocol.ReceiverEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

class ReceiverDiscoveryClient {
    suspend fun discover(timeoutMs: Int = 2500): ReceiverEndpoint? = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMs
            val payload = DiscoveryProtocol.REQUEST.toByteArray(Charsets.UTF_8)
            discoveryTargets().forEach { address ->
                repeat(2) {
                    socket.send(DatagramPacket(payload, payload.size, address, Ports.DISCOVERY))
                }
            }

            val buffer = ByteArray(128)
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val deviceName = DiscoveryProtocol.parseResponse(
                    String(packet.data, 0, packet.length, Charsets.UTF_8),
                ) ?: continue
                val host = packet.address?.hostAddress ?: continue
                return@withContext ReceiverEndpoint(host = host, deviceName = deviceName)
            }
            null
        }
    }

    private fun discoveryTargets(): List<InetAddress> {
        val targets = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { targets += it }
        }
        return targets.toList()
    }
}
