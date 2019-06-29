package net.joinu.prodigy

import mu.KotlinLogging
import java.io.Serializable
import java.net.InetSocketAddress
import java.util.*


abstract class Sender {
    companion object {
        var count = 0
    }

    lateinit var sendHandler: SendHandler
    internal fun applySendHandler(handler: SendHandler) {
        sendHandler = handler
    }

    lateinit var receiveHandler: ReceiveHandler
    internal fun applyReceiveHandler(handler: ReceiveHandler) {
        receiveHandler = handler
    }

    private val logger = KotlinLogging.logger("Sender-${++count}")

    protected suspend fun send(
        protocolName: String,
        messageType: String,
        recipient: InetSocketAddress,
        messageBody: Serializable? = null
    ) {
        val serializedBody = SerializationUtils.toBytes(messageBody)
        val packet = ProtocolPacket(protocolName = protocolName, messageType = messageType, payload = serializedBody)

        sendHandler(packet, recipient)

        logger.debug { "Sent request [protocolName: $protocolName, messageType: $messageType, recipient: $recipient]" }
    }

    protected suspend inline fun <reified T> exchange(
        protocolName: String,
        messageType: String,
        recipient: InetSocketAddress,
        messageBody: Serializable? = null
    ) = exchange(protocolName, messageType, recipient, T::class.java, messageBody)

    protected suspend fun <T> exchange(
        protocolName: String,
        messageType: String,
        recipient: InetSocketAddress,
        responseClazz: Class<T>,
        messageBody: Serializable? = null
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

        logger.debug { "Sent request [protocolName: $protocolName, messageType: $messageType, recipient: $recipient], waiting for response..." }

        val responsePacket = receiveHandler(threadId)

        logger.debug { "Received response for request [protocolName: $protocolName, messageType: $messageType, recipient: $recipient]" }

        return SerializationUtils.toAny(responsePacket.payload, responseClazz)
    }
}

abstract class AbstractProtocol : Sender() {
    abstract val protocol: Protocol
}

class Protocol(var protocolName: String) {
    val handlers = hashMapOf<String, Handler>()

    fun on(type: String, body: HandlerBody) {
        val handler = Handler(protocolName, type, body)
        handlers[type] = handler
    }

    internal fun applySendHandler(handler: SendHandler) {
        handlers.values.forEach { it.applySendHandler(handler) }
    }

    internal fun applyReceiveHandler(handler: ReceiveHandler) {
        handlers.values.forEach { it.applyReceiveHandler(handler) }
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
        responded = true

        logger.debug { "Respond [protocolName: $protocolName, messageType: $messageType, recipient: $sender, threadId: $threadId]" }
    }
}

class AlreadyRespondedException(message: String) : RuntimeException(message)

class Handler(val protocolName: String, val messageType: String, val body: HandlerBody) : Sender() {
    lateinit var request: Request
}

fun protocol(name: String, init: Protocol.() -> Unit): Protocol {
    val protocol = Protocol(name)
    protocol.init()
    return protocol
}
