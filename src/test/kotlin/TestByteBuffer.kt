import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class TestByteBuffer {

    @Test
    fun testEquals() {
        val b1 = ByteBuffer.allocate(4)
        val b2 = ByteBuffer.allocate(4)

        listOf(b1, b2).forEach { b ->
            b.put(1.toByte())
            b.put(2.toByte())
            b.put(3.toByte())
            b.put(4.toByte())
        }

        assertTrue(b1 == b2)

        b1.position(2)
        assertFalse(b1 == b2)

        b2.position(2)
        assertTrue(b1 == b2)

        val b3 = ByteBuffer.wrap(byteArrayOf())
        b2.position(b2.limit())
        assertTrue(b2 == b3)
    }
}