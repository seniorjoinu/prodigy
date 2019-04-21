package net.joinu.prodigy

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import mu.KotlinLogging
import net.joinu.nioudp.MAX_CHUNK_SIZE_BYTES
import java.util.*

object SerializationUtils {
    private val logger = KotlinLogging.logger("SerializationUtils-${Random().nextInt()}")

    val mapper by lazy {
        val m = Kryo()
        m.isRegistrationRequired = false
        m
    }

    fun toBytes(obj: Any): ByteArray {
        val output = Output(ByteArray(MAX_CHUNK_SIZE_BYTES))
        mapper.writeObjectOrNull(output, obj, obj::class.java)

        logger.trace { "Successfully serialized $obj" }

        return output.toBytes()
    }

    fun <T> toAny(bytes: ByteArray, clazz: Class<T>): T {
        val obj = mapper.readObjectOrNull(Input(bytes), clazz)

        logger.trace { "Successfully deserialized $obj" }

        return obj
    }

    inline fun <reified T> toAny(bytes: ByteArray): T =
        toAny(bytes, T::class.java)
}
