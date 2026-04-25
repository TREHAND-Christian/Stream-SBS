package com.treha.streamsbs55.sender.network

import com.treha.streamsbs55.common.protocol.Ports
import com.treha.streamsbs55.common.protocol.ReceiverRenderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class ReceiverControlClient {
    suspend fun send(host: String, config: ReceiverRenderConfig) = withContext(Dispatchers.IO) {
        val payload = config.serialize().toByteArray(Charsets.UTF_8)
        DatagramSocket().use { socket ->
            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), Ports.CONTROL))
        }
    }
}
