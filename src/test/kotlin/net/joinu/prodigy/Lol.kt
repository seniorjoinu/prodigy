package net.joinu.prodigy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Serializable
import java.net.InetSocketAddress


object ChatMessageType {
    const val SHARED = 0
    const val DIRECT = 1
}

data class ChatMessage(val from: String, val message: String, val type: Int) : Serializable

class SimpleChatProtocol(val nickname: String) : AbstractProtocol() {
    val roomMembers = HashMap<String, InetSocketAddress>()

    override val protocol = protocol("CHAT") {
        on("message") {
            val message = request.getPayloadAs<ChatMessage>()

            onMessage(message)
        }

        on("ask to join") {
            // we can whitelist here

            request.respond(roomMembers)
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
            send("CHAT", "message", it.value, messageObj)
        }
    }

    suspend fun sendDirect(message: String, to: String) {
        val messageObj = ChatMessage(nickname, message, ChatMessageType.DIRECT)
        val recipient = roomMembers[to]!!

        send("CHAT", "message", recipient, messageObj)
    }

    suspend fun join() {
        roomMembers.filter { it.key != nickname }.forEach {
            send("CHAT", "join", it.value, nickname)
        }
    }

    suspend fun leave() {
        roomMembers.forEach {
            send("CHAT", "leave", it.value, nickname)
        }
    }

    suspend fun askToJoin(gateway: InetSocketAddress) {
        val roomMembers = sendAndReceive<HashMap<String, InetSocketAddress>>("CHAT", "ask to join", gateway)
        this.roomMembers.putAll(roomMembers)
    }

    var onMessage: suspend (message: ChatMessage) -> Unit = { }
    var onJoin: suspend (nickname: String) -> Unit = { }
    var onLeave: suspend (nickname: String) -> Unit = { }
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

        val nick1 = "John Smith"
        val nick2 = "John Doe"
        val nick3 = "John Snow"

        val chat1 = SimpleChatProtocol(nick1)
        val chat2 = SimpleChatProtocol(nick2)
        val chat3 = SimpleChatProtocol(nick3)

        chat1.onMessage = { println(it) }
        chat2.onMessage = { println(it) }
        chat3.onMessage = { println(it) }

        chat1.roomMembers[chat1.nickname] = addr1
        chat2.roomMembers[chat2.nickname] = addr2
        chat3.roomMembers[chat3.nickname] = addr3

        runner1.registerProtocol(chat1)
        runner2.registerProtocol(chat2)
        runner3.registerProtocol(chat3)

        val a = launch(Dispatchers.IO) { runner1.run() }
        val b = launch(Dispatchers.IO) { runner2.run() }
        val c = launch(Dispatchers.IO) { runner3.run() }

        chat2.askToJoin(addr1)
        chat2.join()

        chat3.askToJoin(addr1)
        chat3.join()

        delay(2000)

        chat1.send("Hey, guys!")
        chat2.send("What?")
        chat1.send("Isn't this cool?")
        chat3.send("Yes ofc")
        chat3.sendDirect("Actually not, haha", nick2)

        chat2.leave()
        chat3.leave()

        runner1.close()
        runner2.close()
        runner3.close()
    }
}
