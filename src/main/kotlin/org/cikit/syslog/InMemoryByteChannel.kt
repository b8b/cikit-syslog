package org.cikit.syslog

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset

class InMemoryByteChannel : SeekableByteChannel {

    private var buffers: Array<ByteArray?> = arrayOf(ByteArray(32))
    private var position = 0L
    private var size = 0L
    private var closed = false

    override fun close() {
        closed = true
    }

    override fun isOpen(): Boolean = !closed

    private fun read0(dst: ByteBuffer, idx: Int, offset: Int, length: Int) {
        val src = buffers[idx]
        if (src == null) {
            if (dst.hasArray()) {
                dst.array().fill(0, dst.arrayOffset(), length)
                dst.position(dst.position() + length)
            } else {
                for (i in 0 until length) dst.put(0)
            }
        } else {
            dst.put(src, offset, length)
        }
    }

    override fun read(dst: ByteBuffer): Int {
        if (closed) throw ClosedChannelException()
        val thisRemaining = size - position
        if (thisRemaining <= 0) return -1
        val dstRemaining = dst.remaining()
        val bytesToCopy = if (dstRemaining > thisRemaining) {
            thisRemaining.toInt()
        } else {
            dstRemaining
        }
        var idx = (position shr BLOCK_POWER).toInt()
        val offset0 = (position and BLOCK_MASK).toInt()
        val length0 = minOf(bytesToCopy, BLOCK_SIZE - offset0)
        read0(dst, idx, offset0, length0)
        var bytesCopied = length0
        while (bytesCopied < bytesToCopy) {
            idx++
            val length1 = minOf(bytesToCopy - bytesCopied, BLOCK_SIZE)
            read0(dst, idx, 0, length1)
            bytesCopied += length1
        }
        position += bytesCopied
        return bytesCopied
    }

    private fun read0(
        dst: ByteArray,
        dstOffset: Int,
        idx: Int,
        offset: Int,
        length: Int
    ) {
        val src = buffers[idx]
        if (src == null) {
            dst.fill(0, dstOffset, length)
        } else {
            src.copyInto(dst, dstOffset, offset, offset + length)
        }
    }

    fun read(dst: ByteBuffer, position: Long): Int {
        if (closed) throw ClosedChannelException()
        val thisRemaining = size - position
        if (thisRemaining <= 0) return -1
        val dstRemaining = dst.remaining()
        val bytesToCopy = if (dstRemaining > thisRemaining) {
            thisRemaining.toInt()
        } else {
            dstRemaining
        }
        var idx = (position shr BLOCK_POWER).toInt()
        val offset0 = (position and BLOCK_MASK).toInt()
        val length0 = minOf(bytesToCopy, BLOCK_SIZE - offset0)
        read0(dst, idx, offset0, length0)
        var bytesCopied = length0
        while (bytesCopied < bytesToCopy) {
            idx++
            val length1 = minOf(bytesToCopy - bytesCopied, BLOCK_SIZE)
            read0(dst, idx, 0, length1)
            bytesCopied += length1
        }
        return bytesCopied
    }

    fun read(dst: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw ClosedChannelException()
        val thisRemaining = size - position
        if (thisRemaining <= 0) return -1
        val bytesToCopy = if (length > thisRemaining) {
            thisRemaining.toInt()
        } else {
            length
        }
        var idx = (position shr BLOCK_POWER).toInt()
        val offset0 = (position and BLOCK_MASK).toInt()
        val length0 = minOf(bytesToCopy, BLOCK_SIZE - offset0)
        read0(dst, offset, idx, offset0, length0)
        var bytesCopied = length0
        while (bytesCopied < bytesToCopy) {
            idx++
            val length1 = minOf(bytesToCopy - bytesCopied, BLOCK_SIZE)
            read0(dst, offset + bytesCopied, idx, 0, length1)
            bytesCopied += length1
        }
        position += bytesCopied
        return bytesCopied
    }

    fun read(): Int {
        if (closed) throw ClosedChannelException()
        if (position >= size) return -1
        val idx = (position shr BLOCK_POWER).toInt()
        val offset = (position and BLOCK_MASK).toInt()
        val buffer = buffers[idx]
        position++
        return if (buffer == null) 0 else buffer[offset].toInt()
    }

