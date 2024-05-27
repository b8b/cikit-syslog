package benchmark

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.cikit.syslog.ProgressiveScanner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openjdk.jmh.annotations.*
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@Fork(1, jvmArgsPrepend = ["-XX:+UseSerialGC"])
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class ReadIntBenchmark {

    private fun sampleData() = buildString {
        repeat(100000) {
            appendLine("8231746")
        }
    }.toByteArray()

    @Benchmark
    @Test
    open fun  readIntIntoLambda() {
        val scanner = ProgressiveScanner()
        runBlocking {
            launch {
                val `in` = ByteArrayInputStream(sampleData())
                val buffer = ByteArray(1024 * 4)
                while (true) {
                    val read = `in`.read(buffer)
                    if (read < 0) {
                        break
                    }
                    scanner.send(ByteBuffer.wrap(buffer, 0, read))
                }
                scanner.close()
            }
            repeat(100000) {
                var a: Int = 0
                val result = scanner.readInt { a = it }
                assertTrue(result)
                assertEquals(8231746, a)
                scanner.skipByte('\n'.code.toByte())
            }
            val end = scanner.readByte()
            assertEquals(-1, end)
        }
    }

    @Benchmark
    @Test
    open fun  readBlocking() {
        val reader = ByteArrayInputStream(sampleData()).bufferedReader()
        for (line in reader.lineSequence()) {
            val a = line.toInt()
            assertEquals(8231746, a)
        }
    }

}