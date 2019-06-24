package net.joinu.prodigy

import mu.KotlinLogging
import java.io.Serializable
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.CompletableFuture


abstract class Sender {
    lateinit var sendHandler: SendHandler
    internal fun applySendHandler(handler: SendHandler) {
        sendHandler = handler
    }

    lateinit var receiveHandler: ReceiveHandler
    internal fun applyReceiveHandler(handler: ReceiveHandler) {
        receiveHandler = handler
    }

    private val logger = KotlinLogging.logger("Sender-${Random().nextInt()}")

    protected fun send(
        protocolName: String,
        messageType: String,
        recipient: InetSocketAddress,
        messageBody: Serializable? = null
    ): CompletableFuture<Unit> {
        val serializedBody = SerializationUtils.toBytes(messageBody)
        val packet = ProtocolPacket(protocolName = protocolName, messageType = messageType, payload = serializedBody)

        val future = sendHandler(packet, recipient)

        logger.debug { "Sent request [protocolName: $protocolName, messageType: $messageType, recipient: $recipient]" }

        return future
    }

    protected inline fun <reified T> sendAndReceive(
        protocolName: String,
        messageType: String,
        recipient: InetSocketAddress,
        messageBody: Serializable? = null
    ) = sendAndReceive(protocolName, messageType, recipient, T::class.java, messageBody)

    protected fun <T> sendAndReceive(
        protocolName: String,
        messageType: String,
        recipient: InetSocketAddress,
        responseClazz: Class<T>,
        messageBody: Serializable? = null
    ): CompletableFuture<T> {
        val serializedBody = SerializationUtils.toBytes(messageBody)
        val threadId = Random().nextLong()
        val packet = ProtocolPacket(
            protocolThreadId = threadId,
            protocolName = protocolName,
            messageType = messageType,
            payload = serializedBody
        )

        val receiveFuture = receiveHandler(threadId)
        val sendFuture = sendHandler(packet, recipient)

        return sendFuture
            .thenCombine(receiveFuture) { _, p -> p }
            .thenApply { responsePacket -> SerializationUtils.toAny(responsePacket.payload, responseClazz) }
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

    fun respond(responseBody: Serializable? = null): CompletableFuture<Unit> {
        if (responded)
            throw AlreadyRespondedException("You've already responded [protocolName: $protocolName, messageType: $messageType, threadId: $threadId]")

        val serializedPayload = SerializationUtils.toBytes(responseBody)
        val packet = ProtocolPacket(threadId, ProtocolPacketFlag.RESPONSE, protocolName, messageType, serializedPayload)

        val respondFuture = respondHandler(packet, sender).thenApply { responded = true }

        logger.debug { "Respond [protocolName: $protocolName, messageType: $messageType, recipient: $sender, threadId: $threadId]" }

        return respondFuture
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
