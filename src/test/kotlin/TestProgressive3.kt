import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.cikit.syslog.Progressive3
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class TestProgressive3 {

    class TestParser : Progressive3() {

        var msg: String = ""

        suspend fun parse(): Boolean {
            while (startIndex >= endIndex) {
                if (!receive()) return false
            }
            msg = Charsets.UTF_8.decode(dup()).toString().trim()
            startIndex = endIndex
            return true
        }

    }

    private suspend fun fill(p: Progressive3) {
        javaClass.getResourceAsStream("/sample.log").use { `in` ->
            `in`.reader().useLines { lines ->
                val buffer = ByteBuffer.allocate(1024 * 4)
                for (line in lines) {
                    buffer.clear()
                    buffer.put("$line\n".toByteArray())
                    buffer.flip()
                    p.send(buffer)
                }
                p.close()
            }
        }
    }

    private suspend fun parse(p: TestParser) {
        val messages = mutableListOf<String>()
        while (p.parse()) {
            messages.add(p.msg)
        }
        assertTrue(messages.removeAt(0).endsWith("msg1"))
        assertTrue(messages.removeAt(0).endsWith("msg02"))
        assertTrue(messages.removeAt(0).endsWith("msg003"))
        assertTrue(messages.removeAt(0).endsWith("msg0004"))
        assertTrue(messages.isEmpty())
    }

    @Test
    fun testFillFirst() {
        val p = TestParser()
        launch(Unconfined) {
            fill(p)
        }
        runBlocking {
            parse(p)
        }
        assertTrue(p.isClosed())
    }

    @Test
    fun testParseFirst() {
        val p = TestParser()
        launch(Unconfined) {
            parse(p)
        }
        runBlocking {
            fill(p)
        }
        assertTrue(p.isClosed())
    }

}