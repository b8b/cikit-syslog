package benchmark

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.cikit.syslog.ProgressiveScanner
import org.cikit.syslog.SyslogParser
import org.junit.jupiter.api.Assertions.*
import org.openjdk.jmh.annotations.*
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

@Fork(1, jvmArgsPrepend = ["-XX:+UseSerialGC"])
@Warmup(iterations = 10)
@Measurement(iterations = 15)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class SyslogParser5Benchmark {

    private suspend fun ProgressiveScanner.skipMessage() {
        skip { it != '\n'.code.toByte() }
        readByte()
    }

    @Benchmark
    open fun syslogParser() {
        val buffer: ByteBuffer = setupSampleData()
        val sampleDataLength = buffer.limit()
        for (i in 0 until 100) {
            runBlocking {
                val scanner = ProgressiveScanner()
                val p = SyslogParser()
                launch {
                    buffer.position(0)
                    buffer.limit(1024)
                    scanner.send(buffer)
                    buffer.limit(sampleDataLength)
                    buffer.position(1024)
                    scanner.send(buffer)
                    scanner.close()
                }
                var counter = 0
                while (p.parse5424(scanner)) {
                    counter++
                    scanner.skipMessage()
                }
                assertEquals(32, counter)
            }
        }
    }

}
