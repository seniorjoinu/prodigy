package net.joinu.prodigy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress


object ExampleProtocol : AbstractProtocol("EXAMPLE") {
    var lolSent = false
    var lolReceived = false
    var kekSent = false
    var kekReceived= false

    @On("lol")
    fun onLol(from: InetSocketAddress, message: Int) {
        println("Lol $message from $from")

        ExampleProtocol.lolReceived = true
    }

    @On("kek")
    fun onKek() {
        println("Kek")

        ExampleProtocol.kekReceived = true
    }

    suspend fun lol(recipient: InetSocketAddress) {
        send("lol", 123, recipient)
        lolSent = true
    }

    suspend fun kek(recipient: InetSocketAddress) {
        send("kek", 123, recipient)
        kekSent = true
    }
}

fun main() {
    System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG")

    runBlocking {
        val addr1 = InetSocketAddress("localhost", 1337)
        val addr2 = InetSocketAddress("localhost", 1338)

        val runner1 = ProtocolRunner(addr1)
        val runner2 = ProtocolRunner(addr2)

        runner1.registerProtocol(ExampleProtocol)
        runner2.registerProtocol(ExampleProtocol)

        launch(Dispatchers.IO) { runner1.run() }
        launch(Dispatchers.IO) { runner2.run() }

        launch { ExampleProtocol.lol(addr1) }
        launch { ExampleProtocol.kek(addr2) }

        while (true) {
            if (with(ExampleProtocol) { (kekReceived && kekSent && lolReceived && lolSent) }) {
                runner1.close()
                runner2.close()
                break
            }

            delay(10)
        }
    }
}
