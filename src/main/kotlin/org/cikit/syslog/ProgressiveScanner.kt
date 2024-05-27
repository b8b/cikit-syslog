package org.cikit.syslog

import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

class ProgressiveScanner {

    companion object {
        private val emptyBuffer: ByteBuffer = ByteBuffer.allocate(0)
    }

    private val channel = Channel<ByteBuffer?>()
    private var bytesConsumedTotal: Long = 0

    private var input: ByteBuffer = emptyBuffer
    private var closed: Boolean = false

    fun isClosed() = closed
    fun bytesConsumed() = bytesConsumedTotal - input.limit() + input.position()

    suspend fun send(element: ByteBuffer) {
        if (input.hasRemaining()) throw IllegalStateException()
        channel.send(element)
        channel.receive()
        if (input.hasRemaining()) throw IllegalStateException()
        input = emptyBuffer
    }

    suspend fun close() {
        if (input.hasRemaining()) throw IllegalStateException()
        channel.send(null)
    }

    suspend fun receive(): Boolean {
        if (input.hasRemaining()) throw IllegalStateException()
        if (closed) return false
        val previousInput = input
        if (previousInput !== emptyBuffer) {
            input = emptyBuffer
            channel.send(previousInput)
        }
        val element = channel.receive() ?: return false.also { closed = true }
        input = element
        bytesConsumedTotal += input.limit()
        return true
    }

    fun available(): Int = input.remaining()

    fun hasAvailable(): Boolean = input.hasRemaining()

    fun readAvailable(max: Int = -1, min: Int = 1): ByteBuffer? {
        val available = available()
        if (available < min) return null
        val result = input.duplicate()
        if (max in 1 until available) {
            val startIndex = input.position()
            result.limit(startIndex + max)
            input.position(startIndex + max)
        } else {
            input.position(input.limit())
        }
        return result
    }

    fun readAvailableUntil(
        predicate: (Byte) -> Boolean,
        max: Int = -1
    ): ByteBuffer? {
        val startIndex = input.position()
        val endIndex = if (max > 0)
            minOf(startIndex + max, input.limit())
        else
            input.limit()
        for (i in startIndex until endIndex) {
            if (predicate(input[i])) {
                val result = input.duplicate()
                input.position(i)
                result.limit(i)
                return result
            }
        }
        return null
    }

    fun readAvailableUntil(
        table: BooleanArray,
        max: Int = -1
    ): ByteBuffer? {
        val startIndex = input.position()
        val endIndex = if (max > 0)
            minOf(startIndex + max, input.limit())
        else
            input.limit()
        for (i in startIndex until endIndex) {
            if (table[input[i].toInt() and 0xFF]) {
                val result = input.duplicate()
                input.position(i)
                result.limit(i)
                return result
            }
        }
        return null
    }

    fun peekAvailable(max: Int = -1, min: Int = 1): ByteBuffer? {
        val available = available()
        if (available < min) return null
        val result = input.duplicate()
        if (max in 1 until available) {
            val startIndex = input.position()
            result.limit(startIndex + max)
        }
        return result
    }

    fun peekAvailableUntil(
        predicate: (Byte) -> Boolean,
        max: Int = -1
    ): ByteBuffer? {
        val startIndex = input.position()
        val endIndex = if (max > 0)
            minOf(startIndex + max, input.limit())
        else
            input.limit()
        for (i in startIndex until endIndex) {
            if (predicate(input[i])) {
                val result = input.duplicate()
                result.limit(i)
                return result
            }
        }
        return null
    }

    fun skipAvailable(numberOfBytes: Int) {
        input.position(input.position() + numberOfBytes)
    }

    suspend fun skip(predicate: (Byte) -> Boolean): Long {
        var skipped = 0L
        while (true) {
            if (!input.hasRemaining()) {
                if (!receive()) break
                continue
            }
            val startIndex = input.position()
            val endIndex = input.limit()
            for (i in startIndex until endIndex) {
                if (!predicate(input[i])) {
                    skipped += i - startIndex
                    input.position(i)
                    return skipped
                }
            }
            skipped += endIndex - startIndex
            input.position(endIndex)
        }
        return skipped
    }

    suspend fun skip(table: BooleanArray): Long {
        var skipped = 0L
        while (true) {
            if (!input.hasRemaining()) {
                if (!receive()) break
                continue
            }
            val startIndex = input.position()
            val endIndex = input.limit()
            for (i in startIndex until endIndex) {
                if (!table[input[i].toInt() and 0xFF]) {
                    skipped += i - startIndex
                    input.position(i)
                    return skipped
                }
            }
            skipped += endIndex - startIndex
            input.position(endIndex)
        }
        return skipped
    }