    fun read(dst: ByteArray, offset: Int, length: Int, position: Long): Int {
        if (closed) throw ClosedChannelException()
        val thisRemaining = size - position
        if (thisRemaining <= 0) return -1
        val bytesToCopy = if (length > thisRemaining) {
            thisRemaining.toInt()
        } else {
            length
        }
        var idx = (position shr BLOCK_POWER).toInt()
        val offset0 = (position and BLOCK_MASK).toInt()
        val length0 = minOf(bytesToCopy, BLOCK_SIZE - offset0)
        read0(dst, offset, idx, offset0, length0)
        var bytesCopied = length0
        while (bytesCopied < bytesToCopy) {
            idx++
            val length1 = minOf(bytesToCopy - bytesCopied, BLOCK_SIZE)
            read0(dst, offset + bytesCopied, idx, 0, length1)
            bytesCopied += length1
        }
        return bytesCopied
    }

    operator fun get(position: Long): Byte? {
        if (closed) throw ClosedChannelException()
        if (position >= size) return null
        val idx = (position shr BLOCK_POWER).toInt()
        val offset = (position and BLOCK_MASK).toInt()
        val buffer = buffers[idx]
        return if (buffer == null) 0 else buffer[offset]
    }

    private fun ensureCapacity(newCapacity: Long) {
        var numberOfBuffers = buffers.size.toLong()
        if (newCapacity <= numberOfBuffers * BLOCK_SIZE) return
        numberOfBuffers = numberOfBuffers shl 1
        while (newCapacity > numberOfBuffers * BLOCK_SIZE) {
            numberOfBuffers = numberOfBuffers shl 1
            if (numberOfBuffers > Integer.MAX_VALUE) {
                throw IllegalStateException()
            }
        }
        buffers = buffers.copyOf(numberOfBuffers.toInt())
    }

    private fun write0(src: ByteBuffer, idx: Int, offset: Int, length: Int) {
        val dst = buffers[idx]
        if (dst == null) {
            val newByteArray = ByteArray(BLOCK_SIZE)
            src.get(newByteArray, offset, length)
            buffers[idx] = newByteArray
            return
        }
        if (idx == 0 && dst.size < offset + length) {
            var newCapacity = dst.size shl 1
            while (offset + length > newCapacity) {
                newCapacity = newCapacity shl 1
                if (newCapacity > BLOCK_SIZE) {
                    throw IllegalArgumentException()
                }
            }
            val newByteArray = dst.copyOf(newCapacity)
            src.get(newByteArray, offset, length)
            buffers[0] = newByteArray
            return
        }
        src.get(dst, offset, length)
    }

    override fun write(src: ByteBuffer): Int {
        if (closed) throw ClosedChannelException()
        val length = src.remaining()
        if (length == 0) return 0
        ensureCapacity(position + length)
        var idx = (position shr BLOCK_POWER).toInt()
        val offset0 = (position and BLOCK_MASK).toInt()
        val length0 = minOf(length, BLOCK_SIZE - offset0)
        write0(src, idx, offset0, length0)
        var bytesCopied = length0
        while (bytesCopied < length) {
            idx++
            val length1 = minOf(length - bytesCopied, BLOCK_SIZE)
            write0(src, idx, 0, length1)
            bytesCopied += length1
        }
        position += bytesCopied
        if (position > size) size = position
        return bytesCopied
    }

    fun write(src: ByteBuffer, position: Long): Int {
        if (closed) throw ClosedChannelException()
        val length = src.remaining()
        if (length == 0) return 0
        ensureCapacity(position + length)
        var idx = (position shr BLOCK_POWER).toInt()
        val offset0 = (position and BLOCK_MASK).toInt()
        val length0 = minOf(length, BLOCK_SIZE - offset0)
        write0(src, idx, offset0, length0)
        var bytesCopied = length0
        while (bytesCopied < length) {
            idx++
            val length1 = minOf(length - bytesCopied, BLOCK_SIZE)
            write0(src, idx, 0, length1)
            bytesCopied += length1
        }
        if (position > size) size = position
        return bytesCopied
    }

