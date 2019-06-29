package net.joinu.prodigy

import kotlinx.coroutines.*
import mu.KotlinLogging
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.net.InetSocketAddress


object ChatMessageType {
    const val SHARED = 0
    const val DIRECT = 1
}

data class ChatMessage(val from: String, val message: String, val type: Int) : Serializable

class SimpleChatProtocol(val nickname: String, address: InetSocketAddress) : AbstractProtocol() {
    val roomMembers = HashMap<String, InetSocketAddress>()

    private val logger = KotlinLogging.logger("SimpleChatProtocol")

    init {
        roomMembers[nickname] = address
    }

    var onMessage: suspend (message: ChatMessage) -> Unit = { println(it) }

    override val protocol = protocol("CHAT") {
        on("message") {
            val message = request.getPayloadAs<ChatMessage>()

            logger.info { "Got on(message) request" }

            onMessage(message)
        }

        on("ask to join") {
            // we can whitelist here

            logger.info { "Got on(ask to join) request" }

            request.respond(roomMembers)
        }

        on("join") {
            val nickname = request.getPayloadAs<String>()
            roomMembers[nickname] = request.sender

            logger.info { "Got on(join) request" }
        }

        on("leave") {
            val nickname = request.getPayloadAs<String>()

            logger.info { "Got on(leave) request" }

            roomMembers.remove(nickname)
        }
    }

    suspend fun send(message: String) = coroutineScope {
        val messageObj = ChatMessage(nickname, message, ChatMessageType.SHARED)

        roomMembers
            .map { async { send("CHAT", "message", it.value, messageObj) } }
            .awaitAll()
    }

    suspend fun sendDirect(message: String, to: String) {
        val messageObj = ChatMessage(nickname, message, ChatMessageType.DIRECT)
        val recipient = roomMembers[to]!!

        send("CHAT", "message", recipient, messageObj)
    }

    suspend fun join() = coroutineScope {
        roomMembers
            .filter { it.key != nickname }
            .map { async { send("CHAT", "join", it.value, nickname) } }
            .awaitAll()
    }

    suspend fun leave() = coroutineScope {
        roomMembers
            .filter { it.key != nickname }
            .map { async { send("CHAT", "leave", it.value, nickname) } }
            .awaitAll()
    }

    suspend fun askToJoin(gateway: InetSocketAddress) {
        val roomMembers = exchange<HashMap<String, InetSocketAddress>>("CHAT", "ask to join", gateway)

        this.roomMembers.putAll(roomMembers)
    }
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

        val chat1 = SimpleChatProtocol("John Smith", addr1)
        val chat2 = SimpleChatProtocol("John Doe", addr2)
        val chat3 = SimpleChatProtocol("John Snow", addr3)

        runner1.registerProtocol(chat1)
        runner2.registerProtocol(chat2)
        runner3.registerProtocol(chat3)

        runBlocking {
            launch { runner1.runSuspending() }
            launch { runner2.runSuspending() }
            launch { runner3.runSuspending() }

            chat2.askToJoin(addr1)
            chat2.join()
            chat3.askToJoin(addr1)
            chat3.join()
            chat1.send("Hey, guys!")
            chat2.send("What?")
            chat1.send("Isn't this cool?")
            chat3.send("Yes ofc")
            chat3.sendDirect("Actually not, haha", "John Doe")
            chat2.leave()
            chat3.leave()

            coroutineContext.cancelChildren()
        }

        runner1.close()
        runner2.close()
        runner3.close()
    }
}
