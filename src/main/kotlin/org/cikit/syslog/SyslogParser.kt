package org.cikit.syslog

import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.MalformedInputException
import java.nio.charset.UnmappableCharacterException
import java.time.OffsetDateTime
import java.time.ZoneOffset

class SyslogParser : Progressive3() {

    companion object {
        private val nilBytes = ByteBuffer.wrap(byteArrayOf('-'.toByte())).asReadOnlyBuffer()
    }

    private var cachedHost: Pair<ByteBuffer, String?> = nilBytes to null
    private var cachedApp: Pair<ByteBuffer, String?> = nilBytes to null

    private val keys = mutableMapOf<ByteBuffer, String>()
    private val values = mutableMapOf<Pair<String, String>, String>()

    private var priValue = -1
    private var host: String? = null
    private var app: String? = null
    private var ts: OffsetDateTime? = null
    private var proc: Long? = null
    private var msgid: String? = null

    fun priValue() = priValue
    fun facility() = if (priValue < 0) null else Facility.values().getOrNull(priValue shr 3)
    fun severity() = if (priValue < 0) null else Severity.values().getOrNull(priValue and 0x7)
    fun ts() = ts
    fun host() = host
    fun app() = app
    fun proc() = proc
    fun msgid() = msgid
    fun sd(id: String, key: String): String? = values[id to key]

    private fun isSpace(b: Byte) = b == ' '.toByte() ||
            b == '\t'.toByte() ||
            b == '\b'.toByte()