    private fun write0(
        src: ByteArray,
        srcOffset: Int,
        idx: Int,
        offset: Int,
        length: Int
    ) {
        val dst = buffers[idx]
        if (dst == null) {
            val newByteArray = ByteArray(BLOCK_SIZE)
            src.copyInto(newByteArray, offset, srcOffset, srcOffset + length)
            buffers[idx] = newByteArray
            return
        }
        if (idx == 0 && dst.size < offset + length) {
            var newCapacity = dst.size shl 1
            while (offset + length > newCapacity) {
                newCapacity = newCapacity shl 1
                if (newCapacity > BLOCK_SIZE) throw IllegalArgumentException()
            }
            val newByteArray = dst.copyOf(newCapacity)
            src.copyInto(newByteArray, offset, srcOffset, srcOffset + length)
            buffers[idx] = newByteArray
            return
        }
        src.copyInto(dst, offset, srcOffset, srcOffset + length)
    }

    fun write(src: ByteArray, offset: Int, length: Int) {
        if (closed) throw ClosedChannelException()
        if (length == 0) return
        ensureCapacity(position + length)
        var idx = (position shr BLOCK_POWER).toInt()
        val offset0 = (position and BLOCK_MASK).toInt()
        val length0 = minOf(length, BLOCK_SIZE - offset0)
        write0(src, offset, idx, offset0, length0)
        var bytesCopied = length0
        while (bytesCopied < length) {
            idx++
            val length1 = minOf(length - bytesCopied, BLOCK_SIZE)
            write0(src, offset + bytesCopied, idx, 0, length1)
            bytesCopied += length1
        }
        position += bytesCopied
        if (position > size) size = position
    }

    fun write(src: ByteArray, offset: Int, length: Int, position: Long) {
        if (closed) throw ClosedChannelException()
        if (length == 0) return
        ensureCapacity(position + length)
        var idx = (position shr BLOCK_POWER).toInt()
        val offset0 = (position and BLOCK_MASK).toInt()
        val length0 = minOf(length, BLOCK_SIZE - offset0)
        write0(src, offset, idx, offset0, length0)
        var bytesCopied = length0
        while (bytesCopied < length) {
            idx++
            val length1 = minOf(length - bytesCopied, BLOCK_SIZE)
            write0(src, offset + bytesCopied, idx, 0, length1)
            bytesCopied += length1
        }
        if (position > size) size = position
    }

    fun write(b: Int) {
        if (closed) throw ClosedChannelException()
        ensureCapacity(position + 1)
        val idx = (position shr BLOCK_POWER).toInt()
        val offset0 = (position and BLOCK_MASK).toInt()
        val buffer = buffers[idx]
        when {
            buffer == null -> {
                val newBuffer = ByteArray(BLOCK_SIZE)
                newBuffer[offset0] = b.toByte()
                buffers[idx] = newBuffer
            }
            idx == 0 && buffer.size <= offset0 -> {
                var newCapacity = buffer.size shl 1
                while (offset0 >= newCapacity) {
                    newCapacity = newCapacity shl 1
                    if (newCapacity > BLOCK_SIZE) {
                        throw IllegalArgumentException()
                    }
                }
                val newByteArray = buffer.copyOf(newCapacity)
                newByteArray[offset0] = b.toByte()
                buffers[idx] = newByteArray
            }
            else -> buffer[offset0] = b.toByte()
        }
        position ++
        if (position > size) size = position
    }