    suspend fun skip(n: Long): Long {
        var skipped = 0L
        while (skipped < n) {
            if (!input.hasRemaining()) {
                if (!receive()) break
                continue
            }
            val startIndex = input.position()
            val endIndex = input.limit()
            val skipNow = if (n - skipped >= endIndex - startIndex)
                endIndex - startIndex
            else
                (n - skipped).toInt()
            skipped += skipNow
            input.position(startIndex + skipNow)
        }
        return skipped
    }

    suspend fun skipCommonPrefix(prefix: ByteBuffer): Int {
        val prefixLength = prefix.remaining()
        if (prefixLength == 0) return 0

        while (true) {
            if (!input.hasRemaining()) {
                if (!receive()) return 0
                continue
            }
            if (input == prefix) {
                input.position(input.position() + prefixLength)
                return prefixLength
            }
            break
        }

        val prefixOffset = prefix.position()

        var skipped = 0
        while (skipped < prefixLength) {
            if (!input.hasRemaining()) {
                if (!receive()) return 0
                continue
            }
            val startIndex = input.position()
            val endIndex = input.limit()
            val limit = minOf(prefixLength - skipped, endIndex - startIndex)
            for (i in 0 until limit) {
                if (input[startIndex + i] != prefix[prefixOffset + skipped + i]) {
                    input.position(startIndex + i)
                    return skipped + i
                }
            }
            input.position(startIndex + limit)
            skipped += limit
        }
        return skipped
    }

    suspend fun skipByte(b: Byte): Boolean {
        while (true) {
            if (!input.hasRemaining()) {
                if (!receive()) return false
                continue
            }
            val startIndex = input.position()
            if (input[startIndex] == b) {
                input.position(startIndex + 1)
                return true
            }
            return false
        }
    }

    suspend fun peekByte(): Int {
        while (true) {
            if (!input.hasRemaining()) {
                if (!receive()) return -1
                continue
            }
            return input[input.position()].toInt()
        }
    }

    suspend fun readByte(): Int {
        while (true) {
            if (!input.hasRemaining()) {
                if (!receive()) return -1
                continue
            }
            return input.get().toInt()
        }
    }

    suspend fun readDigits(block: (Int) -> Unit): Long {
        var n = 0L
        while (true) {
            if (!input.hasRemaining()) {
                if (!receive()) return n
                continue
            }
            val startIndex = input.position()
            val endIndex = input.limit()
            for (i in startIndex until endIndex) {
                val b = input[i]
                if (b < '0'.code.toByte() || b > '9'.code.toByte()) {
                    input.position(i)
                    return n + i - startIndex
                }
                block(b.toInt() - '0'.code)
            }
            n += endIndex - startIndex
            input.position(endIndex)
        }
    }

    suspend fun readInt(block: (Int) -> Unit): Boolean {
        var result = 0
        var valid = false

        while (true) {
            if (!input.hasRemaining()) {
                if (!receive()) {
                    if (valid) {
                        block(result)
                    }
                    return valid
                }
                continue
            }
            val startIndex = input.position()
            val endIndex = input.limit()
            for (i in startIndex until endIndex) {
                val b = input[i]
                if (b < '0'.code.toByte() || b > '9'.code.toByte()) {
                    if (!valid) {
                        return when (b) {
                            '-'.code.toByte() -> {
                                readNegativeInt(block, startIndex, endIndex)
                            }
                            '+'.code.toByte() -> {
                                continue
                            }
                            else -> false
                        }
                    }
                    input.position(i)
                    block(result)
                    return true
                }
                if (valid) {
                    result = result * 10 + b.toInt() - '0'.code
                } else {
                    result = b.toInt() - '0'.code
                    valid = true
                }
            }
            input.position(endIndex)
        }
    }

    private suspend fun readNegativeInt(
        block: (Int) -> Unit,
        firstStartIndex: Int,
        firstEndIndex: Int
    ): Boolean {
        var result = 0
        var valid = false

        for (i in firstStartIndex + 1 until firstEndIndex) {
            val b = input[i]
            if (b < '0'.code.toByte() || b > '9'.code.toByte()) {
                if (!valid) {
                    return false
                }
                input.position(i)
                block(0 - result)
                return true
            }
            if (valid) {
                result = result * 10 + b.toInt() - '0'.code
            } else {
                result = b.toInt() - '0'.code
                valid = true
            }
        }

        input.position(firstEndIndex)

        while (true) {
            if (!input.hasRemaining()) {
                if (!receive()) {
                    if (valid) {
                        block(0 - result)
                    }
                    return valid
                }
                continue
            }
            val startIndex = input.position()
            val endIndex = input.limit()
            for (i in startIndex until endIndex) {
                val b = input[i]
                if (b < '0'.code.toByte() || b > '9'.code.toByte()) {
                    if (!valid) {
                        return false
                    }
                    input.position(i)
                    block(0 - result)
                    return true
                }
                if (valid) {
                    result = result * 10 + b.toInt() - '0'.code
                } else {
                    result = b.toInt() - '0'.code
                    valid = true
                }
            }
            input.position(endIndex)
        }
    }

