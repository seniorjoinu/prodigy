package net.joinu.prodigy

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.joinu.nioudp.MAX_CHUNK_SIZE_BYTES
import net.joinu.rudp.ConfigurableRUDPSocket
import java.io.Serializable
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*


typealias SendHandler = suspend (packet: ProtocolPacket, recipient: InetSocketAddress) -> Unit

@Target(AnnotationTarget.FUNCTION)
@SuppressWarnings("unused")
annotation class On(val messageType: String)

abstract class AbstractProtocol(val name: String) {
    internal var sendHandler: SendHandler? = null
    internal fun applySendHandler(handler: SendHandler) {
        sendHandler = handler
    }

    internal fun getHandlers(): Map<String, Method> {
        val annotatedMethods = this::class.java.declaredMethods.filter { it.isAnnotationPresent(On::class.java) }
        return annotatedMethods.associate { it.getAnnotation(On::class.java).messageType to it }
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

    fun registerProtocols(vararg protocols: AbstractProtocol) {
        protocols.forEach { this.registerProtocol(it) }
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

            val handler = protocol.getHandlers()[packet.messageType]

            if (handler == null) {
                logger.debug { "Received a message for unknown messageType: ${packet.messageType} for protocol: ${packet.protocolName}" }
                return@onMessage
            }

            var addressAssigned = false
            var payloadAssigned = false
            if (packet.messageType == "lol") {
                println("")
            }
            val parameters = handler.parameterTypes.map {
                return@map if (InetSocketAddress::class.java.isAssignableFrom(it) && !addressAssigned) {
                    addressAssigned = true
                    from
                } else if (!payloadAssigned) {
                    payloadAssigned = true
                    SerializationUtils.toAny(packet.payload, it)
                } else {
                    throw IllegalArgumentException("Invalid parameter with type: ${it::class.java} in @On method: ${handler.name} in class ${this::class.java}")
                }
            }.toTypedArray()

            handler.invoke(protocol, *parameters)
        }

        socket.listen()
    }

    suspend fun close() {
        socket.close()
    }
}

data class ProtocolPacket(
    var protocolName: String = "",
    var messageType: String = "",
    var payload: ByteArray = ByteArray(0)
) : Serializable

internal object SerializationUtils {
    val mapper by lazy {
        val m = Kryo()
        m.register(ProtocolPacket::class.java)
        m.register(ByteArray::class.java)
        m
    }

    fun registerClass(clazz: Class<out Any>) = mapper.register(clazz)

    // TODO: handle null
    fun toBytes(obj: Any): ByteArray {
        val output = Output(ByteArray(MAX_CHUNK_SIZE_BYTES))
        mapper.writeObjectOrNull(output, obj, obj::class.java)

        return output.toBytes()
    }

    fun <T> toAny(bytes: ByteArray, clazz: Class<T>): T {
        return mapper.readObjectOrNull(Input(bytes), clazz)
    }

    inline fun <reified T : Any> toAny(bytes: ByteArray): T =
        toAny(bytes, T::class.java)
}
