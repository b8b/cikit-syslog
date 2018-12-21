import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.cikit.syslog.Facility
import org.cikit.syslog.ProgressiveScanner
import org.cikit.syslog.Severity
import org.cikit.syslog.SyslogParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.time.OffsetDateTime

class TestSyslogParser {

    private suspend fun ProgressiveScanner.skipMessage() {
        skip { it != '\n'.toByte() }
        readByte()
    }

    @Test
    fun testBasic() = runBlocking {
        val scanner = ProgressiveScanner()
        val p = SyslogParser()
        launch {
            scanner.send(ByteBuffer.wrap(javaClass.getResourceAsStream("/sample.log").readBytes()))
            scanner.close()
        }
        var counter = 0
        while (p.parse5424(scanner)) {
            counter++
            assertEquals("localhost", p.host())
            scanner.skipMessage()
        }
        assertEquals(4, counter)
        assertTrue(scanner.isClosed())
    }

    @Test
    fun testDecode() = runBlocking {
        val scanner = ProgressiveScanner()
        val p = SyslogParser()
        launch {
            val input = ByteBuffer.wrap("<123>1 2018-01-01T00:00:00Z localhost myapp 1 - [x@1 test=1\u1234] msg1".toByteArray())
            input.limit(input.remaining() - 7)
            scanner.send(input)
            input.limit(input.capacity())
            input.position(input.limit() - 7)
            scanner.send(input)
            scanner.close()
        }
        p.parse5424(scanner)
        assertEquals(123, p.priValue())
        assertEquals(Facility.CRON2, p.facility())
        assertEquals(Severity.ERR, p.severity())
        assertEquals(OffsetDateTime.parse("2018-01-01T00:00:00Z"), p.ts())
        assertEquals("localhost", p.host())
        assertEquals("myapp", p.app())
        assertEquals(1L, p.proc())
        assertNull(p.msgid())
        assertEquals("1\u1234", p.sd("x@1", "test"))
        scanner.skipMessage()
        assertTrue(scanner.isClosed())
    }

    @Test
    fun testDecodeMalformed() = runBlocking {
        val scanner = ProgressiveScanner()
        val p = SyslogParser()
        launch {
            val input = ByteBuffer.wrap("<123>1 2018-01-01T00:00:00Z localhost myapp 1 - [x@1 test=1\u1234] msg1".toByteArray())
            input.limit(input.remaining() - 7)
            scanner.send(input)
            input.limit(input.capacity())
            input.position(input.limit() - 6)
            scanner.send(input)
            scanner.close()
        }
        p.parse5424(scanner)
        assertEquals("localhost", p.host())
        assertEquals("1\uFFFD", p.sd("x@1", "test"))
        scanner.skipMessage()
        assertTrue(scanner.isClosed())
    }

}
