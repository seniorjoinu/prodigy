package net.joinu.prodigy

import mu.KotlinLogging
import java.io.Serializable
import java.net.InetSocketAddress
import java.util.*

/**
 * Represents an entity that can send some messages and wait for response (if needed)
 */
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

    /**
     * Sends some message to some recipient. Suspends until message is delivered.
     *
     * @param protocolName [String] - message protocol name
     * @param messageType [String] - protocol message type
     * @param recipient [InetSocketAddress] - message recipient
     * @param messageBody <optional>[Serializable] - some payload
     */
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

    /**
     * Sends some message to some recipient and suspends until response of type [T] received.
     *
     * @param protocolName [String] - message protocol name
     * @param messageType [String] - protocol message type
     * @param recipient [InetSocketAddress] - message recipient
     * @param messageBody <optional>[Serializable] - some payload
     */
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
    abstract val handler: Protocol
}

/**
 * Prodigy DSL entry point. Use this to create [AbstractProtocol.handler].
 *
 * @param name [String] - protocol name
 * @param init lambda [Protocol].() -> [Unit] - initializer
 *
 * @return [Protocol] - passive side of the protocol
 */
fun protocol(name: String, init: Protocol.() -> Unit): Protocol {
    val protocol = Protocol(name)
    protocol.init()
    return protocol
}

/**
 * Represents passive side of the protocol. Creates with [protocol] DSL builder
 *
 * @param protocolName [String] - protocol name
 */
class Protocol(var protocolName: String) {
    val handlers = hashMapOf<String, Handler>()

    /**
     * Adds a handler for a particular protocol message type. Invoke it inside [protocol] DSL builder.
     *
     * @param type [String] - message type
     * @param body [HandlerBody]
     */
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

/**
 * Represents a handler for a particular protocol message type. Created by [Protocol.on] DSL builder.
 * Contains some [Request] object pre-filled with data about packet that invoked this handler.
 * Can send packets.
 */
class Handler(val protocolName: String, val messageType: String, val body: HandlerBody) : Sender() {
    lateinit var request: Request
}

typealias HandlerBody = suspend Handler.() -> Unit

/**
 * Represents some request (packet that is sent to us and we may somehow handle it). Created by [Protocol.on] DSL entry.
 */
class Request(
    val sender: InetSocketAddress,
    val payload: ByteArray,
    val threadId: Long,
    val messageType: String,
    val protocolName: String,
    internal val respondHandler: SendHandler
) {
    fun <T> getPayloadAs(clazz: Class<T>): T = SerializationUtils.toAny(payload, clazz)

    /**
     * Tries to deserialize payload into some type. Throws an exception if fails.
     */
    inline fun <reified T> getPayloadAs(): T = SerializationUtils.toAny(payload)

    private val logger = KotlinLogging.logger("Request-$protocolName-$messageType-${Random().nextInt()}")

    var responded = false

    /**
     * Responds to the [sender]. This should be invoked for [Sender.exchange] to continue.
     *
     * @param responseBody <optional>[Serializable] - some payload to send back with the response.
     */
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
