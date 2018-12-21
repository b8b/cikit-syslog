package org.cikit.syslog

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.OffsetDateTime
import java.time.ZoneOffset

class SyslogParser {

    companion object {
        private val nilBytes = ByteBuffer.wrap(byteArrayOf('-'.toByte())).asReadOnlyBuffer()
    }

    private var cachedHost: Pair<ByteBuffer, String> = nilBytes to "-"
    private var cachedApp: Pair<ByteBuffer, String> = nilBytes to "-"

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

    private fun isSpaceOrNl(b: Byte) = b == ' '.toByte() ||
            b == '\t'.toByte() ||
            b == '\b'.toByte() ||
            b == '\n'.toByte()

    private fun ByteBuffer.toString(charset: Charset): String {
        val bytes = if (hasArray()) {
            val offset = arrayOffset() + position()
            array().copyOfRange(offset, offset + remaining())
        } else {
            ByteArray(remaining()).also { get(it) }
        }
        return String(bytes, charset)
    }

    private suspend fun ProgressiveScanner.readCachedField(
            cached: Pair<ByteBuffer, String>): Pair<ByteBuffer, String> {
        val buffer = cached.first
        val bytesToSkip = buffer.remaining()
        val skippedBytes = skipCommonPrefix(buffer)
        if (skippedBytes == bytesToSkip) {
            val delimiter = peekByte()
            if (delimiter < 0 || isSpaceOrNl(delimiter.toByte())) return cached
        }
        if (buffer !== nilBytes) {
            val capacity = buffer.capacity()
            if (capacity > skippedBytes) {
                buffer.compact()
                buffer.position(skippedBytes)
                val result = readUntil(buffer, ::isSpaceOrNl)
                buffer.flip()
                if (result) return buffer to buffer.toString(Charsets.UTF_8)
            }
        }
        val tmp = InMemoryByteChannel()
        if (buffer !== nilBytes && buffer.hasRemaining()) tmp.write(buffer)
        readUntil(tmp, ::isSpaceOrNl)
        val newBuffer = ByteBuffer.wrap(tmp.toByteArray())
        return newBuffer to newBuffer.toString(Charsets.UTF_8)
    }

    private suspend fun ProgressiveScanner.readField(
            delimiter: (Byte) -> Boolean = ::isSpaceOrNl): ByteBuffer {
        while (true) {
            if (!hasAvailable()) {
                if (!receive()) return ByteBuffer.allocate(0)
                continue
            }
            return readAvailableUntil(delimiter) ?: break
        }
        val tmp = InMemoryByteChannel()
        readUntil(tmp, delimiter)
        return tmp.asByteBuffer() ?: ByteBuffer.wrap(tmp.toByteArray())
    }

    private suspend fun ProgressiveScanner.readKey(
            delimiter: (Byte) -> Boolean = ::isSpaceOrNl): String {
        val buffer = readField(delimiter)
        val length = buffer.remaining()
        if (length == 0) return ""
        val cached = keys[buffer]
        if (cached != null) {
            return cached
        }
        val bytes = if (buffer.hasArray()) {
            val offset = buffer.arrayOffset() + buffer.position()
            buffer.array().copyOfRange(offset, offset + length)
        } else {
            ByteArray(length).also { buffer.get(it) }
        }
        val str = String(bytes, Charsets.UTF_8)
        keys[ByteBuffer.wrap(bytes)] = str
        return str
    }

    private suspend fun ProgressiveScanner.parseOffset(sign: Int): ZoneOffset? {
        var hours = 0
        var minutes = 0
        if (readDigits { hours = hours * 10 + it } == 0L) return null
        if (!skipByte(':'.toByte())) return null
        if (readDigits { minutes = minutes * 10 + it } == 0L) return null
        return ZoneOffset.ofHoursMinutes(hours * sign, minutes * sign)
    }

