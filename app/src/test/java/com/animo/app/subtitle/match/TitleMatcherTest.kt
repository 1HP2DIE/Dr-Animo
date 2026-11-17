package com.animo.app.subtitle.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatcherTest {

    private val frieren = MatchTarget(
        titles = listOf("Frieren: Beyond Journey's End", "Sousou no Frieren"),
        episode = 5,
        year = 2023,
    )

    private fun match(target: MatchTarget, title: String) =
        TitleMatcher.match(target, MatchCandidate(title))

    // ---------------------------------------------------------------- normalisation

    @Test
    fun `normalisation folds diacritics, apostrophes and ampersands`() {
        assertEquals("pokemon", TitleNormalizer.normalize("Pokémon").text)
        assertEquals(listOf("journeys", "end"), TitleNormalizer.normalize("Journey's End").tokens)
        assertEquals(listOf("fire", "and", "ice"), TitleNormalizer.normalize("Fire & Ice").tokens)
        assertEquals(listOf("soso", "no", "frieren"), TitleNormalizer.normalize("Sōsō no Frieren").tokens)
        // Spider-Man must not split into a subtitle separator.
        assertEquals(listOf("spider", "man"), TitleNormalizer.normalize("Spider-Man").tokens)
    }

    @Test
    fun `main tokens stop at the subtitle separator`() {
        val title = TitleNormalizer.normalize("Frieren: Beyond Journey's End")
        assertEquals(listOf("frieren"), title.mainTokens)
        assertEquals(listOf("frieren", "beyond", "journeys", "end"), title.tokens)
    }

    // ---------------------------------------------------------------- good matches

    @Test
    fun `a real release name is a strong match`() {
        val result = match(frieren, "Frieren.Beyond.Journeys.End.S01E05.JAPANESE.WEBRip.NF")
        assertTrue(result.accepted)
        assertTrue(result.confidence == MatchConfidence.STRONG || result.confidence == MatchConfidence.EXACT)
        assertEquals(true, result.signals.episode)
        assertTrue(result.reasons.any { it.startsWith("shared:") })
    }

    @Test
    fun `the romaji alternate matches its own folder name`() {
        // A folder is show-level, so the target has no episode — that is how Kitsunekko is searched.
        val folderTarget = MatchTarget(titles = listOf("Frieren: Beyond Journey's End", "Sousou no Frieren"))
        val result = match(folderTarget, "Sousou no Frieren")
        assertTrue(result.accepted)
        assertEquals(MatchConfidence.EXACT, result.confidence)
    }

    @Test
    fun `a show-level name cannot stand in for an episode file`() {
        val result = match(frieren, "Sousou no Frieren")
        assertFalse(result.accepted)
        assertTrue(result.reasons.any { it.contains("no episode") })
    }

    @Test
    fun `an episode range covers the episode being watched`() {
        val result = match(frieren, "Frieren.Beyond.Journeys.End.S01E01-E04.JAPANESE.WEBRip")
        assertFalse(result.accepted)
        assertTrue(result.reasons.any { it.contains("outside") })

        val covering = match(frieren, "Frieren.Beyond.Journeys.End.S01E01-E12.Batch")
        assertTrue(covering.accepted)
    }

    @Test
    fun `acronyms are recognised`() {
        val target = MatchTarget(titles = listOf("Attack on Titan"), episode = 5)
        val result = match(target, "AoT.S04E05.1080p.WEB-DL")
        assertTrue(result.accepted)
        assertTrue(result.signals.acronym)
    }

    @Test
    fun `plural and small typos still match`() {
        val target = MatchTarget(titles = listOf("Frieren"), episode = 5)
        assertTrue(match(target, "Freiren.S01E05.1080p").accepted)
        assertTrue(match(target, "Frieren.S01E05.1080p").accepted)
    }

    // ---------------------------------------------------------------- rejections

    @Test
    fun `a different season of the same franchise is rejected`() {
        val pokemon = MatchTarget(titles = listOf("Pokémon Season 9 Battle Frontier"), episode = 5)
        val result = match(pokemon, "Pokemon.BW.Adventures.in.Unova.S16E05.1080p")
        assertFalse(result.accepted)
        assertTrue(result.reasons.any { it.contains("season 16") })
    }

    @Test
    fun `the right season of the same franchise is accepted`() {
        val pokemon = MatchTarget(titles = listOf("Pokémon Season 9 Battle Frontier"), episode = 5)
        val result = match(pokemon, "Pokemon.S09E05.1080p.WEB-DL")
        assertTrue(result.accepted)
    }

    @Test
    fun `a different episode is rejected`() {
        val result = match(frieren, "Frieren.Beyond.Journeys.End.S01E02.JAPANESE.WEBRip")
        assertFalse(result.accepted)
        assertTrue(result.reasons.any { it.contains("episode 5") })
    }

    @Test
    fun `one shared tail word is not enough`() {
        val pokemon = MatchTarget(titles = listOf("Pokémon Season 9 Battle Frontier"), episode = 5)
        // "frontier" is shared, but the franchise name ("pokemon") is missing.
        val result = match(pokemon, "Shangri-La.Frontier.S01E05.1080p.WEB-DL")
        assertFalse(result.accepted)
        // Rejected on the season first (S01 vs Season 9) and on the franchise name second.
        assertTrue(result.reasons.isNotEmpty())
    }

    @Test
    fun `an unrelated show is not accepted`() {
        val result = match(frieren, "One.Piece.S01E05.1080p.WEB-DL")
        assertFalse(result.accepted)
        assertEquals(MatchConfidence.NONE, result.confidence)
    }

    @Test
    fun `a year difference costs score but does not veto`() {
        val target = MatchTarget(titles = listOf("Frieren Beyond Journeys End"), episode = 5, year = 2023)
        val older = match(target, "Frieren.Beyond.Journeys.End.S01E05.2019.WEBRip")
        val right = match(target, "Frieren.Beyond.Journeys.End.S01E05.2023.WEBRip")
        assertTrue(older.accepted)
        assertTrue(right.score > older.score)
    }

    // ---------------------------------------------------------------- ranking

    @Test
    fun `ranking puts the best candidate first and rejected last`() {
        val ranked = TitleMatcher.rank(
            frieren,
            listOf(
                MatchCandidate("One.Piece.S01E05.1080p"),
                MatchCandidate("Frieren.Beyond.Journeys.End.S01E02.WEBRip"),
                MatchCandidate("Frieren.Beyond.Journeys.End.S01E05.JAPANESE.WEBRip.NF"),
                MatchCandidate("Sousou no Frieren"),
            ),
        )

        assertEquals("Frieren.Beyond.Journeys.End.S01E05.JAPANESE.WEBRip.NF", ranked.first().candidate.title)
        assertFalse(ranked.last().accepted)
        // A show-level name has no episode number, so it cannot be chosen as the episode's subtitle.
        assertFalse(ranked.first { it.candidate.title == "Sousou no Frieren" }.accepted)
    }

    // ---------------------------------------------------------------- release parsing

    @Test
    fun `release parsing understands the common shapes`() {
        assertEquals(5..5, ReleaseTitle.parse("Show.S01E05.1080p").episodes)
        assertEquals(5..5, ReleaseTitle.parse("Show.1x05.HDTV").episodes)
        // "Season 2 - 05" names the season *and* the episode; the range is the episode alone.
        assertEquals(5..5, ReleaseTitle.parse("Show Season 2 - 05").episodes)
        assertEquals(2, ReleaseTitle.parse("Show Season 2 - 05").season)
        assertEquals(5..5, ReleaseTitle.parse("Show - 05 [1080p]").episodes)
        assertEquals(1..12, ReleaseTitle.parse("[Group] Show [01~12][BD]").episodes)
        assertEquals(1..4, ReleaseTitle.parse("Show.S01E01-E04.WEBRip").episodes)
        assertEquals("1080 is not an episode", null, ReleaseTitle.parse("Show.1080p.WEB-DL").episodes)
        assertEquals(2023, ReleaseTitle.parse("Show.2023.1080p.WEB-DL").year)
        assertEquals(null, ReleaseTitle.parse("Show.1080p.2023").episodes)
    }

    @Test
    fun `season is read from a title in both spellings`() {
        assertEquals(9, ReleaseTitle.seasonInTitle(listOf("Pokémon Season 9 Battle Frontier")))
        assertEquals(3, ReleaseTitle.seasonInTitle(listOf("Some Show 3rd Season")))
        assertEquals(null, ReleaseTitle.seasonInTitle(listOf("Frieren: Beyond Journey's End")))
    }

    // ---------------------------------------------------------------- primitives

    @Test
    fun `token similarity is strict about short words`() {
        assertTrue(TitleMatcher.tokensMatch("journey", "journeys"))
        assertTrue(TitleMatcher.tokensMatch("frieren", "freiren"))
        assertTrue("a one-character typo is tolerated", TitleMatcher.tokensMatch("naruto", "narut0"))
        assertFalse(TitleMatcher.tokensMatch("on", "of"))
    }

    @Test
    fun `levenshtein bails out past the limit`() {
        assertEquals(0, TitleMatcher.levenshtein("frieren", "Frieren".lowercase(), 2))
        assertEquals(2, TitleMatcher.levenshtein("frieren", "freiren", 2))
        assertTrue(TitleMatcher.levenshtein("abcdef", "uvwxyz", 2) > 2)
    }
}
