package net.joinu.prodigy

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.joinu.rudp.ConfigurableRUDPSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap


/**
 * TODO: check serialization optimizations
 */
class ProtocolRunner(val bindAddress: InetSocketAddress) {
    val protocols = ConcurrentHashMap<String, AbstractProtocol>()
    val socket = ConfigurableRUDPSocket(1400) // TODO: make it configurable
    val responses = ConcurrentHashMap<Long, ProtocolPacket>()
    var state = ProtocolRunnerState.NEW

    val sendHandler: SendHandler = { packet, recipient ->
        val serializedPacket = SerializationUtils.toBytes(packet)
        val buffer = ByteBuffer.allocateDirect(serializedPacket.size)
        buffer.put(serializedPacket)
        buffer.flip()

        socket.send(buffer, recipient, 5000, { 50 }, { 1400 })
    }

    val receiveHandler: ReceiveHandler = {
        val response = responses[it]
        if (response != null)
            responses.remove(it)

        response
    }

    private val logger = KotlinLogging.logger("ProtocolRunner-${Random().nextInt()}")

    init {
        runBlocking {
            socket.bind(bindAddress)
            logger.debug { "Bound to $bindAddress" }
        }
    }

    fun registerProtocol(protocol: AbstractProtocol) {
        protocols[protocol.protocol.name] = protocol
        protocol.applySendHandler(sendHandler)
        protocol.applyReceiveHandler(receiveHandler)

        logger.debug { "Protocol ${protocol.protocol.name} registered" }
    }

    suspend fun run() {
        socket.onMessage { buffer, from ->
            val bytes = ByteArray(buffer.limit())
            buffer.get(bytes)

            val packet: ProtocolPacket = SerializationUtils.toAny(bytes)

            if (packet.protocolFlag == ProtocolPacketFlag.RESPONSE) {
                responses[packet.protocolThreadId] = packet

                logger.debug { "Received RESPONSE packet for threadId: ${packet.protocolThreadId}, storing..." }

                return@onMessage
            }

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

            handler.request = Request(
                from,
                packet.payload,
                packet.protocolThreadId,
                packet.messageType,
                packet.protocolName,
                sendHandler
            )

            logger.debug { "Received REQUEST packet for threadId: ${packet.protocolThreadId}, invoking handler..." }

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