    private suspend fun skip(predicate: (Byte) -> Boolean = ::isSpace): Boolean {
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

    private suspend fun skipPrefix(b: Byte): Boolean {
        while (startIndex >= endIndex) {
            if (!receive()) return false
        }
        if (input(startIndex) == b) {
            startIndex++
            return true
        }
        return false
    }

    private suspend fun readByte(): Int {
        while (startIndex >= endIndex) {
            if (!receive()) return -1
        }
        val b = input(startIndex)
        startIndex++
        return b.toInt()
    }

    private suspend fun readDigits(block: (Int) -> Unit): Boolean {
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

    private suspend fun readUntil(predicate: (Byte) -> Boolean = ::isSpace): Boolean {
        if (startIndex >= endIndex && !receive()) {
            val dup = dup()
            dup.limit(startIndex)
            dup.position(startIndex)
            return false
        }
        for (i in startIndex until endIndex) {
            val b = input(i)
            if (predicate(b) || b == '\n'.toByte()) {
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

    private suspend fun readUntil(tmp: ByteBuffer, predicate: (Byte) -> Boolean = ::isSpace): Boolean {
        while (tmp.hasRemaining()) {
            for (i in startIndex until endIndex) {
                val b = input(i)
                if (predicate(b) || b == '\n'.toByte()) {
                    startIndex = i
                    return true
                }
                tmp.put(b)
                if (!tmp.hasRemaining()) return false
            }
            startIndex = endIndex
            if (!receive()) return false
        }
        return false
    }

    private suspend fun decodeRemaining(dest: CharBuffer, tmp: ByteBuffer, decoder: CharsetDecoder,
                                        predicate: (Byte) -> Boolean = ::isSpace): Boolean {
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

    private suspend fun decodeUntil(dest: CharBuffer, tmp: ByteBuffer, decoder: CharsetDecoder,
                                    predicate: (Byte) -> Boolean = ::isSpace): Boolean {
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

    private suspend fun readField(tmp: ByteBuffer, predicate: (Byte) -> Boolean = ::isSpace): ByteBuffer {
        return when {
            readUntil(predicate) -> dup()
            else -> tmp.also {
                it.clear()
                it.put(dup())
                if (!readUntil(it, predicate) && !isClosed()) throw BufferOverflowException()
            }
        }
    }

    private suspend fun readKey(tmp: ByteBuffer, decoder: CharsetDecoder, predicate: (Byte) -> Boolean = ::isSpace): String {
        val field = readField(tmp, predicate)
        val cached = keys[field]
        if (cached != null) return cached
        val bytes = ByteBuffer.allocate(field.remaining())
        bytes.put(field)
        bytes.rewind()
        val chars = decoder.decode(bytes) ?: Charsets.UTF_8.decode(bytes)
        val str = chars.toString()
        keys[bytes] = str
        return str
    }

    private suspend fun parseOffset(sign: Int): ZoneOffset? {
        var hours = 0
        var minutes = 0
        if (!readDigits({ hours = hours * 10 + it })) return null
        if (!skipPrefix(':'.toByte())) return null
        if (!readDigits({ minutes = minutes * 10 + it })) return null
        return ZoneOffset.ofHoursMinutes(hours * sign, minutes * sign)
    }

    private suspend fun parseTs(tmp: ByteBuffer): Boolean {
        var year = 0
        var month = 0
        var day = 0
        var hour = 0
        var minute = 0
        var second = 0
        var nanos = 0
        if (!readDigits({ year = year * 10 + it })) {
            if (readField(tmp) == nilBytes) {
                ts = null
                return true
            }
            return false
        }
        if (!skipPrefix('-'.toByte())) return false
        if (!readDigits({ month = month * 10 + it })) return false
        if (!skipPrefix('-'.toByte())) return false
        if (!readDigits({ day = day * 10 + it })) return false
        if (!skipPrefix('T'.toByte())) return false
        if (!readDigits({ hour = hour * 10 + it })) return false
        if (!skipPrefix(':'.toByte())) return false
        if (!readDigits({ minute = minute * 10 + it })) return false
        if (!skipPrefix(':'.toByte())) return false
        if (!readDigits({ second = second * 10 + it })) return false
        if (skipPrefix('.'.toByte())) {
            var digits = 0
            if (!readDigits({ digits++; nanos = nanos * 10 + it })) return false
            if (digits > 9) return false
            for (i in digits.inc() .. 9) nanos *= 10
        }
        val z = when (readByte()) {
            'Z'.toByte().toInt() -> ZoneOffset.UTC
            '+'.toByte().toInt() -> parseOffset(1) ?: return false
            '-'.toByte().toInt() -> parseOffset(-1) ?: return false
            else -> return false
        }
        ts = OffsetDateTime.of(year, month, day, hour, minute, second, nanos, z)
        return true
    }

    suspend fun parse5424(tmp: ByteBuffer = ByteBuffer.allocate(1024),
                          decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()): Boolean {
        //reset
        priValue = 0
        ts = null
        host = null
        app = null
        proc = null
        msgid = null
        values.clear()

        //read pri
        if (!skipPrefix('<'.toByte())) return false
        if (!readDigits({ priValue = priValue * 10 + it })) return false
        if (!skipPrefix('>'.toByte())) return false

        //read version
        if (!skipPrefix('1'.toByte())) return false
        if (!skip()) return false

        //read ts
        if (!parseTs(tmp)) return false
        skip()

        //read host
        host = readField(tmp).let {
            when (it) {
                cachedHost.first -> cachedHost.second
                nilBytes -> null
                else -> {
                    val bytes = ByteBuffer.allocate(it.remaining())
                    bytes.put(it)
                    bytes.rewind()
                    val chars = decoder.decode(bytes).toString()
                    cachedHost = bytes to chars
                    cachedHost.second
                }
            }
        }
        skip()

        //read app
        app = readField(tmp).let {
            when (it) {
                cachedApp.first -> cachedApp.second
                nilBytes -> null
                else -> {
                    val bytes = ByteBuffer.allocate(it.remaining())
                    bytes.put(it)
                    bytes.rewind()
                    val chars = decoder.decode(bytes).toString()
                    cachedApp = bytes to chars
                    cachedApp.second
                }
            }
        }
        skip()

        //read proc
        proc = let {
            var longValue = 0L
            when {
                readDigits({ longValue = longValue * 10 + it }) -> longValue
                readField(tmp) == nilBytes -> null
                else -> return false
            }
        }
        skip()

        //read msgid
        msgid = readField(tmp).let {
            if (it == nilBytes)
                null
            else
                decoder.decode(it).toString()
        }
        skip()

        //read sd
        var hasSd = false
        while (skipPrefix('['.toByte())) {
            hasSd = true
            //read id
            val id = readKey(tmp, decoder)
            skip()
            //field kv pairs
            while (true) {
                if (skipPrefix(']'.toByte())) break
                val key = readKey(tmp, decoder, { it == '='.toByte() || it == ']'.toByte() })
                if (skipPrefix('='.toByte())) {
                    decoder.reset()
                    val cb = CharBuffer.allocate(1024)
                    val value = StringBuilder()
                    if (skipPrefix('"'.toByte())) {
                        while (true) {
                            val result = decodeUntil(cb, tmp, decoder, { it == '\\'.toByte() || it == '"'.toByte() })
                            cb.flip()
                            value.append(cb)
                            cb.clear()
                            if (result) {
                                val c1 = readByte().toChar()
                                if (c1 == '"') break
                                if (c1 != '\\') return false
                                val c2 = readByte().toChar()
                                if (c2 != '\\' && c2 != '"' && c2 != ']') {
                                    value.append('\\')
                                }
                                value.append(c2)
                            }
                            if (isClosed()) return false
                        }
                    } else {
                        while (true) {
                            val result = decodeUntil(cb, tmp, decoder, { isSpace(it) || it == ']'.toByte() })
                            cb.flip()
                            value.append(cb)
                            cb.clear()
                            if (result) break
                            if (isClosed()) return false
                        }
                    }
                    values[id to key] = value.toString()
                } else {
                    return false
                }
            }
        }
        if (!hasSd) {
            if (readField(tmp) != nilBytes) return false
        }
        skip()

        return true
    }

    suspend fun skipMessage() {
        val result = skip({ it != '\n'.toByte() })
        if (result) readByte()
    }

}