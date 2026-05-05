package com.treha.streamsbs.common.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.coroutines.coroutineContext

data class UdpDatagram(
    val payload: ByteArray,
    val address: InetAddress,
    val port: Int,
)

class UdpDatagramTransport(
    port: Int? = null,
    private val packetSize: Int = 1500,
) : AutoCloseable {
    private val socket = if (port == null) DatagramSocket() else DatagramSocket(port)

    suspend fun send(host: String, port: Int, payload: ByteArray) = withContext(Dispatchers.IO) {
        socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), port))
    }

    suspend fun receiveLoop(onPacket: suspend (UdpDatagram) -> Unit) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(packetSize)
        while (true) {
            coroutineContext.ensureActive()
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            onPacket(
                UdpDatagram(
                    payload = packet.data.copyOf(packet.length),
                    address = packet.address,
                    port = packet.port,
                ),
            )
        }
    }

    override fun close() {
        socket.close()
    }
}
