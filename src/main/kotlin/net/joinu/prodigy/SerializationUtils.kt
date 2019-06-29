package net.joinu.prodigy

import mu.KotlinLogging
import org.nustaq.serialization.FSTConfiguration
import java.io.Serializable
import java.nio.ByteBuffer
import java.util.*


/**
 * Some util funcs to handle serialization.
 */
object SerializationUtils {
    private val logger = KotlinLogging.logger("SerializationUtils-${Random().nextInt()}")

    val mapper by lazy { FSTConfiguration.createAndroidDefaultConfiguration() }

    fun toBytes(obj: Serializable?): ByteArray {
        val bytes = mapper.asByteArray(obj)

        logger.trace { "Successfully serialized $obj" }

        return bytes
    }

    fun <T> toAny(bytes: ByteArray, clazz: Class<T>): T {
        val obj = mapper.asObject(bytes)

        logger.trace { "Successfully deserialized $obj" }

        return clazz.cast(obj)
    }

    inline fun <reified T> toAny(buffer: ByteBuffer): T {
        val bytes = ByteArray(buffer.limit())
        buffer.get(bytes)
        buffer.flip()

        return toAny(bytes)
    }

    inline fun <reified T> toAny(bytes: ByteArray): T =
        toAny(bytes, T::class.java)
}
