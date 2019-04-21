package net.joinu.prodigy

import java.net.InetSocketAddress


data class ProtocolPacket(
    var protocolName: String = "",
    var messageType: String = "",
    var payload: ByteArray = ByteArray(0)
) {
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

typealias SendHandler = suspend (packet: ProtocolPacket, recipient: InetSocketAddress) -> Unit

@Target(AnnotationTarget.FUNCTION)
@SuppressWarnings("unused")
annotation class On(val messageType: String)