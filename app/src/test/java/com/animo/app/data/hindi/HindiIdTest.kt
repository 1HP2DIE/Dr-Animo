package com.animo.app.data.hindi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The negative id space is a shared contract: [HindiCatalogImpl] writes the ids and the Hindi stream
 * resolver reads them back from an `Int` alone. Every case here is a way that contract could break
 * silently — an id drifting into the positive space, a series' episode block bleeding into its
 * neighbour's, or a rareanimes post id that cannot be recovered.
 */
class HindiIdTest {

    // ---------------------------------------------------------------- toono

    @Test
    fun `toono ids stay inside their range and are never zero`() {
        val slugs = (1..2_000).map { "series-$it" } + listOf("", " ", "1-happy-family-usa", "tarzan-jane")

        for (slug in slugs) {
            if (slug.isBlank()) continue
            val id = HindiIds.toono(slug)
            assertTrue("$slug -> $id below TOONO_MIN", id >= HindiIds.TOONO_MIN)
            assertTrue("$slug -> $id above TOONO_MAX", id <= HindiIds.TOONO_MAX)
            assertNotEquals("$slug -> 0", 0, id)
            assertEquals("$slug must classify as toono", HindiOrigin.TOONO, HindiIds.originOf(id))
        }
    }

    @Test
    fun `toono id is stable for the same slug`() {
        assertEquals(HindiIds.toono("1-happy-family-usa"), HindiIds.toono("1-happy-family-usa"))
        assertEquals(HindiIds.toono("  tarzan-jane  "), HindiIds.toono("tarzan-jane"))
    }

    @Test
    fun `a slug hashing to Int MIN_VALUE still lands in the negative space`() {
        // `abs(Int.MIN_VALUE)` is still negative in Kotlin; the derivation takes the hash through
        // Long first, so this slug cannot flip the id into the positive space.
        val slug = "polygenelubricants"
        assertEquals(Int.MIN_VALUE, slug.hashCode())

        val id = HindiIds.toono(slug)
        assertEquals(-147_483_651, id)
        assertEquals(HindiOrigin.TOONO, HindiIds.originOf(id))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toono rejects a blank slug instead of producing a shared id`() {
        HindiIds.toono("   ")
    }

    // ---------------------------------------------------------------- rareanimes

    @Test
    fun `rare id is minus one billion minus the post id`() {
        assertEquals(-1_000_000_001, HindiIds.rare(1))
        assertEquals(-1_000_001_234, HindiIds.rare(1_234))
        assertEquals(HindiIds.RARE_MIN, HindiIds.rare(999_999_999))
    }

    @Test
    fun `rare post id round trips`() {
        for (postId in listOf(1, 42, 1_234, 999_999_999)) {
            val id = HindiIds.rare(postId)
            assertEquals(postId, HindiIds.rarePostId(id))
            assertEquals(HindiOrigin.RARE, HindiIds.originOf(id))
        }
        assertNull(HindiIds.rarePostId(HindiIds.TOONO_MAX))
        assertNull(HindiIds.rarePostId(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rare rejects a post id outside the space`() {
        HindiIds.rare(0)
    }

    // ---------------------------------------------------------------- classification

    @Test
    fun `originOf splits the two ranges and rejects everything else`() {
        assertEquals(HindiOrigin.TOONO, HindiIds.originOf(-1))
        assertEquals(HindiOrigin.TOONO, HindiIds.originOf(-999_999_999))
        assertEquals(HindiOrigin.RARE, HindiIds.originOf(-1_000_000_000))
        assertEquals(HindiOrigin.RARE, HindiIds.originOf(-1_999_999_999))

        assertNull("0 is not Hindi", HindiIds.originOf(0))
        assertNull("a positive id is the original catalogue", HindiIds.originOf(42))
        assertNull("past the rare floor", HindiIds.originOf(-2_000_000_000))
        assertNull("Int.MIN_VALUE", HindiIds.originOf(Int.MIN_VALUE))
    }

    // ---------------------------------------------------------------- episodes

    @Test
    fun `episode ids are negative and unique per anime and number`() {
        val ids = mutableSetOf<Long>()
        val animeIds = listOf(-1, -2, HindiIds.toono("tarzan-jane"), HindiIds.rare(1_234))

        for (animeId in animeIds) {
            for (number in 1..24) {
                val id = HindiIds.episodeId(animeId, number)
                assertTrue("$animeId/$number -> $id must be negative", id < 0L)
                assertTrue("duplicate episode id $id", ids.add(id))
            }
        }
    }

    @Test
    fun `one series cannot reach into the next series' block`() {
        val first = HindiIds.episodeId(-1_000_000_500, 999)
        val neighbour = HindiIds.episodeId(-1_000_000_501, 1)
        assertNotEquals(first, neighbour)
        assertTrue("blocks are ordered by series magnitude", neighbour < first)
    }

    @Test
    fun `an out of range episode number stays inside its own block`() {
        val bound = HindiIds.episodeId(-7, 10_000)
        assertEquals(bound, HindiIds.episodeId(-7, HindiIds.EPISODE_BLOCK.toInt() - 1))
        // Below the block floor, i.e. it never crosses into series -8.
        assertTrue(bound > HindiIds.episodeId(-8, 1))
    }

    @Test
    fun `episode ids never collide with positive original ids`() {
        for (animeId in listOf(-1, HindiIds.toono("x"), HindiIds.rare(7))) {
            for (number in 1..5) {
                assertTrue(HindiIds.episodeId(animeId, number) < 0L)
            }
        }
    }
}
