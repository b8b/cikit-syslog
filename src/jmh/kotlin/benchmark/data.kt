package benchmark

import java.nio.ByteBuffer

fun setupSampleData(): ByteBuffer {
    val buffer: ByteBuffer = ByteBuffer.allocate(1024 * 2)
    arrayOf("""<123>1 2018-01-01T00:00:00Z localhost myapp 1 - [blah@1234 data="1234\"5678"] msg1""",
            """<123>1 2018-01-01T00:00:18Z localhost myapp 1 - - msg02""",
            """<123>1 2018-01-01T00:01:00Z localhost myapp 1 - - msg003""",
            """<123>1 2018-01-01T01:00:00Z localhost myapp 1 - - msg0004""")
            .forEach { line ->
                buffer.put(line.toByteArray(Charsets.UTF_8))
                buffer.put('\n'.toByte())
            }
    val sampleLength = buffer.position()
    val repeat = buffer.duplicate()
    for (i in sampleLength until buffer.limit() step sampleLength) {
        repeat.position(0)
        repeat.limit(minOf(buffer.remaining(), sampleLength))
        buffer.put(repeat)
    }
    buffer.flip()
    return buffer
}
