import org.cikit.syslog.InMemoryByteChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class TestInMemoryByteChannel {

    @Test
    fun testGetSet() {
        val x = InMemoryByteChannel()
        x[10_000] = 1
        assertEquals(0.toByte(), x[0])
        assertEquals(1.toByte(), x[10_000])
        assertNull(x[10_001])
    }

    @Test
    fun testPIO() {
        val x = InMemoryByteChannel()
        x[10_000] = 1
        val tmp = ByteArray(100)
        val n = x.read(ByteBuffer.wrap(tmp), 10_000)
        assertEquals(1, n)
        assertEquals(1.toByte(), tmp[0])
        val n2 = x.read(tmp, 0, 10, 9_991)
        assertEquals(10, n2)
        assertEquals(0.toByte(), tmp[0])
        assertEquals(1.toByte(), tmp[9])
        x.write(tmp, 0, 10, 9_990)
        assertEquals(1.toByte(), x[10_000])
        assertEquals(1.toByte(), x[9_999])
        x.write(ByteBuffer.wrap(tmp), 10)
        assertEquals(1.toByte(), x[19])
    }

}
