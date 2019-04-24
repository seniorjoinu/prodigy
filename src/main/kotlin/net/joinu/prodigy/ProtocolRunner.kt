package net.joinu.prodigy

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.joinu.rudp.ConfigurableRUDPSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*


/**
 * TODO: check serialization optimizations
 */
class ProtocolRunner(val bindAddress: InetSocketAddress) {
    val protocols = hashMapOf<String, AbstractProtocol>()
    val socket = ConfigurableRUDPSocket(1400)
    var state = ProtocolRunnerState.NEW

    private val logger = KotlinLogging.logger("ProtocolRunner-${Random().nextInt()}")

    init {
        runBlocking {
            socket.bind(bindAddress)
        }
    }

    fun registerProtocol(protocol: AbstractProtocol) {
        protocols[protocol.protocol.name] = protocol

        protocol.applySendHandler { packet, recipient ->
            val serializedPacket = SerializationUtils.toBytes(packet)
            val buffer = ByteBuffer.allocateDirect(serializedPacket.size)
            buffer.put(serializedPacket)
            buffer.flip()

            socket.send(buffer, recipient, 5000, { 50 }, { 1400 })
        }

        logger.debug { "Protocol ${protocol.protocol.name} registered" }
    }

    suspend fun run() {
        socket.onMessage { buffer, from ->
            val bytes = ByteArray(buffer.limit())
            buffer.get(bytes)

            val packet: ProtocolPacket = SerializationUtils.toAny(bytes)
            val protocol = protocols[packet.protocolName]

            if (protocol == null) {
                logger.debug { "Received a message for unknown protocol: ${packet.protocolName}" }
                return@onMessage
            }

            val handler = protocol.protocol.handlers[packet.messageType]

            if (handler == null) {
                logger.debug { "Received a message for unknown messageType: ${packet.messageType} for protocol: ${packet.protocolName}" }
                return@onMessage
            }

            handler.request = Request(from, packet.payload)

            handler.body.invoke(handler)
        }

        state = ProtocolRunnerState.RUNNING
        logger.debug { "Started on $bindAddress" }

        socket.listen()
    }

    suspend fun close() {
        state = ProtocolRunnerState.CLOSED

        logger.debug { "Closed on $bindAddress" }

        socket.close()
    }
}

enum class ProtocolRunnerState {
    NEW, RUNNING, CLOSED
}
