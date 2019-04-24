package net.joinu.prodigy

import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.TimeoutException


abstract class AbstractProtocol {
    abstract val protocol: Protocol

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
        messageBody: Any? = null,
        protocolName: String = protocol.name
    ) {
        val serializedBody = SerializationUtils.toBytes(messageBody)
        val packet = ProtocolPacket(protocolName = protocolName, messageType = messageType, payload = serializedBody)

        sendHandler(packet, recipient)
    }

    // TODO: add reified

    protected suspend fun <T> sendAndReceive(
        messageType: String,
        recipient: InetSocketAddress,
        responseClazz: Class<T>,
        messageBody: Any? = null,
        protocolName: String = protocol.name,
        timeoutMs: Long = 10000
    ): T {
        val serializedBody = SerializationUtils.toBytes(messageBody)
        val threadId = UUID.randomUUID()
        val packet = ProtocolPacket(
            protocolThreadId = threadId,
            protocolName = protocolName,
            messageType = messageType,
            payload = serializedBody
        )

        sendHandler(packet, recipient)

        val responsePacket = withTimeoutOrNull(timeoutMs) {
            var response: ProtocolPacket?

            while (true) {
                response = receiveHandler(threadId)

                if (response != null)
                    break
            }

            response
        } ?: throw TimeoutException("Response timeout for threadId: $threadId elapsed")

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
    val threadId: UUID,
    val messageType: String,
    val protocolName: String
) {
    fun <T> getPayloadAs(clazz: Class<T>): T = SerializationUtils.toAny(payload, clazz)
    inline fun <reified T> getPayloadAs(): T = SerializationUtils.toAny(payload)

    internal lateinit var respondHandler: SendHandler
    internal fun applyRespondHandler(handler: SendHandler) {
        respondHandler = handler
    }

    var responded = false

    suspend fun respond(responseBody: Any? = null) {
        if (responded)
            throw AlreadyRespondedException("You've already responded [protocolName: $protocolName, messageType: $messageType, threadId: $threadId]")

        val serializedPayload = SerializationUtils.toBytes(responseBody)
        val packet = ProtocolPacket(threadId, ProtocolPacketFlag.RESPONSE, protocolName, messageType, serializedPayload)

        respondHandler(packet, sender)

        responded = true
    }
}

class AlreadyRespondedException(message: String) : RuntimeException(message)

class Handler(val protocolName: String, val type: String, val body: suspend Handler.() -> Unit) {
    lateinit var request: Request

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
        messageBody: Any? = null,
        protocolName: String = this.protocolName
    ) {
        val serializedBody = SerializationUtils.toBytes(messageBody)
        val packet = ProtocolPacket(protocolName = protocolName, messageType = messageType, payload = serializedBody)

        sendHandler(packet, recipient)
    }

    private suspend fun <T> sendAndReceive(
        messageType: String,
        recipient: InetSocketAddress,
        responseClazz: Class<T>,
        messageBody: Any? = null,
        protocolName: String = this.protocolName,
        timeoutMs: Long = 10000
    ): T {

        val serializedBody = SerializationUtils.toBytes(messageBody)
        val threadId = UUID.randomUUID()
        val packet = ProtocolPacket(
            protocolThreadId = threadId,
            protocolName = protocolName,
            messageType = messageType,
            payload = serializedBody
        )

        sendHandler(packet, recipient)

        val responsePacket = withTimeoutOrNull(timeoutMs) {
            var response: ProtocolPacket?

            while (true) {
                response = receiveHandler(threadId)

                if (response != null)
                    break
            }

            response
        } ?: throw TimeoutException("Response timeout for threadId: $threadId elapsed")

        return SerializationUtils.toAny(responsePacket.payload, responseClazz)
    }
}

fun protocol(name: String, init: Protocol.() -> Unit): Protocol {
    val protocol = Protocol(name)
    protocol.init()
    return protocol
}
