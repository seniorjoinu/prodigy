package net.joinu.prodigy

import net.joinu.rudp.RUDPSocket
import net.joinu.rudp.runSuspending
import net.joinu.rudp.send
import java.io.Closeable
import java.net.InetSocketAddress


interface NetworkProvider : Closeable {
    suspend fun send(packet: ProtocolPacket, to: InetSocketAddress)
    suspend fun receive(): Pair<ProtocolPacket, InetSocketAddress>
    fun bind(address: InetSocketAddress)
    suspend fun runSuspending()
}

class RUDPNetworkProvider(val socket: RUDPSocket = RUDPSocket()) : NetworkProvider {

    override suspend fun send(packet: ProtocolPacket, to: InetSocketAddress) {
        val data = SerializationUtils.toBytes(packet)
        socket.send(data, to)
    }

    override suspend fun receive(): Pair<ProtocolPacket, InetSocketAddress> {
        val data = socket.receive()

        return SerializationUtils.toAny<ProtocolPacket>(data.data) to data.address
    }

    override fun bind(address: InetSocketAddress) {
        socket.bind(address)
    }

    override fun close() {
        socket.close()
    }

    override suspend fun runSuspending() {
        socket.runSuspending()
    }
}
