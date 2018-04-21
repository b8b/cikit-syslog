package org.cikit.syslog

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.MalformedInputException
import java.nio.charset.UnmappableCharacterException

open class Progressive3 {

    private val channel = BlockingChannel()
    private var bytesConsumedTotal: Long = 0
    private var input: ByteBuffer = ByteBuffer.allocate(0)
    private var dup: ByteBuffer? = null

    protected var startIndex: Int = 0
    protected var endIndex: Int = 0

    fun isClosed() = channel.isClosed()
    fun bytesConsumed() = bytesConsumedTotal - endIndex + startIndex
    protected fun input(index: Int) = input[index]
    protected fun dup(): ByteBuffer = dup ?: input.duplicate().also { dup = it }

    suspend fun send(element: ByteBuffer) {
        if (startIndex < endIndex) throw IllegalStateException()
        channel.send(element)
        if (startIndex != endIndex) throw IllegalStateException()
    }

    suspend fun close() {
        if (startIndex < endIndex) throw IllegalStateException()
        channel.close()
    }

    protected suspend fun receive(): Boolean {
        if (startIndex < endIndex) throw IllegalStateException()
        val element = channel.receive() ?: return false
        input = element
        startIndex = input.position()
        endIndex = input.limit()
        bytesConsumedTotal += endIndex - startIndex
        dup = null
        return true
    }

    fun isSpace(b: Byte) = b == ' '.toByte() ||
            b == '\t'.toByte() ||
            b == '\b'.toByte()

    suspend fun skip(predicate: (Byte) -> Boolean = ::isSpace): Boolean {
        while (true) {
            for (i in startIndex until endIndex) {
                if (!predicate(input(i))) {
                    startIndex = i
                    return true
                }
            }
            startIndex = endIndex
            if (!receive()) return false
        }
    }

    suspend fun skipPrefix(b: Byte): Boolean {
        while (startIndex >= endIndex) {
            if (!receive()) return false
        }
        if (input(startIndex) == b) {
            startIndex++
            return true
        }
        return false
    }

    suspend fun readByte(): Int {
        while (startIndex >= endIndex) {
            if (!receive()) return -1
        }
        val b = input(startIndex)
        startIndex++
        return b.toInt()
    }

    suspend fun readDigits(block: (Int) -> Unit): Boolean {
        while (startIndex >= endIndex) {
            if (!receive()) return false
        }
        var b = input(startIndex)
        if (b < '0'.toByte() || b > '9'.toByte()) {
            return false
        }
        block(b.toInt() - '0'.toInt())
        startIndex++
        while (true) {
            for (i in startIndex until endIndex) {
                b = input(i)
                if (b < '0'.toByte() || b > '9'.toByte()) {
                    startIndex = i
                    return true
                }
                block(b.toInt() - '0'.toInt())
            }
            if (!receive()) return false
        }
    }

    suspend fun readUntil(predicate: (Byte) -> Boolean): Boolean {
        if (startIndex >= endIndex && !receive()) {
            val dup = dup()
            dup.limit(startIndex)
            dup.position(startIndex)
            return false
        }
        for (i in startIndex until endIndex) {
            val b = input(i)
            if (predicate(b)) {
                val dup = dup()
                dup.limit(i)
                dup.position(startIndex)
                startIndex = i
                return true
            }
        }
        val dup = dup()
        dup.limit(endIndex)
        dup.position(startIndex)
        startIndex = endIndex
        return false
    }

    private suspend fun decodeRemaining(dest: CharBuffer,
                                        tmp: ByteBuffer,
                                        decoder: CharsetDecoder,
                                        predicate: (Byte) -> Boolean): Boolean {
        val result = readUntil(predicate)
        val dup = dup()
        val start = dup.position()
        val limit = dup.limit()
        val tmpPosition = tmp.position()
        val tmpRemaining = tmp.remaining()
        val endOfInput = result && if (limit - start > tmpRemaining) {
            dup.limit(dup.position() + tmpRemaining)
            false
        } else {
            true
        }
        tmp.put(dup)
        tmp.flip()
        val cr = decoder.decode(tmp, dest, endOfInput)
        val consumed = tmp.position() - tmpPosition
        startIndex = if (consumed <= 0) {
            startIndex - limit + start
        } else {
            startIndex - limit + start + consumed
        }
        when {
            cr.isUnderflow -> {
                if (endOfInput && !tmp.hasRemaining()) return true
                if (consumed <= 0) throw IllegalStateException()
                return false
            }
            cr.isOverflow -> {
                return false
            }
            cr.isMalformed -> throw MalformedInputException(cr.length())
            cr.isUnmappable -> throw UnmappableCharacterException(cr.length())
            else -> throw IllegalStateException()
        }
    }

    suspend fun decodeUntil(dest: CharBuffer, tmp: ByteBuffer,
                            decoder: CharsetDecoder,
                            predicate: (Byte) -> Boolean): Boolean {
        while (dest.hasRemaining()) {
            val result = readUntil(predicate)
            val dup = dup()
            val cr = decoder.decode(dup, dest, result)
            val remaining = dup.remaining()
            startIndex -= remaining
            when {
                cr.isUnderflow -> {
                    if (remaining > 0) {
                        if (result) throw IllegalStateException()
                        if (!dest.hasRemaining()) return false
                        tmp.clear()
                        tmp.limit(9)
                        tmp.put(dup)
                        startIndex += remaining
                        tmp.limit(10)
                        if (decodeRemaining(dest, tmp, decoder, predicate)) return true
                    } else if (result) {
                        return true
                    }
                }
                cr.isOverflow -> {
                    return false
                }
                cr.isMalformed -> throw MalformedInputException(cr.length())
                cr.isUnmappable -> throw UnmappableCharacterException(cr.length())
                else -> throw IllegalStateException()
            }
        }
        return false
    }

}
