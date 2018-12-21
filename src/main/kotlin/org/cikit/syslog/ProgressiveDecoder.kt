package org.cikit.syslog

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.*

class ProgressiveDecoder(val charset: Charset = Charsets.UTF_8) {

    private val decoder: CharsetDecoder = charset.newDecoder()
    private val tmp: ByteBuffer = ByteBuffer.allocate(10)

    private var bytesDecoded: Long = 0L
    private var charsDecoded: Long = 0L

    fun bytesDecoded() = bytesDecoded

    fun charsDecoded() = charsDecoded

    fun flush(dest: CharBuffer): ProgressiveCoderResult {
        //TBD call decode with endOfInput=true if needed?
        val cr = decoder.flush(dest)
        return when {
            cr.isUnderflow -> {
                ProgressiveCoderResult.Underflow
            }
            cr.isOverflow -> {
                ProgressiveCoderResult.Overflow
            }
            cr.isMalformed -> ProgressiveCoderResult.Malformed(cr.length())
            cr.isUnmappable -> ProgressiveCoderResult.Unmappable(cr.length())
            else -> throw IllegalStateException()
        }
    }

    fun reset() {
        bytesDecoded = 0L
        charsDecoded = 0L
        decoder.reset()
        tmp.clear()
    }

    private fun decodeTmp(src: ByteBuffer, dest: CharBuffer,
                          endOfInput: Boolean): Int {
        val tmpPosition = tmp.position()
        if (tmpPosition == 0) return 0
        val startIndex = src.position()
        val endIndex = src.limit()
        // fill tmp
        val tmpRemaining = tmp.remaining()
        for (i in 0 until minOf(tmpRemaining, endIndex - startIndex)) {
            tmp.put(src[startIndex + i])
        }
        // decode tmp
        tmp.flip()
        val destStartIndex = dest.position()
        val cr = decoder.decode(tmp, dest, endOfInput &&
                tmpRemaining >= endIndex - startIndex)
        val produced = dest.position() - destStartIndex
        charsDecoded += produced
        val consumedAlready = maxOf(0, tmp.position() - tmpPosition)
        if (consumedAlready > 0) {
            src.position(startIndex + consumedAlready)
            bytesDecoded += consumedAlready
        }
        when {
            cr.isUnderflow -> {
                val srcRemaining = tmp.remaining()
                if (srcRemaining > 0 && produced == 0) {
                    // need more bytes
                    tmp.position(tmp.limit())
                    tmp.limit(tmp.capacity())
                    if (!tmp.hasRemaining()) throw IllegalStateException()
                    return consumedAlready
                }
            }
            cr.isOverflow -> {
            }
            cr.isMalformed -> throw MalformedInputException(cr.length())
            cr.isUnmappable -> throw UnmappableCharacterException(cr.length())
            else -> throw IllegalStateException()
        }
        //TODO exceptions above avoid clearing tmp
        tmp.clear()
        return consumedAlready
    }

    private fun decode(src: ByteBuffer, dest: CharBuffer,
                       endOfInput: Boolean): Int {
        val consumedAlready = decodeTmp(src, dest, endOfInput)
        if (!dest.hasRemaining()) return consumedAlready
        val startIndex = src.position()
        val destStartIndex = dest.position()
        val cr = decoder.decode(src, dest, endOfInput)
        charsDecoded += dest.position() - destStartIndex
        val consumed = src.position() - startIndex
        bytesDecoded += consumed
        when {
            cr.isUnderflow -> {
                val srcRemaining = src.remaining()
                if (srcRemaining > 0) {
                    if (endOfInput) throw IllegalStateException()
                    if (!dest.hasRemaining()) return consumed
                    tmp.limit(9)
                    tmp.put(src)
                    tmp.limit(10)
                    return consumed + srcRemaining
                }
            }
            cr.isOverflow -> {
            }
            cr.isMalformed -> throw MalformedInputException(cr.length())
            cr.isUnmappable -> throw UnmappableCharacterException(cr.length())
            else -> throw IllegalStateException()
        }
        return consumed
    }

    fun decodeAvailable(src: ProgressiveScanner, dest: CharBuffer,
                        maxNumberOfBytes: Int = -1): Int {
        val remaining = dest.remaining()
        if (remaining <= 0) return 0
        val buffer = src.peekAvailable(max = maxNumberOfBytes) ?: return 0
        val consumed = decode(buffer, dest, false)
        src.skipAvailable(consumed)
        return consumed
    }

    fun decodeAvailableUntil(src: ProgressiveScanner, dest: CharBuffer,
                             predicate: (Byte) -> Boolean,
                             maxNumberOfBytes: Int = -1): Int {
        val remaining = dest.remaining()
        if (remaining <= 0) return 0
        val found = src.peekAvailableUntil(predicate, max = maxNumberOfBytes)
        if (found != null) {
            val consumed = decode(found, dest, true)
            src.skipAvailable(consumed)
            return consumed
        }
        return decodeAvailable(src, dest, maxNumberOfBytes)
    }

    suspend fun decodeUntilEnd(src: ProgressiveScanner, dest: CharBuffer): Int {
        var n = 0
        while (true) {
            val decoded = decodeAvailable(src, dest)
            if (decoded == 0) {
                if (!dest.hasRemaining()) break
                if (!src.receive()) break
                continue
            }
            n += decoded
        }
        return n
    }

    suspend fun decodeUntil(src: ProgressiveScanner, dest: CharBuffer,
                            predicate: (Byte) -> Boolean): Int {
        var n = 0
        while (true) {
            val decoded = decodeAvailableUntil(src, dest, predicate)
            if (decoded == 0) {
                if (!dest.hasRemaining()) break
                if (src.hasAvailable()) break
                if (!src.receive()) break
                continue
            }
            n += decoded
            if (src.hasAvailable()) break
        }
        return n
    }

    fun onMalformedInput(newAction: CodingErrorAction) {
        decoder.onMalformedInput(newAction)
    }

    fun onUnmappableCharacter(newAction: CodingErrorAction) {
        decoder.onUnmappableCharacter(newAction)
    }

}
