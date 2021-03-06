package net.joinu.prodigy

import java.io.Serializable
import java.net.InetSocketAddress
import java.util.*

/**
 * Represents packet type (now support only request-response model)
 */
object ProtocolPacketFlag {
    const val REQUEST = 0
    const val RESPONSE = 1
}

/**
 * Structure that is used to represent any type of data flowing through network.
 *
 * @param protocolThreadId [Long] - some unique id used for request-response logic TODO: switch to UUID
 * @param protocolFlag [Int] - [ProtocolPacketFlag]
 * @param protocolName [String] - packet protocol name
 * @param messageType [String] - packet protocol message type
 * @param payload [ByteArray] - raw data to transmit
 */
data class ProtocolPacket(
    var protocolThreadId: Long = Random().nextLong(),
    var protocolFlag: Int = ProtocolPacketFlag.REQUEST,
    var protocolName: String = "",
    var messageType: String = "",
    var payload: ByteArray = ByteArray(0)
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProtocolPacket

        if (protocolName != other.protocolName) return false
        if (messageType != other.messageType) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = protocolName.hashCode()
        result = 31 * result + messageType.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

typealias SendHandler = suspend (packet: ProtocolPacket, to: InetSocketAddress) -> Unit
typealias ReceiveHandler = suspend (threadId: Long) -> ProtocolPacket

class AlreadyRespondedException(message: String) : RuntimeException(message)
