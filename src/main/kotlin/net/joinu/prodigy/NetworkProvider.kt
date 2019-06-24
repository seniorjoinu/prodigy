package net.joinu.prodigy

import net.joinu.rudp.RUDPSocket
import net.joinu.rudp.send
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture


interface NetworkProvider : Closeable {
    fun send(packet: ProtocolPacket, to: InetSocketAddress): CompletableFuture<Unit>
    fun receive(): Pair<ProtocolPacket, InetSocketAddress>?
    fun bind(address: InetSocketAddress)
    fun runOnce()
}

class RUDPNetworkProvider(val socket: RUDPSocket = RUDPSocket()) : NetworkProvider {

    override fun send(packet: ProtocolPacket, to: InetSocketAddress): CompletableFuture<Unit> {
        val data = SerializationUtils.toBytes(packet)
        return socket.send(data, to).thenApply {}
    }

    override fun receive(): Pair<ProtocolPacket, InetSocketAddress>? {
        val data = socket.receive() ?: return null

        return SerializationUtils.toAny<ProtocolPacket>(data.data) to data.address
    }

    override fun bind(address: InetSocketAddress) {
        socket.bind(address)
    }

    override fun close() {
        socket.close()
    }

    override fun runOnce() {
        socket.runOnce()
    }
}
