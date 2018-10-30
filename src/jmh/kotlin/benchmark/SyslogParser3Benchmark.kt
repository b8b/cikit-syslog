package benchmark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.cikit.syslog.SyslogParser
import org.junit.Assert
import org.openjdk.jmh.annotations.*
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

@Fork(1, jvmArgsPrepend = ["-XX:+UseSerialGC"])
@Warmup(iterations = 10)
@Measurement(iterations = 15)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class SyslogParser3Benchmark {

    @Benchmark
    open fun syslogParser3() {
        val buffer: ByteBuffer = setupSampleData()
        val sampleDataLength = buffer.limit()
        for (i in 0 until 100) {
            val p = SyslogParser()
            GlobalScope.launch(Dispatchers.Unconfined) {
                buffer.position(0)
                buffer.limit(1024)
                p.send(buffer)
                buffer.limit(sampleDataLength)
                buffer.position(1024)
                p.send(buffer)
                p.close()
            }
            runBlocking {
                var counter = 0
                while (p.parse5424()) {
                    counter++
                    p.skipMessage()
                }
                Assert.assertEquals(32, counter)
            }
        }
    }

}
