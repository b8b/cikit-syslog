import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.cikit.syslog.InMemoryByteChannel
import org.cikit.syslog.ProgressiveScanner
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.Charset

class TestProgressiveScanner {

    private fun isNl(b: Byte) = b == '\n'.toByte()

    private fun ByteBuffer.contentToString() = toString(Charsets.UTF_8)

    private fun ByteBuffer.toString(charset: Charset) = if (hasArray()) {
        String(array(), arrayOffset() + position(), remaining(), charset)
    } else {
        String(ByteArray(remaining()).also {
            val pos = position()
            try {
                get(it)
            } finally {
                position(pos)
            }
        }, charset)
    }

    private suspend fun ProgressiveScanner.readLine(tmp: InMemoryByteChannel = InMemoryByteChannel()): String? {
        val line = readAvailableUntil(::isNl)
        if (line != null) {
            val lineStr = line.contentToString()
            skipAvailable(1)
            return lineStr
        } else {
            val result = readUntil(tmp, ::isNl)
            val lineStr = tmp.contentToString()
            tmp.truncate(0)
            if (result) skipAvailable(1)
            else if (lineStr.isEmpty()) return null
            return lineStr
        }
    }

    @Test
    fun testReadLine() = runBlocking {
        val scanner = ProgressiveScanner()
        launch {
            scanner.send(ByteBuffer.wrap("hello line 1\n".toByteArray()))
            scanner.send(ByteBuffer.wrap("hello line 2\n".toByteArray()))
            scanner.close()
        }
        val tmp = InMemoryByteChannel()
        while (true) {
            val line = scanner.readLine(tmp) ?: break
            println(line)
        }
    }
}
