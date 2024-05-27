package org.cikit.syslog

import java.nio.ByteBuffer
import java.nio.charset.Charset

fun ByteBuffer.decodeToString(): String {
    if (hasArray()) {
        val data = array()
        val offset = arrayOffset() + position()
        return String(data, offset, remaining())
    } else {
        val data = ByteArray(remaining())
        get(data)
        return String(data)
    }
}

fun ByteBuffer.decodeToString(charset: Charset = Charsets.UTF_8): String {
    if (hasArray()) {
        val data = array()
        val offset = arrayOffset() + position()
        return String(data, offset, remaining(), charset)
    } else {
        val data = ByteArray(remaining())
        get(data)
        return String(data, charset)
    }
}
