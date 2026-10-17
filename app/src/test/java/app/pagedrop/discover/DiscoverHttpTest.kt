package app.pagedrop.discover

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverHttpTest {
    @Test fun networkDeadlineStopsAnUnresponsiveSocket() {
        ServerSocket(0).use { server ->
            val connected = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                server.accept().use {
                    connected.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            try {
                val http = OkHttpDiscoverHttp().withTimeout(200)
                val started = System.nanoTime()
                val failure = runCatching { http.get("http://127.0.0.1:${server.localPort}/") }.exceptionOrNull()
                assertTrue(connected.await(1, TimeUnit.SECONDS))
                assertTrue(failure is IOException)
                assertTrue("The socket ignored its request deadline", (System.nanoTime() - started) / 1_000_000 < 2_000)
            } finally {
                release.countDown()
                worker.join(1_000)
            }
        }
    }
}
