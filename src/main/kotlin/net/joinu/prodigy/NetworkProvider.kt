package net.joinu.prodigy

import net.joinu.rudp.RUDPSocket
import net.joinu.rudp.runSuspending
import net.joinu.rudp.send
import java.io.Closeable
import java.net.InetSocketAddress


/**
 * An interface that represents some mechanism to work with the network. Implementation can be any.
 * By default [ProtocolRunner] uses [RUDPNetworkProvider] implementation.
 */
interface NetworkProvider : Closeable {
    /**
     * Sends some packet to some receiver. Suspends until packet is delivered.
     *
     * @param packet [ProtocolPacket] - packet to send
     * @param to [InetSocketAddress] - receiver
     */
    suspend fun send(packet: ProtocolPacket, to: InetSocketAddress)

    /**
     * Suspends until receives some packet from network.
     *
     * @return [Pair] of [ProtocolPacket] and [InetSocketAddress]
     */
    suspend fun receive(): Pair<ProtocolPacket, InetSocketAddress>

    /**
     * Binds to some address in order to receive packets.
     */
    fun bind(address: InetSocketAddress)

    /**
     * Runs processing loop (e.g. TCPSocket.listen(), or RUDPSocket.runSuspending())
     */
    suspend fun runSuspending()
}

/**
 * Implementation of [NetworkProvider] that uses [RUDPSocket] to handle networking.
 */
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
