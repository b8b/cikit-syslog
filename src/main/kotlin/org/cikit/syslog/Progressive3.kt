package org.cikit.syslog

import java.nio.ByteBuffer

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

}
