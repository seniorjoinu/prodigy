package net.joinu.prodigy

import java.net.InetSocketAddress


abstract class AbstractProtocol {
    abstract val protocol: Protocol

    lateinit var sendHandler: SendHandler
    internal fun applySendHandler(handler: SendHandler) {
        sendHandler = handler
        protocol.applySendHandler(handler)
    }

    protected suspend fun sendCrossProtocolMessage(
        protocolName: String,
        messageType: String,
        messageBody: Any,
        recipient: InetSocketAddress
    ) {
        val serializedBody = SerializationUtils.toBytes(messageBody)
        val packet = ProtocolPacket(protocolName, messageType, serializedBody)

        sendHandler(packet, recipient)
    }

    protected suspend fun sendMessage(
        messageType: String,
        messageBody: Any,
        recipient: InetSocketAddress
    ) = sendCrossProtocolMessage(protocol.name, messageType, messageBody, recipient)
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
}

class Request(val sender: InetSocketAddress, val payload: ByteArray) {
    fun <T> getPayloadAs(clazz: Class<T>): T = SerializationUtils.toAny(payload, clazz)
    inline fun <reified T> getPayloadAs(): T = SerializationUtils.toAny(payload)
}

class Handler(val protocolName: String, val type: String, val body: suspend Handler.() -> Unit) {
    lateinit var request: Request

    lateinit var sendHandler: SendHandler
    internal fun applySendHandler(handler: SendHandler) {
        sendHandler = handler
    }

    private suspend fun sendCrossProtocolMessage(
        protocolName: String,
        messageType: String,
        messageBody: Any,
        recipient: InetSocketAddress
    ) {
        val serializedBody = SerializationUtils.toBytes(messageBody)
        val packet = ProtocolPacket(protocolName, messageType, serializedBody)

        sendHandler(packet, recipient)
    }

    private suspend fun sendMessage(
        messageType: String,
        messageBody: Any,
        recipient: InetSocketAddress
    ) = sendCrossProtocolMessage(protocolName, messageType, messageBody, recipient)
}

fun protocol(name: String, init: Protocol.() -> Unit): Protocol {
    val protocol = Protocol(name)
    protocol.init()
    return protocol
}