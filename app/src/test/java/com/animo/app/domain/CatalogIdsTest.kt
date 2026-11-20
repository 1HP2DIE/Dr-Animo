package com.animo.app.domain

import com.animo.app.domain.model.CatalogIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three id spaces have to stay disjoint: every routing decision is one comparison. */
class CatalogIdsTest {

    @Test
    fun `a slug maps into the second catalogue band, stably`() {
        val first = CatalogIds.forSlug("movie/mKs_dzwu5gqbllrbyxbshjij6tquli")
        val again = CatalogIds.forSlug("movie/mKs_dzwu5gqbllrbyxbshjij6tquli")

        assertEquals(first, again)
        assertTrue(CatalogIds.isMovieLink(first))
    }

    @Test
    fun `the band never overlaps hindi or the original catalogue`() {
        listOf(
            "mKs_a", "mKs_b", "movie/mKs_c", "series/mKs_d", "anime/mKs_e", "drama/mKs_f",
        ).forEach { slug ->
            val id = CatalogIds.forSlug(slug)
            assertTrue(CatalogIds.isMovieLink(id))
            assertFalse("hindi band claimed $slug -> $id", CatalogIds.isHindi(id))
            assertFalse("positive band claimed $slug -> $id", id >= 0)
        }
    }

    @Test
    fun `hindi ids are not mistaken for the second catalogue`() {
        assertTrue(CatalogIds.isHindi(-1))
        assertTrue(CatalogIds.isHindi(-1_999_999_999))
        assertFalse(CatalogIds.isMovieLink(-1))
        assertFalse(CatalogIds.isMovieLink(-1_999_999_999))
    }

    @Test
    fun `only the two negative bands are hindi`() {
        assertFalse(CatalogIds.isHindi(0))
        assertFalse(CatalogIds.isHindi(42))
        assertFalse(CatalogIds.isHindi(-2_000_000_000))
        assertFalse(CatalogIds.isHindi(CatalogIds.MOVIE_LINK_MIN))
    }
}
