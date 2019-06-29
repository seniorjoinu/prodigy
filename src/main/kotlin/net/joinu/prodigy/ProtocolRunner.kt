package net.joinu.prodigy

import kotlinx.coroutines.*
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext


/**
 * The class that is used to actually run protocols
 *
 * @param networkProvider [NetworkProvider] - some entity that is responsible for sending and receiving packets
 */
class ProtocolRunner(val networkProvider: NetworkProvider) {
    val protocols = ConcurrentHashMap<String, AbstractProtocol>()
    val responses = ConcurrentHashMap<Long, ProtocolPacket>()

    private val sendHandler: SendHandler = { packet, to -> networkProvider.send(packet, to) }
    private val receiveHandler: ReceiveHandler = { threadId ->
        var responsePacket: ProtocolPacket? = null

        while (coroutineContext.isActive) {
            responsePacket = responses.remove(threadId)
            if (responsePacket != null) break
            delay(1)
        }

        responsePacket!!
    }

    private val logger = KotlinLogging.logger("ProtocolRunner-${Random().nextInt()}")

    /**
     * Add handler to handler runner context.
     *
     * @param protocol [AbstractProtocol] - protocol to add
     */
    fun registerProtocol(protocol: AbstractProtocol) {
        protocols[protocol.handler.protocolName] = protocol

        protocol.applySendHandler(sendHandler)
        protocol.handler.applySendHandler(sendHandler)

        protocol.applyReceiveHandler(receiveHandler)
        protocol.handler.applyReceiveHandler(receiveHandler)

        logger.debug { "Protocol ${protocol.handler.protocolName} registered" }
    }

    /**
     * Binds [NetworkProvider] to some address
     *
     * @param on [InetSocketAddress] - address to bind
     */
    fun bind(on: InetSocketAddress) {
        logger.debug { "Bound to: $on" }

        networkProvider.bind(on)
    }

    /**
     * Runs [NetworkProvider] and handles packet transmission and dispatching
     */
    suspend fun runSuspending() = coroutineScope {
        launch { networkProvider.runSuspending() }

        supervisorScope {
            while (coroutineContext.isActive) {
                val (packet, from) = networkProvider.receive()

                if (packet.protocolFlag == ProtocolPacketFlag.RESPONSE) {
                    responses[packet.protocolThreadId] = packet

                    logger.debug { "Received response [protocolName: ${packet.protocolName}, messageType: ${packet.messageType}, recipient: $from, threadId: ${packet.protocolThreadId}]" }
                    continue
                }

                val protocol = protocols[packet.protocolName]

                if (protocol == null) {
                    logger.debug { "Received a message for unknown handler: ${packet.protocolName}" }
                    continue
                }

                val handler = protocol.handler.handlers[packet.messageType]

                if (handler == null) {
                    logger.debug { "Received a message for unknown messageType: ${packet.messageType} for handler: ${packet.protocolName}" }
                    continue
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

                launch {
                    handler.body.invoke(handler)
                }
            }
        }
    }

    /**
     * Closes [NetworkProvider]
     */
    fun close() {
        logger.debug { "Closed" }

        networkProvider.close()
    }
}