    operator fun set(position: Long, b: Byte) {
        if (closed) throw ClosedChannelException()
        ensureCapacity(position + 1)
        val idx = (position shr BLOCK_POWER).toInt()
        val offset0 = (position and BLOCK_MASK).toInt()
        val buffer = buffers[idx]
        when {
            buffer == null -> {
                val newBuffer = ByteArray(BLOCK_SIZE)
                newBuffer[offset0] = b
                buffers[idx] = newBuffer
            }
            idx == 0 && buffer.size <= offset0 -> {
                var newCapacity = buffer.size shl 1
                while (offset0 >= newCapacity) {
                    newCapacity = newCapacity shl 1
                    if (newCapacity > BLOCK_SIZE) {
                        throw IllegalArgumentException()
                    }
                }
                val newByteArray = buffer.copyOf(newCapacity)
                newByteArray[offset0] = b
                buffers[idx] = newByteArray
            }
            else -> buffer[offset0] = b
        }
        if (position >= size) size = position + 1
    }

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        if (closed) throw ClosedChannelException()
        if (newPosition < 0L) throw IllegalArgumentException()
        position = newPosition
        return this
    }

    override fun size(): Long = size

    override fun truncate(size: Long): SeekableByteChannel {
        if (closed) throw ClosedChannelException()
        if (size < 0) throw IllegalArgumentException()
        if (size < this.size) {
            val needBuffers = (size shr BLOCK_POWER) +
                    if ((size and BLOCK_MASK) != 0L) 1 else 0
            if (needBuffers == 0L || needBuffers == 1L) {
                if (buffers.size != 1) buffers = buffers.copyOf(1)
            } else {
                var numberOfBuffers = buffers.size
                while (needBuffers < numberOfBuffers) {
                    val newNumberOfBuffers = numberOfBuffers shr 1
                    if (newNumberOfBuffers < needBuffers) break
                    numberOfBuffers = newNumberOfBuffers
                }
                if (numberOfBuffers != buffers.size) {
                    buffers = buffers.copyOf(numberOfBuffers)
                }
                for (idx in needBuffers until numberOfBuffers) {
                    buffers[idx.toInt()] = null
                }
            }
        } else if (size > this.size) {
            val idx = (this.size shr BLOCK_POWER).toInt()
            val offset = (this.size and BLOCK_MASK).toInt()
            val dst = buffers[idx]
            if (dst != null) {
                var length = dst.size - offset
                if (size - this.size < length) {
                    length = (size - this.size).toInt()
                }
                dst.fill(0, offset, length)
            }
        }
        this.size = size
        if (position > this.size) position = this.size
        return this
    }

    fun asInputStream(): InputStream = object : InputStream() {

        override fun read(): Int = this@InMemoryByteChannel.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int =
                this@InMemoryByteChannel.read(b, off, len)

        override fun read(b: ByteArray): Int =
                this@InMemoryByteChannel.read(b, 0, b.size)

        override fun skip(n: Long): Long {
            this@InMemoryByteChannel.position += n
            return n
        }

    }

    fun asOutputStream(): OutputStream = object : OutputStream() {

        override fun write(b: Int) = this@InMemoryByteChannel.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) =
                this@InMemoryByteChannel.write(b, off, len)

        override fun write(b: ByteArray) =
                this@InMemoryByteChannel.write(b, 0, b.size)

    }

    fun asByteBuffer(): ByteBuffer? {
        if (size > BLOCK_SIZE) return null
        return ByteBuffer.wrap(buffers[0], 0, size.toInt())
    }

    fun toByteArray(): ByteArray {
        if (size > Integer.MAX_VALUE) throw IllegalStateException()
        val bytesToCopy = size.toInt()
        var bytesCopied = 0
        val result = ByteArray(bytesToCopy)
        for (i in 0 until buffers.size) {
            val src = buffers[i]
            val length = minOf(bytesToCopy - bytesCopied, BLOCK_SIZE)
            if (src == null) {
                for (j in 0 until length) result[bytesCopied] = 0
            } else {
                src.copyInto(result, bytesCopied, 0, length)
            }
            bytesCopied += length
            if (bytesCopied == bytesToCopy) break
        }
        return result
    }

    fun toString(charset: Charset): String {
        if (size <= 0L) return ""
        if (size > BLOCK_SIZE) return String(toByteArray(), charset)
        val buffer = buffers[0] ?: return String(CharArray(size.toInt()))
        return String(buffer, 0, size.toInt(), charset)
    }

    fun contentToString() = toString(Charsets.UTF_8)

    companion object {
        const val BLOCK_POWER = 12
        const val BLOCK_SIZE = 1 shl BLOCK_POWER
        const val BLOCK_MASK = (BLOCK_SIZE - 1).toLong()
    }

}
