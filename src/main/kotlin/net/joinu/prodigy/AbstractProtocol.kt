package net.joinu.prodigy

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import java.io.Serializable
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.TimeoutException

// TODO: fix exception pop

abstract class AbstractProtocol {
    abstract val protocol: Protocol

    private val logger = KotlinLogging.logger("Protocol-${Random().nextInt()}")

    lateinit var sendHandler: SendHandler
    internal fun applySendHandler(handler: SendHandler) {
        sendHandler = handler
        protocol.applySendHandler(handler)
    }

    lateinit var receiveHandler: ReceiveHandler
    internal fun applyReceiveHandler(handler: ReceiveHandler) {
        receiveHandler = handler
        protocol.applyReceiveHandler(handler)
    }

    protected suspend fun send(
        messageType: String,
        recipient: InetSocketAddress,
        messageBody: Serializable? = null,
        protocolName: String = protocol.name
    ) {
        val serializedBody = SerializationUtils.toBytes(messageBody)
        val packet = ProtocolPacket(protocolName = protocolName, messageType = messageType, payload = serializedBody)

        sendHandler(packet, recipient)

        logger.debug { "Sent request [protocolName: $protocolName, messageType: $messageType, recipient: $recipient]" }
    }

    protected suspend inline fun <reified T> sendAndReceive(
        messageType: String,
        recipient: InetSocketAddress,
        messageBody: Serializable? = null,
        protocolName: String = protocol.name,
        timeoutMs: Long = 10000
    ) = sendAndReceive(messageType, recipient, T::class.java, messageBody, protocolName, timeoutMs)

    protected suspend fun <T> sendAndReceive(
        messageType: String,
        recipient: InetSocketAddress,
        responseClazz: Class<T>,
        messageBody: Serializable? = null,
        protocolName: String = protocol.name,
        timeoutMs: Long = 10000
    ): T {
        val serializedBody = SerializationUtils.toBytes(messageBody)
        val threadId = Random().nextLong()
        val packet = ProtocolPacket(
            protocolThreadId = threadId,
            protocolName = protocolName,
            messageType = messageType,
            payload = serializedBody
        )

        sendHandler(packet, recipient)

        logger.debug { "Sent request [protocolName: $protocolName, messageType: $messageType, recipient: $recipient, threadId: $threadId], waiting for response..." }

        val responsePacket = withTimeoutOrNull(timeoutMs) {
            var response: ProtocolPacket?

            while (true) {
                response = receiveHandler(threadId)

                if (response != null)
                    break
            }

            response
        } ?: throw TimeoutException("Response timeout for threadId: $threadId elapsed")

        logger.debug { "Received response [protocolName: $protocolName, messageType: $messageType, recipient: $recipient, threadId: $threadId]" }

        return SerializationUtils.toAny(responsePacket.payload, responseClazz)
    }
}

class Protocol(val name: String) {
    val handlers = hashMapOf<String, Handler>()

    fun on(type: String, body: suspend Handler.() -> Unit) {
        val handler = Handler(name, type, body)
        handlers[type] = handler
    }

    internal fun applySendHandler(handler: SendHandler) {
        handlers.values.forEach { it.applySendHandler(handler) }
    }

    internal fun applyReceiveHandler(handler: ReceiveHandler) {
        handlers.values.forEach { it.applyReceivehandler(handler) }
    }
}

class Request(
    val sender: InetSocketAddress,
    val payload: ByteArray,
    val threadId: Long,
    val messageType: String,
    val protocolName: String,
    internal val respondHandler: SendHandler
) {
    fun <T> getPayloadAs(clazz: Class<T>): T = SerializationUtils.toAny(payload, clazz)
    inline fun <reified T> getPayloadAs(): T = SerializationUtils.toAny(payload)

    private val logger = KotlinLogging.logger("Request-$protocolName-$messageType-${Random().nextInt()}")

    var responded = false

    suspend fun respond(responseBody: Serializable? = null) {
        if (responded)
            throw AlreadyRespondedException("You've already responded [protocolName: $protocolName, messageType: $messageType, threadId: $threadId]")

        val serializedPayload = SerializationUtils.toBytes(responseBody)
        val packet = ProtocolPacket(threadId, ProtocolPacketFlag.RESPONSE, protocolName, messageType, serializedPayload)

        respondHandler(packet, sender)

        logger.debug { "Respond [protocolName: $protocolName, messageType: $messageType, recipient: $sender, threadId: $threadId]" }

        responded = true
    }
}

class AlreadyRespondedException(message: String) : RuntimeException(message)

class Handler(val protocolName: String, val messageType: String, val body: suspend Handler.() -> Unit) {
    lateinit var request: Request

    private val logger = KotlinLogging.logger("Handler-$protocolName-$messageType-${Random().nextInt()}")

    lateinit var sendHandler: SendHandler
    internal fun applySendHandler(handler: SendHandler) {
        sendHandler = handler
    }

    internal lateinit var receiveHandler: ReceiveHandler
    internal fun applyReceivehandler(handler: ReceiveHandler) {
        receiveHandler = handler
    }

    private suspend fun send(
        messageType: String,
        recipient: InetSocketAddress,
        messageBody: Serializable? = null,
        protocolName: String = this.protocolName
    ) {
        val serializedBody = SerializationUtils.toBytes(messageBody)
        val packet = ProtocolPacket(protocolName = protocolName, messageType = messageType, payload = serializedBody)

        sendHandler(packet, recipient)

        logger.debug { "Sent request [protocolName: $protocolName, messageType: $messageType, recipient: $recipient]" }
    }

    private suspend fun <T> sendAndReceive(
        messageType: String,
        recipient: InetSocketAddress,
        responseClazz: Class<T>,
        messageBody: Serializable? = null,
        protocolName: String = this.protocolName,
        timeoutMs: Long = 10000
    ): T {

        val serializedBody = SerializationUtils.toBytes(messageBody)
        val threadId = Random().nextLong()
        val packet = ProtocolPacket(
            protocolThreadId = threadId,
            protocolName = protocolName,
            messageType = messageType,
            payload = serializedBody
        )

        sendHandler(packet, recipient)

        logger.debug { "Sent request [protocolName: $protocolName, messageType: $messageType, recipient: $recipient, threadId: $threadId], waiting for response..." }

        val responsePacket = withTimeoutOrNull(timeoutMs) {
            var response: ProtocolPacket?

            while (true) {
                response = receiveHandler(threadId)

                if (response != null)
                    break
                else
                    delay(1)
            }

            response
        } ?: throw TimeoutException("Response timeout for threadId: $threadId elapsed")

        logger.debug { "Received response [protocolName: $protocolName, messageType: $messageType, recipient: $recipient, threadId: $threadId]" }

        return SerializationUtils.toAny(responsePacket.payload, responseClazz)
    }
}

fun protocol(name: String, init: Protocol.() -> Unit): Protocol {
    val protocol = Protocol(name)
    protocol.init()
    return protocol
}
