package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * Live check against the real alternative source. **Manual**: CI has no network and no secrets.
 * The shard list is read from the git-ignored `secrets/providers.properties`; on a machine that does
 * not have it the test is skipped rather than failed.
 */
@Ignore("live network — run manually")
class AltStreamLiveTest {

    private val secrets = File("../secrets/providers.properties")
        .takeIf { it.isFile }
        ?: File("secrets/providers.properties").takeIf { it.isFile }

    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    @Before
    fun installVault() {
        assumeTrue("secrets/providers.properties is not present", secrets != null)
        val props = Properties().apply { secrets!!.inputStream().use { load(it) } }
        ProviderVault.installForTests(
            props.stringPropertyNames().associateWith { props.getProperty(it) },
        )
    }

    private fun episode(number: Int) = Episode(
        id = 1L,
        animeId = 21,
        number = number,
        title = "Episode $number",
    )

    @Test
    fun `one piece episode one resolves to a proxied playlist`() = runBlocking {
        val source = AltStreamSource(client)
        val stream = withTimeout(180_000) {
            source.resolve("21", episode(1), AudioTrack.DUB)
        }.getOrThrow()

        assertTrue(
            "expected a proxy playlist, got ${stream.masterUrl}",
            stream.masterUrl.contains("/api/core/proxy"),
        )
        assertTrue(stream.userAgent.isNotBlank())

        // The playlist itself must be real HLS, and the proxy must declare TS segments.
        val request = okhttp3.Request.Builder()
            .url(stream.masterUrl)
            .header("User-Agent", stream.userAgent)
            .build()
        client.newCall(request).execute().use { response ->
            assertTrue("playlist HTTP ${response.code}", response.isSuccessful)
            val body = response.body?.string().orEmpty()
            assertTrue("not a playlist", body.startsWith("#EXTM3U"))
            assertTrue("no segments", body.contains("#EXTINF"))
        }
    }
}
