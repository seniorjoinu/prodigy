package net.joinu.prodigy

import kotlinx.coroutines.*
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext


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
                    logger.debug { "Received a message for unknown protocol: ${packet.protocolName}" }
                    continue
                }

                val handler = protocol.protocol.handlers[packet.messageType]

                if (handler == null) {
                    logger.debug { "Received a message for unknown messageType: ${packet.messageType} for protocol: ${packet.protocolName}" }
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

    fun close() {
        logger.debug { "Closed" }

        networkProvider.close()
    }
}
