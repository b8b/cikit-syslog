<div width="100%" align="center">
<img src="https://raw.githubusercontent.com/b8b/cikit-syslog/master/logo.svg?sanitize=true">
</div>

# rfc 5424 syslog parser

```kotlin
import kotlinx.coroutines.*
import org.cikit.syslog.*
import java.io.FileInputStream
import java.nio.*
import java.nio.charset.*

fun main(args: Array<String>) = runBlocking<Unit> {
    val scanner = ProgressiveScanner()
    val p = SyslogParser()
    val tmp = InMemoryByteChannel()

    //feed input
    launch {
        val buffer = ByteBuffer.wrap(FileInputStream("logfile").readBytes())
        scanner.send(buffer)
        scanner.close()
    }

    //process log records
    while (p.parse5424(scanner)) {
        println("${p.ts()} ${p.host()}")
        if (scanner.readUntil(tmp) { it == '\n'.code.toByte() }) {
            scanner.readByte()        
        }
        println(tmp.toString(Charsers.UTF_8))
    }
    assert(scanner.isClosed())

    println("Done!")
}
```
