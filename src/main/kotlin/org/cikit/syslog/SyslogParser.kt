package org.cikit.syslog

import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.time.ZoneOffset

class SyslogParser {

    companion object {
        private val nilBytes = ByteBuffer.wrap(
            byteArrayOf('-'.code.toByte())
        ).asReadOnlyBuffer()
    }

    private var cachedHost: Pair<ByteBuffer, String> = nilBytes to "-"
    private var cachedApp: Pair<ByteBuffer, String> = nilBytes to "-"

    private val keys = HashMap<ByteBuffer, String>()
    private val values = HashMap<Pair<String, String>, String>()

    private var priValue = -1
    private var host: String? = null
    private var app: String? = null
    private var ts: OffsetDateTime? = null
    private var proc: Long? = null
    private var msgid: String? = null

    private val tSpace = BooleanArray(256) { i ->
        i == ' '.code ||
                i == '\t'.code ||
                i == '\b'.code
    }

    private val tSpaceOrNl = BooleanArray(256) { i ->
        i == ' '.code ||
                i == '\n'.code ||
                i == '\t'.code ||
                i == '\b'.code
    }

    private val tEqOrCloseBracketOrNl = BooleanArray(256) { i ->
        i == '='.code || i == ']'.code || i == '\n'.code
    }

    private val tCloseBracketOrSpaceOrNl = BooleanArray(256) { i ->
        tSpace[i] || i == ']'.code || i == '\n'.code
    }

    fun priValue() = priValue

    fun facility() = when {
        priValue < 0 -> null
        else -> Facility.entries.getOrNull(priValue shr 3)
    }

    fun severity() = when {
        priValue < 0 -> null
        else -> Severity.entries.getOrNull(priValue and 0x7)
    }

    fun ts() = ts

    fun host() = host

    fun app() = app

    fun proc() = proc

    fun msgid() = msgid

    fun sd(id: String, key: String): String? = values[id to key]

    private suspend fun ProgressiveScanner.readCachedField(
        cached: Pair<ByteBuffer, String>
    ): Pair<ByteBuffer, String> {
        val buffer = cached.first
        val bytesToSkip = buffer.remaining()
        val skippedBytes = skipCommonPrefix(buffer)
        if (skippedBytes == bytesToSkip) {
            val delimiter = peekByte()
            if (delimiter < 0 || tSpaceOrNl[delimiter and 0xFF]) return cached
        }
        if (buffer !== nilBytes) {
            val capacity = buffer.capacity()
            if (capacity > skippedBytes) {
                buffer.compact()
                buffer.position(skippedBytes)
                val result = readUntil(buffer, tSpaceOrNl)
                buffer.flip()
                if (result) return buffer to buffer.decodeToString()
            }
        }
        val tmp = InMemoryByteChannel()
        if (buffer !== nilBytes && buffer.hasRemaining()) {
            tmp.write(buffer)
        }
        readUntil(tmp, tSpaceOrNl)
        val newBuffer = ByteBuffer.wrap(tmp.toByteArray())
        return newBuffer to newBuffer.decodeToString()
    }

    private suspend fun ProgressiveScanner.readField(
        delimiter: BooleanArray = tSpaceOrNl
    ): ByteBuffer {
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
        delimiter: BooleanArray = tSpaceOrNl
    ): String {
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

    private suspend fun ProgressiveScanner.parseOffset(
        sign: Int
    ): ZoneOffset? {
        var hours = 0
        var minutes = 0
        if (!readInt { v -> hours = v }) return null
        if (!skipByte(':'.code.toByte())) return null
        if (!readInt { v -> minutes = v }) return null
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
        if (!readInt { v -> year = v }) {
            if (readField() == nilBytes) {
                ts = null
                return true
            }
            return false
        }
        if (!skipByte('-'.code.toByte())) return false
        if (!readInt { v -> month = v }) return false
        if (!skipByte('-'.code.toByte())) return false
        if (!readInt { v -> day = v }) return false
        if (!skipByte('T'.code.toByte())) return false
        if (!readInt { v -> hour = v }) return false
        if (!skipByte(':'.code.toByte())) return false
        if (!readInt { v -> minute = v }) return false
        if (!skipByte(':'.code.toByte())) return false
        if (!readInt { v -> second = v }) return false
        if (skipByte('.'.code.toByte())) {
            var digits = 0
            if (readDigits { digits++; nanos = nanos * 10 + it } == 0L) {
                return false
            }
            if (digits > 9) return false
            for (i in digits.inc()..9) nanos *= 10
        }
        val z = when (readByte()) {
            'Z'.code.toByte().toInt() -> ZoneOffset.UTC
            '+'.code.toByte().toInt() -> parseOffset(1) ?: return false
            '-'.code.toByte().toInt() -> parseOffset(-1) ?: return false
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
        if (!input.skipByte('<'.code.toByte())) return false
        if (!input.readInt { v -> priValue = v }) {
            return false
        }
        if (!input.skipByte('>'.code.toByte())) return false

        //read version
        if (!input.skipByte('1'.code.toByte())) return false
        if (input.skip(tSpace) == 0L) return false

        //read ts
        if (!input.parseTs()) return false
        input.skip(tSpace)

        //read host
        cachedHost = input.readCachedField(cachedHost)
        host = cachedHost.second
        if (input.skip(tSpace) == 0L) return false

        //read app
        cachedApp = input.readCachedField(cachedApp)
        app = cachedApp.second
        if (input.skip(tSpace) == 0L) return false

        //read proc
        proc = let {
            var longValue = 0L
            when {
                input.readLong { v -> longValue = v } -> longValue
                input.readField() == nilBytes -> null
                else -> return false
            }
        }
        if (input.skip(tSpace) == 0L) return false

        //read msgid
        msgid = input.readField().let {
            if (it == nilBytes)
                null
            else {
                it.decodeToString()
            }
        }
        if (input.skip(tSpace) == 0L) return false

        //read sd
        var hasSd = false
        while (input.skipByte('['.code.toByte())) {
            hasSd = true
            //read id
            val id = input.readKey()
            input.skip(tSpace)
            //field kv pairs
            while (true) {
                if (input.skipByte(']'.code.toByte())) break
                val key = input.readKey(tEqOrCloseBracketOrNl)
                if (input.skipByte('='.code.toByte())) {
                    val tmp = InMemoryByteChannel()
                    if (input.skipByte('"'.code.toByte())) {
                        while (true) {
                            input.readUntil(tmp) {
                                it == '"'.code.toByte() ||
                                        it == '\\'.code.toByte() ||
                                        it == '\n'.code.toByte()
                            }
                            if (input.hasAvailable()) {
                                val c1 = input.readByte().toChar()
                                if (c1 == '"') break
                                if (c1 != '\\') return false
                                val c2 = input.readByte().toChar()
                                if (c2 != '\\' && c2 != '"' && c2 != ']') {
                                    tmp.write('\\'.code)
                                }
                                tmp.write(c2.code)
                            }
                            if (input.isClosed()) return false
                        }
                    } else {
                        while (true) {
                            input.readUntil(tmp, tCloseBracketOrSpaceOrNl)
                            if (input.hasAvailable()) break
                            if (input.isClosed()) return false
                        }
                    }
                    values[id.trim() to key.trim()] =
                        tmp.toString(Charsets.UTF_8)
                } else {
                    return false
                }
            }
        }
        if (!hasSd) {
            if (input.readField() != nilBytes) return false
        }
        input.skip(tSpace)

        return true
    }

}
