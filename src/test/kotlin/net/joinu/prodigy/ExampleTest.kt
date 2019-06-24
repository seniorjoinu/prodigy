package net.joinu.prodigy

import org.junit.jupiter.api.Test
import java.io.Serializable
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture


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

    fun send(message: String): CompletableFuture<Void> {
        val messageObj = ChatMessage(nickname, message, ChatMessageType.SHARED)

        val futures = roomMembers.map { send("CHAT", "message", it.value, messageObj) }

        return CompletableFuture.allOf(*(futures.toTypedArray()))
    }

    fun sendDirect(message: String, to: String): CompletableFuture<Unit> {
        val messageObj = ChatMessage(nickname, message, ChatMessageType.DIRECT)
        val recipient = roomMembers[to]!!

        return send("CHAT", "message", recipient, messageObj)
    }

    fun join(): CompletableFuture<Void> {
        val futures = roomMembers
            .filter { it.key != nickname }
            .map { send("CHAT", "join", it.value, nickname) }

        return CompletableFuture.allOf(*(futures.toTypedArray()))
    }

    fun leave(): CompletableFuture<Void> {
        val futures = roomMembers
            .filter { it.key != nickname }
            .map { send("CHAT", "leave", it.value, nickname) }

        return CompletableFuture.allOf(*(futures.toTypedArray()))
    }

    fun askToJoin(gateway: InetSocketAddress) =
        sendAndReceive<HashMap<String, InetSocketAddress>>("CHAT", "ask to join", gateway)
            .thenAccept { this.roomMembers.putAll(it) }


    var onMessage: (message: ChatMessage) -> Unit = { }
    var onJoin: (nickname: String) -> Unit = { }
    var onLeave: (nickname: String) -> Unit = { }
}


class ExampleTest {

    init {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG")
    }

    @Test
    fun `chat protocol works well`() {
        val addr1 = InetSocketAddress("localhost", 1337)
        val addr2 = InetSocketAddress("localhost", 1338)
        val addr3 = InetSocketAddress("localhost", 1339)

        val runner1 = ProtocolRunner(RUDPNetworkProvider()).also { it.bind(addr1) }
        val runner2 = ProtocolRunner(RUDPNetworkProvider()).also { it.bind(addr2) }
        val runner3 = ProtocolRunner(RUDPNetworkProvider()).also { it.bind(addr3) }

        val nick1 = "John Smith"
        val nick2 = "John Doe"
        val nick3 = "John Snow"

        val chat1 = SimpleChatProtocol(nick1)
        val chat2 = SimpleChatProtocol(nick2)
        val chat3 = SimpleChatProtocol(nick3)

        chat1.onMessage = { println(it) }
        chat2.onMessage = { println(it) }
        chat3.onMessage = { println(it) }

        var joined = 0
        chat1.onJoin = { joined++ }

        var left = 0
        chat1.onLeave = { left++ }

        chat1.roomMembers[chat1.nickname] = addr1
        chat2.roomMembers[chat2.nickname] = addr2
        chat3.roomMembers[chat3.nickname] = addr3

        runner1.registerProtocol(chat1)
        runner2.registerProtocol(chat2)
        runner3.registerProtocol(chat3)

        chat2.askToJoin(addr1)
            .thenCompose { chat2.join() }
            .thenCompose { chat3.askToJoin(addr1) }
            .thenCompose { chat3.join() }
            .thenCompose { chat1.send("Hey, guys!") }
            .thenCompose { chat2.send("What?") }
            .thenCompose { chat1.send("Isn't this cool?") }
            .thenCompose { chat3.send("Yes ofc") }
            .thenCompose { chat3.sendDirect("Actually not, haha", nick2) }
            .thenCompose { chat2.leave() }
            .thenCompose { chat3.leave() }

        while (joined < 2 || left < 2) {
            runner1.runOnce()
            runner2.runOnce()
            runner3.runOnce()
        }

        runner1.close()
        runner2.close()
        runner3.close()
    }
}
