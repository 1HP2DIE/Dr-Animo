package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.StreamContainer
import com.animo.app.domain.repo.SourceRequest
import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Live end-to-end check of servers 2–4 and the skip-times lookup, through the app's own code.
 *
 * **Manual**: needs the network and the git-ignored secrets file. Skipped unless `ANIMO_LIVE=1`:
 * `ANIMO_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*ProvidersLiveTest*'`
 */
class ProvidersLiveTest {

    private val secrets = File("../secrets/providers.properties").takeIf { it.isFile }
        ?: File("secrets/providers.properties").takeIf { it.isFile }

    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Frieren: Beyond Journey's End, episode 1 — AniList 154587, MAL 52991. */
    private val request = SourceRequest(
        episode = Episode(id = 1L, animeId = 1, number = 1, title = "Episode 1"),
        anilistId = "154587",
        titles = listOf("Frieren: Beyond Journey's End", "Sousou no Frieren"),
        year = 2023,
    )

    @Before
    fun setUp() {
        assumeTrue("set ANIMO_LIVE=1 to run", System.getenv("ANIMO_LIVE") == "1")
        assumeTrue("secrets/providers.properties is not present", secrets != null)
        val props = Properties().apply { secrets!!.inputStream().use { load(it) } }
        ProviderVault.installForTests(props.stringPropertyNames().associateWith { props.getProperty(it) })
    }

    /** The URL Media3 would open actually serves media bytes from the middle of the file. */
    private fun assertPlayable(stream: PlayableStream) {
        val request = Request.Builder()
            .url(stream.masterUrl)
            .header("Referer", stream.referer)
            .header("User-Agent", stream.userAgent)
            .header("Range", "bytes=1000000-1000999")
            .build()
        client.newCall(request).execute().use { response ->
            assertEquals("range read on ${stream.container}", 206, response.code)
            val type = response.header("Content-Type").orEmpty()
            assertTrue("content type $type", type.startsWith("video/"))
        }
    }

    @Test
    fun `server 3 plays one multi-audio file with chapters and subtitles`() = runBlocking {
        val source = MirrorStreamSource(client)
        val languages = withTimeout(60_000) { source.languages(request) }
        println("server 3 languages: ${languages.map { it.id }}")
        assertTrue(languages.any { it.id == "sub" })

        val sub = withTimeout(120_000) { source.resolve(request, AudioTrack.SUB, "sub") }.getOrThrow()
        println("server 3: intro=${sub.intro} outro=${sub.outro} subs=${sub.subtitles.map { it.label }} preview=${sub.previewVtt != null}")
        assertEquals(StreamContainer.PROGRESSIVE, sub.container)
        assertEquals("jpn", sub.preferredAudioLanguage)
        assertNotNull(sub.intro ?: sub.outro)
        assertTrue(sub.subtitles.isNotEmpty())
        assertPlayable(sub)

        val dub = withTimeout(120_000) { source.resolve(request, AudioTrack.DUB, "dub") }.getOrThrow()
        assertEquals("eng", dub.preferredAudioLanguage)
    }

    @Test
    fun `server 4 finds the title and serves every quality`() = runBlocking {
        val source = ArchiveStreamSource(client)
        val languages = withTimeout(60_000) { source.languages(request) }
        println("server 4 languages: ${languages.map { it.id }}")
        assertTrue(languages.any { it.id == "sub" })

        val stream = withTimeout(120_000) { source.resolve(request, AudioTrack.SUB, "sub") }.getOrThrow()
        println("server 4 renditions: ${stream.alternates.map { it.label }}")
        assertTrue(stream.alternates.size > 1)
        assertPlayable(stream)
        assertPlayable(stream.copy(masterUrl = stream.alternates.last().url))
    }

    @Test
    fun `server 2 publishes its qualities as renditions`() = runBlocking {
        val stream = withTimeout(180_000) {
            AltStreamSource(client).resolve("154587", request.episode, AudioTrack.SUB, "ja", seasonHint = 1, year = 2023)
        }.getOrThrow()
        println("server 2 renditions: ${stream.alternates.map { it.label }}")
        assertTrue(stream.alternates.isNotEmpty())
    }

    @Test
    fun `skip times exist for a popular episode`() = runBlocking {
        val markers = SkipTimes.fetch(
            client = client,
            malId = 52991,
            episode = 1,
            episodeLengthMs = 1_440_000L,
            api = ProviderVault.optionalUrl(ProviderKey.SKIP_TIMES_API).orEmpty(),
        )
        println("skip times: $markers")
        assertNotNull(markers?.intro)
    }
}