    suspend fun readLong(block: (Long) -> Unit): Boolean {
        var result = 0L
        var valid = false

        while (true) {
            if (!input.hasRemaining()) {
                if (!receive()) {
                    if (valid) {
                        block(result)
                    }
                    return valid
                }
                continue
            }
            val startIndex = input.position()
            val endIndex = input.limit()
            for (i in startIndex until endIndex) {
                val b = input[i]
                if (b < '0'.code.toByte() || b > '9'.code.toByte()) {
                    if (!valid) {
                        return when (b) {
                            '-'.code.toByte() -> {
                                readNegativeLong(block, startIndex, endIndex)
                            }
                            '+'.code.toByte() -> {
                                continue
                            }
                            else -> false
                        }
                    }
                    input.position(i)
                    block(result)
                    return true
                }
                if (valid) {
                    result = result * 10L + (b.toInt() - '0'.code)
                } else {
                    result = (b.toInt() - '0'.code).toLong()
                    valid = true
                }
            }
            input.position(endIndex)
        }
    }

    private suspend fun readNegativeLong(
        block: (Long) -> Unit,
        firstStartIndex: Int,
        firstEndIndex: Int
    ): Boolean {
        var result = 0L
        var valid = false

        for (i in firstStartIndex + 1 until firstEndIndex) {
            val b = input[i]
            if (b < '0'.code.toByte() || b > '9'.code.toByte()) {
                if (!valid) {
                    return false
                }
                input.position(i)
                block(0L - result)
                return true
            }
            if (valid) {
                result = result * 10L + (b.toInt() - '0'.code)
            } else {
                result = (b.toInt() - '0'.code).toLong()
                valid = true
            }
        }

        input.position(firstEndIndex)

        while (true) {
            if (!input.hasRemaining()) {
                if (!receive()) {
                    if (valid) {
                        block(0L - result)
                    }
                    return valid
                }
                continue
            }
            val startIndex = input.position()
            val endIndex = input.limit()
            for (i in startIndex until endIndex) {
                val b = input[i]
                if (b < '0'.code.toByte() || b > '9'.code.toByte()) {
                    if (!valid) {
                        return false
                    }
                    input.position(i)
                    block(0L - result)
                    return true
                }
                if (valid) {
                    result = result * 10L + (b.toInt() - '0'.code)
                } else {
                    result = (b.toInt() - '0'.code).toLong()
                    valid = true
                }
            }
            input.position(endIndex)
        }
    }

    suspend fun readUntilEnd(dest: WritableByteChannel) {
        while (true) {
            val buffer = readAvailable()
            if (buffer == null) {
                if (!receive()) break
                continue
            }
            dest.write(buffer)
        }
    }

    suspend fun readUntilEnd(dest: ByteBuffer): Boolean {
        while (true) {
            val remaining = dest.remaining()
            if (remaining <= 0) return false
            val buffer = readAvailable(max = remaining)
            if (buffer == null) {
                if (!receive()) return true
                continue
            }
            dest.put(buffer)
        }
    }

    suspend fun readUntil(
        dest: WritableByteChannel,
        predicate: (Byte) -> Boolean
    ): Boolean {
        while (true) {
            val found = readAvailableUntil(predicate)
            if (found != null) {
                dest.write(found)
                return true
            }
            val buffer = readAvailable()
            if (buffer == null) {
                if (!receive()) return false
                continue
            }
            dest.write(buffer)
        }
    }

    suspend fun readUntil(
        dest: WritableByteChannel,
        table: BooleanArray
    ): Boolean {
        while (true) {
            val found = readAvailableUntil(table)
            if (found != null) {
                dest.write(found)
                return true
            }
            val buffer = readAvailable()
            if (buffer == null) {
                if (!receive()) return false
                continue
            }
            dest.write(buffer)
        }
    }

    suspend fun readUntil(
        dest: ByteBuffer,
        predicate: (Byte) -> Boolean
    ): Boolean {
        while (true) {
            val remaining = dest.remaining()
            if (remaining <= 0) return false
            val found = readAvailableUntil(predicate, max = remaining)
            if (found != null) {
                dest.put(found)
                return true
            }
            val buffer = readAvailable(max = remaining)
            if (buffer == null) {
                if (!receive()) return false
                continue
            }
            dest.put(buffer)
        }
    }

    suspend fun readUntil(
        dest: ByteBuffer,
        table: BooleanArray
    ): Boolean {
        while (true) {
            val remaining = dest.remaining()
            if (remaining <= 0) return false
            val found = readAvailableUntil(table, max = remaining)
            if (found != null) {
                dest.put(found)
                return true
            }
            val buffer = readAvailable(max = remaining)
            if (buffer == null) {
                if (!receive()) return false
                continue
            }
            dest.put(buffer)
        }
    }

}
