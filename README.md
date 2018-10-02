<div width="100%" align="center">
<img src="https://raw.githubusercontent.com/b8b/cikit-syslog/master/logo.svg?sanitize=true">
</div>

# rfc 5424 syslog parser

```kotlin
import kotlinx.coroutines.*
import org.cikit.syslog.SyslogParser
import java.io.FileInputStream
import java.nio.*
import java.nio.charset.*

fun main(args: Array<String>) = runBlocking<Unit> {
    val p = SyslogParser()

    //feed input
    launch {
        val buffer = ByteBuffer.wrap(FileInputStream("logfile").readBytes())
        p.send(buffer)
        p.close()
    }

    //process log records
    while (p.parse5424()) {
        println("${p.ts()} ${p.host()}")
        p.skipMessage()
    }
    assert(p.isClosed())

    println("Done!")
}
```

# decoding message part

```kotlin
val tmp = ByteBuffer.allocate(10)
val cb = CharBuffer.allocate(1024)
val decoder = Charsets.UTF_8.newDecoder()
decoder.onMalformedInput(CodingErrorAction.IGNORE)
decoder.onUnmappableCharacter(CodingErrorAction.REPLACE)
while (p.parse5424()) {
    print("${p.ts()} ${p.host()} - ")
    while (true) {
        val result = p.decodeUntil(cb, tmp, decoder, { it == '\n'.toByte() })
        cb.flip()
        print(cb)
        cb.clear()
        if (result) break
        if (p.isClosed()) {
            val empty = ByteBuffer.wrap(byteArrayOf())
            decoder.decode(empty, cb, true)
            break
        }
    }
    decoder.flush(cb)
    if (cb.position() > 0) {
        cb.flip()
        println(cb)
    }
    p.skipMessage()
}
assert(p.isClosed())
```
