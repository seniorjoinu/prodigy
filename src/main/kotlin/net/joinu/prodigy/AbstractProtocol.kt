package net.joinu.prodigy

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.joinu.rudp.ConfigurableRUDPSocket
import org.nustaq.serialization.FSTConfiguration
import java.io.Serializable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import kotlin.reflect.KClass


typealias Handler = (from: InetSocketAddress, message: Any) -> Unit
typealias SendHandler = suspend (packet: ProtocolPacket, recipient: InetSocketAddress) -> Unit

abstract class AbstractProtocol(val name: String, vararg handlerPairs: Pair<String, Handler>) {
    internal val handlers: MutableMap<String, Handler> = hashMapOf()

    init {
        handlerPairs.forEach { handlers.putIfAbsent(it.first, it.second) }
    }

    internal var sendHandler: SendHandler? = null
    internal fun applySendHandler(handler: SendHandler) {
        sendHandler = handler
    }

    protected suspend fun send(messageType: String, messageBody: Any, recipient: InetSocketAddress) {
        if (sendHandler == null)
            throw IllegalStateException("Send handler is not set")
        else {
            val serializedBody = SerializationUtils.toBytes(messageBody)
            val packet = ProtocolPacket(name, messageType, serializedBody)

            sendHandler!!(packet, recipient)
        }
    }
}

object ProtocolRunnerState {
    const val NEW = 0
    const val RUNNING = 1
    const val CLOSED = 2
}

class ProtocolRunner(bindAddress: InetSocketAddress) {
    val protocols = hashMapOf<String, AbstractProtocol>()
    val socket = ConfigurableRUDPSocket(1400) // TODO: handle send from controllers and from body
    var state = ProtocolRunnerState.NEW

    private val logger = KotlinLogging.logger("ProtocolRunner-${Random().nextInt()}")

    init {
        runBlocking {
            socket.bind(bindAddress)
        }
    }

    fun registerProtocols(vararg protocol: AbstractProtocol) {
        protocols.values.forEach { this.registerProtocol(it) }
    }

    fun registerProtocol(protocol: AbstractProtocol) {
        protocols[protocol.name] = protocol

        protocol.applySendHandler { packet, recipient ->
            val serializedPacket = SerializationUtils.toBytes(packet)
            val buffer = ByteBuffer.allocateDirect(serializedPacket.size)
            buffer.put(serializedPacket)
            buffer.flip()

            socket.send(buffer, recipient, 5000, { 50 }, { 1400 })
        }
    }

    suspend fun run() {
        socket.onMessage { buffer, from ->
            val bytes = ByteArray(buffer.limit())
            buffer.get(bytes)

            val packet: ProtocolPacket = SerializationUtils.toAny(bytes)
            val protocol = protocols[packet.protocolName]

            if (protocol == null) {
                logger.debug { "Received a message for unknown protocol: ${packet.protocolName}" }
                return@onMessage
            }

            val handler = protocol.handlers[packet.messageType]

            if (handler == null) {
                logger.debug { "Received a message for unknown messageType: ${packet.messageType} for protocol: ${packet.protocolName}" }
                return@onMessage
            }

            val payloadType = handler::class.java
                .declaredMethods
                .first {
                    !it.returnType.isAssignableFrom(Void::class.java)
                }
                .parameterTypes[1]
            val deserializedPayload = SerializationUtils.toAny(packet.payload, payloadType)

            handler.invoke(from, deserializedPayload)
        }

        socket.listen()
    }

    suspend fun close() {
        socket.close()
    }
}

data class ProtocolPacket(val protocolName: String, val messageType: String, val payload: ByteArray) : Serializable

internal object SerializationUtils {
    val mapper by lazy { FSTConfiguration.createDefaultConfiguration() }

    fun <T : Any> registerClass(vararg clazz: KClass<T>) = mapper.registerClass(*(clazz.map { it.java }.toTypedArray()))
    fun toBytes(obj: Any): ByteArray = mapper.asByteArray(obj)
    fun <T : Any> toAny(bytes: ByteArray, clazz: Class<T>): T = clazz.cast(mapper.asObject(bytes))
    inline fun <reified T : Any> toAny(bytes: ByteArray): T =
        toAny(bytes, T::class.java)
}
