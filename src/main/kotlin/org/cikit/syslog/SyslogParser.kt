package org.cikit.syslog

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

    private fun isSpaceOrNl(b: Byte) = b == ' '.toByte() ||
            b == '\t'.toByte() ||
            b == '\b'.toByte() ||
            b == '\n'.toByte()

    private suspend fun readField(tmp: ByteBuffer,
                                  predicate: (Byte) -> Boolean = ::isSpaceOrNl): ByteBuffer {
        return when {
            readUntil(predicate) -> dup()
            else -> tmp.also {
                it.clear()
                it.put(dup())
                while (true) {
                    val result = readUntil(predicate)
                    it.put(dup())
                    if (result) break
                }
                it.flip()
            }
        }
    }

    private suspend fun readKey(tmp: ByteBuffer, decoder: CharsetDecoder,
                                predicate: (Byte) -> Boolean = ::isSpaceOrNl): String {
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
        if (!readDigits { hours = hours * 10 + it }) return null
        if (!skipPrefix(':'.toByte())) return null
        if (!readDigits { minutes = minutes * 10 + it }) return null
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
        if (!readDigits { year = year * 10 + it }) {
            if (readField(tmp) == nilBytes) {
                ts = null
                return true
            }
            return false
        }
        if (!skipPrefix('-'.toByte())) return false
        if (!readDigits { month = month * 10 + it }) return false
        if (!skipPrefix('-'.toByte())) return false
        if (!readDigits { day = day * 10 + it }) return false
        if (!skipPrefix('T'.toByte())) return false
        if (!readDigits { hour = hour * 10 + it }) return false
        if (!skipPrefix(':'.toByte())) return false
        if (!readDigits { minute = minute * 10 + it }) return false
        if (!skipPrefix(':'.toByte())) return false
        if (!readDigits { second = second * 10 + it }) return false
        if (skipPrefix('.'.toByte())) {
            var digits = 0
            if (!readDigits { digits++; nanos = nanos * 10 + it }) return false
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
        if (!readDigits { priValue = priValue * 10 + it }) return false
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
                    bytes.position(0)
                    val chars = decoder.decode(bytes).toString()
                    bytes.position(0)
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
                    bytes.position(0)
                    val chars = decoder.decode(bytes).toString()
                    bytes.position(0)
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
                readDigits { longValue = longValue * 10 + it } -> longValue
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
                val key = readKey(tmp, decoder) {
                    it == '='.toByte() ||
                            it == ']'.toByte() ||
                            it == '\n'.toByte()
                }
                if (skipPrefix('='.toByte())) {
                    decoder.reset()
                    val cb = CharBuffer.allocate(1024)
                    val value = StringBuilder()
                    if (skipPrefix('"'.toByte())) {
                        while (true) {
                            val result = decodeUntil(cb, tmp, decoder) {
                                it == '"'.toByte() ||
                                        it == '\\'.toByte() ||
                                        it == '\n'.toByte()
                            }
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
                            val result = decodeUntil(cb, tmp, decoder) {
                                isSpace(it) ||
                                        it == ']'.toByte() ||
                                        it == '\n'.toByte()
                            }
                            cb.flip()
                            value.append(cb)
                            cb.clear()
                            if (result) break
                            if (isClosed()) return false
                        }
                    }
                    cb.clear()
                    val cr = decoder.flush(cb)
                    when {
                        cr.isUnderflow -> {
                            if (cb.position() > 0) {
                                cb.flip()
                                value.append(cb)
                            }
                        }
                        cr.isMalformed -> throw MalformedInputException(cr.length())
                        cr.isUnmappable -> throw UnmappableCharacterException(cr.length())
                        else -> throw IllegalStateException()
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
        val result = skip { it != '\n'.toByte() }
        if (result) readByte()
    }

}