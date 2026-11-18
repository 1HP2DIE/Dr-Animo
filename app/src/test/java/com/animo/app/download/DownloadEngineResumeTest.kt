package com.animo.app.download

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.StreamContainer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Resume rules for the progressive path.
 *
 * The dangerous case is a part file that looks finished: the chunked strategy pre-sizes its file to
 * the full length, so a leftover of a plausible size must not be believed just because the server
 * answers 416. These drive the real engine against a hand-written HTTP server (the JVM's
 * `com.sun.net.httpserver` is not on the Android unit-test classpath).
 */
class DownloadEngineResumeTest {

    private val body = ByteArray(4_000) { (it % 251).toByte() }

    private lateinit var directory: File
    private lateinit var server: TestServer

    /** The total the server claims in `Content-Range` for a 416. */
    private var claimedTotal = body.size.toLong()

    /** When true the server answers a ranged request with the whole file, like a CDN that ignores Range. */
    @Volatile
    private var ignoresRange = false

    @Volatile
    private var plainGets = 0

    @Before
    fun setUp() {
        directory = File.createTempFile("animo-download", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        server = TestServer { range ->
            if (range != null && !ignoresRange) {
                Reply(
                    status = "416 Range Not Satisfiable",
                    headers = listOf("Content-Range" to "bytes */$claimedTotal"),
                    body = ByteArray(0),
                )
            } else {
                plainGets++
                Reply("200 OK", listOf("Content-Length" to body.size.toString()), body)
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
        directory.deleteRecursively()
    }

    private fun stream() = PlayableStream(
        masterUrl = "http://127.0.0.1:${server.port}/ep.mp4",
        referer = "",
        userAgent = "test",
        audio = AudioTrack.SUB,
        container = StreamContainer.PROGRESSIVE,
    )

    private fun engine() = DownloadEngine(OkHttpClient())

    private fun download() = runBlocking {
        engine().download(stream(), 0, directory, { "Ep 1 [SUB Original]" }) { _, _, _ -> }
    }

    @Test
    fun `a part the server does not agree with is thrown away, not renamed`() {
        // 3 000 bytes of a different encode: the server says the file is 4 000.
        val part = File(directory, "Ep 1 [SUB Original].stream.part")
        part.writeBytes(ByteArray(3_000) { 7 })
        claimedTotal = body.size.toLong()

        val result = download()

        assertEquals(body.size.toLong(), result.file.length())
        assertArrayEquals(body, result.file.readBytes())
        assertEquals(1, plainGets)
    }

    @Test
    fun `a part the server agrees with is kept as complete`() {
        val part = File(directory, "Ep 1 [SUB Original].stream.part")
        part.writeBytes(body)
        claimedTotal = body.size.toLong()

        val result = download()

        assertEquals(body.size.toLong(), result.file.length())
        assertArrayEquals(body, result.file.readBytes())
        // Nothing was fetched: the 416 named the very same total.
        assertEquals(0, plainGets)
    }

    @Test
    fun `a host that ignores Range is downloaded whole instead of duplicated`() {
        ignoresRange = true
        val part = File(directory, "Ep 1 [SUB Original].stream.part")
        part.writeBytes(ByteArray(1_000) { 3 })

        val result = download()

        assertEquals(body.size.toLong(), result.file.length())
        assertArrayEquals(body, result.file.readBytes())
    }

    private class Reply(
        val status: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    )

    /** Bare-bones HTTP/1.1 server: one request per connection, `Connection: close`. */
    private class TestServer(private val reply: (rangeHeader: String?) -> Reply) : Closeable {

        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort

        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use(::handle)
                } catch (_: Exception) {
                    // Closed while accepting: the test is over.
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        private fun handle(client: Socket) {
            client.soTimeout = 5_000
            val input = client.getInputStream()
            var range: String? = null
            val requestLine = input.readHttpLine().orEmpty()
            while (true) {
                val header = input.readHttpLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Range:", ignoreCase = true)) range = header.substringAfter(':').trim()
            }
            check(requestLine.startsWith("GET ")) { "unexpected request: $requestLine" }
            val answer = reply(range)
            val out = client.getOutputStream()
            out.write(
                buildString {
                    append("HTTP/1.1 ${answer.status}\r\n")
                    answer.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                    append("Content-Length: ${answer.body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(),
            )
            out.write(answer.body)
            out.flush()
        }

        override fun close() {
            socket.close()
            worker.join(1_000)
        }

        private fun InputStream.readHttpLine(): String? {
            val line = StringBuilder()
            while (true) {
                val byte = read()
                if (byte < 0) return if (line.isEmpty()) null else line.toString()
                when (byte) {
                    '\r'.code -> Unit
                    '\n'.code -> return line.toString()
                    else -> line.append(byte.toChar())
                }
            }
        }
    }
}
