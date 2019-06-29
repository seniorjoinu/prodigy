## Prodigy - Protocol Digitalyzer
Let's you write compact and portable P2P protocols using Kotlin DSL

[![Build Status](https://travis-ci.com/seniorjoinu/prodigy.svg?branch=master)](https://travis-ci.com/seniorjoinu/prodigy)
[![](https://jitpack.io/v/seniorjoinu/prodigy.svg)](https://jitpack.io/#seniorjoinu/prodigy)

### What's in it
So you want to implement some networking for your app. Maybe you wanna make some multiplayer game or even your own fancy
messaging app with e2e encryption - if your goal is *message exchange* and not *massive data transmission*, this library 
can help you.

Let's suppose we want to create some simple chat app that allows users to join rooms and have a nice talk.

#### First of all - we have to develop our flow
Our users should be provided with the next features:
* Sending messages (shared and direct)
* Joining and leaving rooms
* Whitelisting some of the guys we don't want to participate in our room

Some assumptions made for simplicity
1. Each user can participate in only one room at a time
2. Each room is represented by one of the users - room master
3. Whitelisting logic is not what we interested in
4. Room master decides who to whitelist
5. Security, fault-tolerance and race conditions are also out of scope

So here is the flow:
1. User that wants to become a room master does nothing and waits for incoming join requests
2. Other users send ***ask to join*** message to the room master so he can allow their participation
3. Room master whitelists users asking him to join and responds them with current list of his room members
4. When received room members list user sends all of them ***join*** message (with his nickname) that means he is also 
participating now
5. When user receives ***join*** message he adds sender to his current room members list
6. All participating users can send each other messages: some of them can be sent directly to someone, some of them - 
to everyone
7. When user wants to leave the room he sends everyone ***leave*** message
8. When user receives ***leave*** message he removes the sender from his room members list

#### Let's do this

Let's keep it simple. We need some storage for our room members. Let's store their addresses by nicknames:
```kotlin
val roomMembers = HashMap<String, InetSocketAddress>()
```

After this we want to implement the *passive* side of our chat protocol - what user should do when he receives some
kind of message.
There is special builder function for this in Prodigy - `protocol()`. It takes two parameters: 
1. protocol name (string)
2. lambda with some initializing code that will be shown below.

We can name our protocol simply "CHAT" so it should be like:
```kotlin
protocol("CHAT") {
    // other passive stuff will be placed here
}
```
Now we want our protocol to actually do some work when it receives a message. Let's start with ***ask to join*** message.
Inside `protocol()`'s lambda body we can invoke another builder function - `on()`. It also takes two parameters:
1. message name (string)
2. lambda with some code that should be invoked when message with this name received

What do we want our ***ask to join*** function to do:
* it should whitelist sender (somehow check if this user is not banned in our chat)
* it should respond with our current room members list back to the sender

Inside `on()`'s lambda body we're allowed to access special `Request` object. This object has many useful things
in it but now we're interested in only one - `respond()` method. 
This method responds to the sender with some stuff.

> Inside `on()`'s lambda body there is also `send()` and `exchange()` functions, so you can even send a message to 
some other protocol from it

> Warning! Everything sent and received through Prodigy should implement `java.io.Serializable` - 
google 'FST serialization java' if you want to know the reason

Ok, here we go:
```kotlin
on("ask to join") {
    // some whitelisting logic goes here
    request.respond(roomMembers)
}
```

Not so impressed by now, huh? Let's continue. Here are other `on()` builders that we'll use:
```kotlin
// when we receive some message
on("message") {
    // we can try to retrieve some payload out of the Request object
    val message = request.getPayloadAs<ChatMessage>()
}

on("join") {
    val nickname = request.getPayloadAs<String>()
    // we can also retrieve the sender's address from the Request object
    roomMembers[nickname] = request.sender
}

on("leave") {
    val nickname = request.getPayloadAs<String>()
    roomMembers.remove(nickname)
}
```

Complete passive protocol side should look like this:
```kotlin
protocol("CHAT") {
    on("message") { ... }

    on("ask to join") { ... }

    on("join") { ... }

    on("leave") { ... }
}
```

#### Combining everything together
So we have our passive protocol side done nicely using fancy Kotlin type-safe builders syntax, but how do we actually
send somebody these messages? How do we `join` or `leave` the room? How do we keep all this stuff together in one place?

The best thing in OOP is re-usability. We can declare some class that encapsulates some complex stuff inside it
and gives us a clear simple API. Let's use OOP to solve our problem gracefully.

There is a special class in Prodigy for this - `AbstractProtocol`. Inside this class we can declare complete logic 
related to our protocol implementation. It also gives us `send()` and `exchange()` methods so we can actually
send some messages using it. Let's give it a try! Here's the complete code of our chat protocol:

```kotlin
/**
 * Chat protocol.
 *
 * @param nickname [String] - user's nickname
 * @param address [InetSocketAddress] - user's address
 */
class SimpleChatProtocol(val nickname: String, address: InetSocketAddress) : AbstractProtocol() {
    val roomMembers = HashMap<String, InetSocketAddress>()

    init {
        roomMembers[nickname] = address
    }

    override val handler = protocol("CHAT") {
        on("message") {
            val message = request.getPayloadAs<ChatMessage>()
            println(message)
        }

        on("ask to join") {
            // we can whitelist here
            request.respond(roomMembers)
        }

        on("join") {
            val nickname = request.getPayloadAs<String>()
            roomMembers[nickname] = request.sender
        }

        on("leave") {
            val nickname = request.getPayloadAs<String>()
            roomMembers.remove(nickname)
        }
    }

    /**
     * Send message to everyone asynchronously
     */
    suspend fun send(message: String) = coroutineScope {
        val messageObj = ChatMessage(nickname, message, ChatMessageType.SHARED)

        roomMembers
            .map { async { send("CHAT", "message", it.value, messageObj) } }
            .awaitAll()
    }

    /**
     * Send message to someone
     */
    suspend fun sendDirect(message: String, to: String) {
        val messageObj = ChatMessage(nickname, message, ChatMessageType.DIRECT)
        val recipient = roomMembers[to]!!

        send("CHAT", "message", recipient, messageObj)
    }

    /**
     * Join some chat room
     */
    suspend fun join() = coroutineScope {
        roomMembers
            .filter { it.key != nickname }
            .map { async { send("CHAT", "join", it.value, nickname) } }
            .awaitAll()
    }

    /**
     * Leave some chat room
     */
    suspend fun leave() = coroutineScope {
        roomMembers
            .filter { it.key != nickname }
            .map { async { send("CHAT", "leave", it.value, nickname) } }
            .awaitAll()
    }

    /**
     * Ask room owner to join their room
     */
    suspend fun askToJoin(gateway: InetSocketAddress) {
        val roomMembers = exchange<HashMap<String, InetSocketAddress>>("CHAT", "ask to join", gateway)

        this.roomMembers.putAll(roomMembers)
    }
}

/**
 * Flag that distinguishes chat message type
 */
object ChatMessageType {
    const val SHARED = 0
    const val DIRECT = 1
}

/**
 * Chat message itself. NOTE (!) implements Serializable
 *
 * @param from [String] - sender's nickname
 * @param message [String] - message
 * @param type [Int] - [ChatMessageType]
 */
data class ChatMessage(val from: String, val message: String, val type: Int) : Serializable
```

As you might notice, all methods have the `suspend` modifier - it's because Prodigy is made with [coroutines](https://github.com/Kotlin/kotlinx.coroutines) - 
something like `inversed futures`. As you also might notice, we now have everything in one place: our *passive* logic 
that reacts to some messages is in the same place where our *active* logic that actually does some actions. Everything 
is wrapped in a single facade. You now can invoke some methods like: 
```kotlin
chatProtocolInstance.sendDirect("blabla", someNickname)
```
and be absolutely happy that it does the stuff you want it to do.

If you're reading this carefully you might ask: *But where the actual networking logic? Where all this `listen` and 
`close` stuff from the world of sockets?*. 

The short answer is: **You don't need to focus on it, you have it for free**.

#### But the long answer is also not so complicated
Yes, there is networking stuff and a lot! But it's hidden inside. All you need to do is to *run* your protocol with another
special class on some local address. This class is called (warning, original naming) `ProtocolRunner`.

Some notes about `ProtocolRunner`:
1. You can create as many runners as you want until you have free local address (free port if you have only one network 
interface)
2. You can register as many *different* protocols via one distinct runner as you want - they all will work on the same 
local address and that's amazing because it allows you to utilize the minimum
3. Every runner should be `close()`ed to free underlying resources after it's job's done

Let's run our protocol:
```kotlin
// create some addresses
val addr1 = InetSocketAddress("localhost", 1337)
val addr2 = InetSocketAddress("localhost", 1338)
val addr3 = InetSocketAddress("localhost", 1339)

// create some protocol runners and bind them
val runner1 = ProtocolRunner(RUDPNetworkProvider()).also { it.bind(addr1) }
val runner2 = ProtocolRunner(RUDPNetworkProvider()).also { it.bind(addr2) }
val runner3 = ProtocolRunner(RUDPNetworkProvider()).also { it.bind(addr3) }

// create chat protocols
val chat1 = SimpleChatProtocol("John Smith", addr1)
val chat2 = SimpleChatProtocol("John Doe", addr2)
val chat3 = SimpleChatProtocol("John Snow", addr3)

// register protocols at runners
runner1.registerProtocol(chat1)
runner2.registerProtocol(chat2)
runner3.registerProtocol(chat3)

// preparations are over
runBlocking {
    // start runners
    launch { runner1.runSuspending() }
    launch { runner2.runSuspending() }
    launch { runner3.runSuspending() }

    // make some conversation
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

    // stop runners
    coroutineContext.cancelChildren()
}

// close runners, free resources
runner1.close()
runner2.close()
runner3.close()
```

### Features
* Coroutine-powered solution that scales
* Based on [no-ARQ reliable UDP](https://github.com/seniorjoinu/reliable-udp)
* Uses [one of the fastest serialization libraries](https://github.com/RuedigerMoeller/fast-serialization)
* Enables you to decouple p2p-protocol from business logic
* Enables you to share the same protocol code between multiple projects
* Pretty

### Install
Use [Jitpack](https://jitpack.io/)

### Limitations
Currently works only for linux/win 32-64, but you can help me fix this - [details here.](https://github.com/seniorjoinu/wirehair-wrapper)