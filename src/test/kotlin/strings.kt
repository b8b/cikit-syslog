import org.cikit.syslog.InMemoryByteChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.random.Random

class TestStrings {

    private val length = 10_000_000

    @Test
    fun testStringBuilder() {
        val sb = StringBuilder()
        for (i in 0 until length) {
            sb.append('a')
        }
        val result = sb.toString()
        assertEquals(length, result.length)
        assertTrue(result.all { it == 'a' })
    }

    @Test
    fun testInMemoryByteChannel() {
        val rnd = Random(18)
        val length = 10_000
        val out = InMemoryByteChannel()
        val out2 = ByteArrayOutputStream()
        for (i in 0 until length) {
            val src = ByteArray(1000) {
                rnd.nextInt(32, 127).toByte()
            }
            out.write(ByteBuffer.wrap(src))
            out2.write(src)
        }
        val result = out.toString(Charsets.UTF_8)
        val chk = String(out2.toByteArray(), Charsets.UTF_8)
        assertEquals(chk, result)
    }

    @Test
    fun testInMemoryByteChannelAsOutputStream() {
        val rnd = Random(18)
        val length = 10_000
        val tmp = InMemoryByteChannel()
        val out = tmp.asOutputStream()
        val out2 = ByteArrayOutputStream()
        for (i in 0 until length) {
            val src = ByteArray(1000) {
                rnd.nextInt(32, 127).toByte()
            }
            out.write(src)
            out2.write(src)
        }
        val result = String(tmp.toByteArray(), Charsets.UTF_8)
        val chk = String(out2.toByteArray(), Charsets.UTF_8)
        assertEquals(chk, result)
    }

    @Test
    fun testInMemoryByteChannelAsInputStream() {
        val rnd = Random(18)
        val length = 10_000
        val tmp = InMemoryByteChannel()
        val out = tmp.asOutputStream()
        val out2 = ByteArrayOutputStream()
        for (i in 0 until length) {
            val src = ByteArray(1000) {
                rnd.nextInt(32, 127).toByte()
            }
            out.write(src)
            out2.write(src)
        }
        ByteArrayInputStream(out2.toByteArray()).use { chk ->
            tmp.position(0)
            tmp.asInputStream().use { `in` ->
                while (true) {
                    val b = `in`.read()
                    assertEquals(chk.read(), b)
                    if (b < 0) break
                }
            }
            Unit
        }
    }

}