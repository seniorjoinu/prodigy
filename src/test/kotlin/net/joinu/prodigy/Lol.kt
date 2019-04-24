package net.joinu.prodigy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress


object ChatMessageType {
    const val SHARED = 0
    const val DIRECT = 1
}

data class ChatMessage(val from: String, val message: String, val type: Int)

class SimpleChatProtocol(val nickname: String) : AbstractProtocol() {
    val roomMembers = mutableMapOf<String, InetSocketAddress>()

    override val protocol = protocol("CHAT") {
        on("message") {
            val message = request.getPayloadAs<ChatMessage>()

            onMessage(message)
        }

        on("ask to join") {
            // we can whitelist here
            askToJoinSuccess(request.sender, roomMembers)
        }

        on("ask to join success") {
            roomMembers.putAll(request.getPayloadAs())
            onAskToJoinSuccess()
        }

        on("join") {
            val nickname = request.getPayloadAs<String>()
            roomMembers[nickname] = request.sender

            onJoin(nickname)
        }

        on("leave") {
            val nickname = request.getPayloadAs<String>()

            roomMembers.remove(nickname)

            onLeave(nickname)
        }
    }

    suspend fun send(message: String) {
        val messageObj = ChatMessage(nickname, message, ChatMessageType.SHARED)

        roomMembers.forEach {
            sendMessage("message", messageObj, it.value)
        }
    }

    suspend fun sendDirect(message: String, to: String) {
        val messageObj = ChatMessage(nickname, message, ChatMessageType.DIRECT)
        val recipient = roomMembers[to]!!

        sendMessage("message", messageObj, recipient)
    }

    suspend fun join() {
        roomMembers.forEach {
            sendMessage("join", nickname, it.value)
        }
    }

    suspend fun leave() {
        roomMembers.forEach {
            sendMessage("leave", nickname, it.value)
        }
    }

    suspend fun askToJoin(gateway: InetSocketAddress) {
        sendMessage("ask to join", null, gateway)
    }

    suspend fun askToJoinSuccess(recipient: InetSocketAddress, roomMembers: Map<String, InetSocketAddress>) {
        sendMessage("ask to join success", roomMembers, recipient)
    }

    lateinit var onMessage: suspend (message: ChatMessage) -> Unit
    lateinit var onJoin: suspend (nickname: String) -> Unit
    lateinit var onLeave: suspend (nickname: String) -> Unit
    lateinit var onAskToJoinSuccess: suspend () -> Unit
}


fun main() {
    System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG")

    runBlocking {
        val addr1 = InetSocketAddress("localhost", 1337)
        val addr2 = InetSocketAddress("localhost", 1338)
        val addr3 = InetSocketAddress("localhost", 1339)

        val runner1 = ProtocolRunner(addr1)
        val runner2 = ProtocolRunner(addr2)
        val runner3 = ProtocolRunner(addr3)

        val chat1 = SimpleChatProtocol("John Smith")
        val chat2 = SimpleChatProtocol("John Doe")
        val chat3 = SimpleChatProtocol("John Snow")

        chat1.roomMembers[chat1.nickname] = addr1
        chat2.roomMembers[chat2.nickname] = addr2
        chat3.roomMembers[chat3.nickname] = addr3

        chat2.onAskToJoinSuccess = suspend { chat2.join() }
        chat3.onAskToJoinSuccess = suspend { chat3.join() }

        runner1.registerProtocol(chat1)
        runner2.registerProtocol(chat2)
        runner3.registerProtocol(chat3)

        launch(Dispatchers.IO) { runner1.run() }
        launch(Dispatchers.IO) { runner2.run() }
        launch(Dispatchers.IO) { runner3.run() }

        // TODO: add responses
        chat2.askToJoin(addr1)
        chat3.askToJoin(addr1)

        while (true) {
            if (with(ExampleProtocol) { kekReceived && kekSent && lolReceived && lolSent }) {
                runner1.close()
                runner2.close()
                break
            }

            delay(10)
        }
    }
}
