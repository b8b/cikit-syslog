import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.cikit.syslog.Facility
import org.cikit.syslog.Severity
import org.cikit.syslog.SyslogParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime

class TestSyslogParser {

    @Test
    fun testBasic() {
        val p = SyslogParser()
        launch(Unconfined) {
            p.send(ByteBuffer.wrap(javaClass.getResourceAsStream("/sample.log").readBytes()))
            p.close()
        }
        runBlocking {
            var counter = 0
            while (p.parse5424()) {
                counter++
                assertEquals("localhost", p.host())
                p.skipMessage()
            }
            assertEquals(4, counter)
            assertTrue(p.isClosed())
        }
    }

    @Test
    fun testDecode() {
        val p = SyslogParser()
        launch(Unconfined) {
            val input = ByteBuffer.wrap("<123>1 2018-01-01T00:00:00Z localhost myapp 1 - [x@1 test=1\u1234] msg1".toByteArray())
            input.limit(input.remaining() - 7)
            p.send(input)
            input.limit(input.capacity())
            input.position(input.limit() - 7)
            p.send(input)
            p.close()
        }
        runBlocking {
            p.parse5424()
            assertEquals(123, p.priValue())
            assertEquals(Facility.CRON2, p.facility())
            assertEquals(Severity.ERR, p.severity())
            assertEquals(OffsetDateTime.parse("2018-01-01T00:00:00Z"), p.ts())
            assertEquals("localhost", p.host())
            assertEquals("myapp", p.app())
            assertEquals(1L, p.proc())
            assertNull(p.msgid())
            assertEquals("1\u1234", p.sd("x@1", "test"))
            p.skipMessage()
            assertTrue(p.isClosed())
        }
    }

    @Test
    fun testDecodeMalformed() {
        val p = SyslogParser()
        launch(Unconfined) {
            val input = ByteBuffer.wrap("<123>1 2018-01-01T00:00:00Z localhost myapp 1 - [x@1 test=1\u1234] msg1".toByteArray())
            input.limit(input.remaining() - 7)
            p.send(input)
            input.limit(input.capacity())
            input.position(input.limit() - 6)
            p.send(input)
            p.close()
        }
        runBlocking {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.onMalformedInput(CodingErrorAction.IGNORE)
            decoder.onUnmappableCharacter(CodingErrorAction.REPLACE)
            p.parse5424(decoder = decoder)
            assertEquals("localhost", p.host())
            assertEquals("1", p.sd("x@1", "test"))
            p.skipMessage()
            assertTrue(p.isClosed())
        }
    }

}