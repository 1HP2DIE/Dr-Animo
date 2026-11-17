package com.animo.app.subtitle.online

import com.animo.app.subtitle.SubtitleParser
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Live checks against the real sites. **Manual, not part of the build**: CI has no network, and a
 * site being down must never turn into a red build. Run with `--tests '*OnlineSubtitleLiveTest*'`
 * after removing the annotations, or from the IDE.
 */
@Ignore("live network — run manually")
class OnlineSubtitleLiveTest {

    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val frieren = SubtitleQuery(
        titles = listOf("Frieren: Beyond Journey's End", "Sousou no Frieren"),
        episode = 5,
        year = 2023,
    )

    @Test
    fun `subtitlecat searches and downloads a real episode`() = runBlocking {
        val results = withTimeout(180_000) { SubtitleCatSource.search(frieren, client) }
        assertTrue("expected languages, got ${results.size}", results.size >= 3)

        val english = results.first { it.language.code == "en" }
        val file = SubtitleCatSource.download(english, client)
        val cues = SubtitleParser.parse(file.name, file.content)
        assertTrue("expected cues, got ${cues.size}", cues.size > 20)
    }

    @Test
    fun `subdl finds the episode and unpacks the right file`() = runBlocking {
        val results = withTimeout(180_000) { SubdlSource.search(frieren, client) }
        assertTrue("expected languages, got ${results.size}", results.size >= 3)

        val english = results.first { it.language.code == "en" }
        val file = SubdlSource.download(english, client)
        val cues = SubtitleParser.parse(file.name, file.content)
        assertTrue("expected cues, got ${cues.size}", cues.size > 20)
    }

    @Test
    fun `kitsunekko finds the anime folder and an episode file`() = runBlocking {
        val results = withTimeout(180_000) { KitsunekkoSource.search(frieren, client) }
        assertTrue("expected japanese files, got ${results.size}", results.isNotEmpty())

        val file = KitsunekkoSource.download(results.first(), client)
        val cues = SubtitleParser.parse(file.name, file.content)
        assertTrue("expected cues, got ${cues.size}", cues.size > 20)
    }

    @Test
    fun `repository merges the sources`() = runBlocking {
        val report = withTimeout(240_000) { OnlineSubtitleRepository(client).search(frieren) }
        assertTrue("expected merged results", report.results.size >= 5)
        assertTrue(
            "expected more than one language",
            report.results.map { it.language.code }.toSet().size >= 3,
        )
    }
}