    private suspend fun ProgressiveScanner.parseTs(): Boolean {
        var year = 0
        var month = 0
        var day = 0
        var hour = 0
        var minute = 0
        var second = 0
        var nanos = 0
        if (readDigits { year = year * 10 + it } == 0L) {
            if (readField() == nilBytes) {
                ts = null
                return true
            }
            return false
        }
        if (!skipByte('-'.toByte())) return false
        if (readDigits { month = month * 10 + it } == 0L) return false
        if (!skipByte('-'.toByte())) return false
        if (readDigits { day = day * 10 + it } == 0L) return false
        if (!skipByte('T'.toByte())) return false
        if (readDigits { hour = hour * 10 + it } == 0L) return false
        if (!skipByte(':'.toByte())) return false
        if (readDigits { minute = minute * 10 + it } == 0L) return false
        if (!skipByte(':'.toByte())) return false
        if (readDigits { second = second * 10 + it } == 0L) return false
        if (skipByte('.'.toByte())) {
            var digits = 0
            if (readDigits { digits++; nanos = nanos * 10 + it } == 0L) return false
            if (digits > 9) return false
            for (i in digits.inc()..9) nanos *= 10
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

    suspend fun parse5424(input: ProgressiveScanner): Boolean {
        //reset
        priValue = 0
        ts = null
        host = null
        app = null
        proc = null
        msgid = null
        values.clear()

        //read pri
        if (!input.skipByte('<'.toByte())) return false
        if (input.readDigits { priValue = priValue * 10 + it } == 0L) return false
        if (!input.skipByte('>'.toByte())) return false

        //read version
        if (!input.skipByte('1'.toByte())) return false
        if (input.skip(::isSpace) == 0L) return false

        //read ts
        if (!input.parseTs()) return false
        input.skip(::isSpace)

        //read host
        cachedHost = input.readCachedField(cachedHost)
        host = cachedHost.second
        if (input.skip(::isSpace) == 0L) return false

        //read app
        cachedApp = input.readCachedField(cachedApp)
        app = cachedApp.second
        if (input.skip(::isSpace) == 0L) return false

        //read proc
        proc = let {
            var longValue = 0L
            when {
                input.readDigits { d -> longValue = longValue * 10 + d } > 0 -> longValue
                input.readField() == nilBytes -> null
                else -> return false
            }
        }
        if (input.skip(::isSpace) == 0L) return false

        //read msgid
        msgid = input.readField().let {
            if (it == nilBytes)
                null
            else {
                it.toString(Charsets.UTF_8)
            }
        }
        if (input.skip(::isSpace) == 0L) return false

        //read sd
        var hasSd = false
        while (input.skipByte('['.toByte())) {
            hasSd = true
            //read id
            val id = input.readKey()
            input.skip(::isSpace)
            //field kv pairs
            while (true) {
                if (input.skipByte(']'.toByte())) break
                val key = input.readKey {
                    it == '='.toByte() ||
                            it == ']'.toByte() ||
                            it == '\n'.toByte()
                }
                if (input.skipByte('='.toByte())) {
                    val tmp = InMemoryByteChannel()
                    if (input.skipByte('"'.toByte())) {
                        while (true) {
                            input.readUntil(tmp) {
                                it == '"'.toByte() ||
                                        it == '\\'.toByte() ||
                                        it == '\n'.toByte()
                            }
                            if (input.hasAvailable()) {
                                val c1 = input.readByte().toChar()
                                if (c1 == '"') break
                                if (c1 != '\\') return false
                                val c2 = input.readByte().toChar()
                                if (c2 != '\\' && c2 != '"' && c2 != ']') {
                                    tmp.write('\\'.toInt())
                                }
                                tmp.write(c2.toInt())
                            }
                            if (input.isClosed()) return false
                        }
                    } else {
                        while (true) {
                            input.readUntil(tmp) {
                                isSpace(it) ||
                                        it == ']'.toByte() ||
                                        it == '\n'.toByte()
                            }
                            if (input.hasAvailable()) break
                            if (input.isClosed()) return false
                        }
                    }
                    values[id to key] = tmp.toString(Charsets.UTF_8)
                } else {
                    return false
                }
            }
        }
        if (!hasSd) {
            if (input.readField() != nilBytes) return false
        }
        input.skip(::isSpace)

        return true
    }

}
