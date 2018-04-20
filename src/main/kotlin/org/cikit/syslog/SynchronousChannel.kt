package org.cikit.syslog

import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import java.nio.ByteBuffer

class SynchronousChannel {

    private var continueFill: CancellableContinuation<Unit>? = null
    private var continueReceive: CancellableContinuation<ByteBuffer?>? = null

    private var channelClosed = false

    fun isClosed() = channelClosed

    suspend fun send(element: ByteBuffer) {
        if (channelClosed) throw IllegalStateException()
        if (continueReceive == null) {
            suspendCancellableCoroutine<Unit> { cont ->
                continueFill = cont
            }
        }
        val continueReceive = this.continueReceive ?: throw IllegalStateException()
        this.continueReceive = null
        suspendCancellableCoroutine<Unit> { cont ->
            continueFill = cont
            continueReceive.resume(element)
        }
    }

    suspend fun close() {
        if (channelClosed) return
        if (continueReceive == null) {
            suspendCancellableCoroutine<Unit> { cont ->
                continueFill = cont
            }
        }
        val continueReceive = this.continueReceive ?: throw IllegalStateException()
        this.continueReceive = null
        suspendCancellableCoroutine<Unit> { cont ->
            continueFill = cont
            continueReceive.resume(null)
        }
        if (!channelClosed) throw IllegalStateException()
    }

    suspend fun receive(): ByteBuffer? {
        if (channelClosed) return null
        val element = suspendCancellableCoroutine<ByteBuffer?> { cont ->
            continueReceive = cont
            continueFill?.let {
                continueFill = null
                it.resume(Unit)
            }
        }
        if (element == null) {
            channelClosed = true
            continueFill?.let {
                continueFill = null
                it.resume(Unit)
            }
            return null
        }
        return element
    }

}