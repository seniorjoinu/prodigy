package net.joinu.prodigy

import mu.KotlinLogging
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap


/**
 * TODO: check serialization optimizations
 */
class ProtocolRunner(val networkProvider: NetworkProvider) {
    val protocols = ConcurrentHashMap<String, AbstractProtocol>()
    val responses = ConcurrentHashMap<Long, CompletableFuture<ProtocolPacket>>()

    private val sendHandler: SendHandler = { packet, to -> networkProvider.send(packet, to) }
    private val receiveHandler: ReceiveHandler = { threadId ->
        val future = CompletableFuture<ProtocolPacket>()
        responses[threadId] = future

        future
    }
    private var handlerWrapper: HandlerWrapper = { handler -> handler() }

    private val logger = KotlinLogging.logger("ProtocolRunner-${Random().nextInt()}")

    fun wrapHandlers(wrapper: HandlerWrapper) {
        handlerWrapper = wrapper
    }

    fun registerProtocol(wrapper: AbstractProtocol) {
        protocols[wrapper.protocol.protocolName] = wrapper

        wrapper.applySendHandler(sendHandler)
        wrapper.protocol.applySendHandler(sendHandler)

        wrapper.applyReceiveHandler(receiveHandler)
        wrapper.protocol.applyReceiveHandler(receiveHandler)

        logger.debug { "Protocol ${wrapper.protocol.protocolName} registered" }
    }

    fun bind(on: InetSocketAddress) {
        logger.debug { "Bound to: $on" }

        networkProvider.bind(on)
    }

    fun runOnce() {
        networkProvider.runOnce()

        val receivedPacketsAndAddresses = mutableListOf<Pair<ProtocolPacket, InetSocketAddress>>()
        while (true) {
            val packetAndAddress = networkProvider.receive() ?: break
            receivedPacketsAndAddresses.add(packetAndAddress)
        }

        receivedPacketsAndAddresses.forEach { (packet, from) ->
            if (packet.protocolFlag == ProtocolPacketFlag.RESPONSE) {
                responses[packet.protocolThreadId]?.complete(packet)

                logger.debug { "Received response [protocolName: ${packet.protocolName}, messageType: ${packet.messageType}, recipient: $from, threadId: ${packet.protocolThreadId}]" }

                return
            }

            val protocol = protocols[packet.protocolName]

            if (protocol == null) {
                logger.debug { "Received a message for unknown protocol: ${packet.protocolName}" }
                return
            }

            val handler = protocol.protocol.handlers[packet.messageType]

            if (handler == null) {
                logger.debug { "Received a message for unknown messageType: ${packet.messageType} for protocol: ${packet.protocolName}" }
                return
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

            handlerWrapper(handler, handler.body)
        }
    }

    fun close() {
        logger.debug { "Closed" }

        networkProvider.close()
    }
}
