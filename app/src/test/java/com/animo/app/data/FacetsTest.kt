package com.animo.app.data

import com.animo.app.data.local.FacetRow
import com.animo.app.data.local.filterFacetsOf
import com.animo.app.domain.model.FilterFacets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter sheet must never offer a value that returns zero rows, so every assertion here is about
 * what the *catalogue* says, not about a fixed list of genres.
 */
class FacetsTest {

    private fun row(
        genres: String = "",
        year: Int? = null,
        type: String = "",
        statusNorm: String = "",
        titleNorm: String = "",
    ) = FacetRow(genres = genres, year = year, type = type, statusNorm = statusNorm, titleNorm = titleNorm)

    @Test
    fun `an empty catalogue yields empty facets`() {
        assertEquals(FilterFacets(), filterFacetsOf(emptyList()))
    }

    @Test
    fun `genres are split from csv and de-duplicated case-insensitively`() {
        val facets = filterFacetsOf(
            listOf(
                row(genres = "Action, Sci-Fi, action"),
                row(genres = "Comedy,SCI-FI"),
            ),
        )

        // First-seen casing wins, so the sheet shows the API's own spelling.
        assertEquals(listOf("Action", "Comedy", "Sci-Fi"), facets.genres)
    }

    @Test
    fun `genres are sorted case-insensitively`() {
        val facets = filterFacetsOf(
            listOf(row(genres = "romance, Action, adventure, Slice of Life")),
        )

        assertEquals(listOf("Action", "adventure", "romance", "Slice of Life"), facets.genres)
    }

    @Test
    fun `the literal genre unknown is never offered`() {
        val facets = filterFacetsOf(listOf(row(genres = "unknown, Action, Unknown")))

        assertEquals(listOf("Action"), facets.genres)
    }

    @Test
    fun `blank genre fragments are dropped`() {
        assertEquals(emptyList<String>(), filterFacetsOf(listOf(row(genres = " , ,, "))).genres)
    }

    @Test
    fun `years are newest first and nulls are ignored`() {
        val facets = filterFacetsOf(
            listOf(
                row(year = 2019),
                row(year = null),
                row(year = 2024),
                row(year = 2019),
                row(year = 1999),
            ),
        )

        assertEquals(listOf(2024, 2019, 1999), facets.years)
    }

    @Test
    fun `types are canonicalised, de-duplicated and sorted`() {
        val facets = filterFacetsOf(
            listOf(
                row(type = "TV"),
                row(type = " Movie "),
                row(type = "TV_SHORT"),
                row(type = ""),
                row(type = "ONA"),
                row(type = "conspiracy"),
            ),
        )

        assertEquals(listOf("Movie", "ONA", "TV", "TV Short"), facets.types)
    }

    @Test
    fun `statuses are translated to the three domain values`() {
        val facets = filterFacetsOf(
            listOf(
                row(statusNorm = "currently_airing"),
                row(statusNorm = "finished_airing"),
                row(statusNorm = "not_yet_aired"),
                row(statusNorm = "currently_airing"),
            ),
        )

        assertEquals(listOf("airing", "finished", "upcoming"), facets.statuses)
    }

    @Test
    fun `unknown statuses never reach the sheet`() {
        val facets = filterFacetsOf(
            listOf(row(statusNorm = ""), row(statusNorm = "banana"), row(statusNorm = "finished_airing")),
        )

        assertEquals(listOf("finished"), facets.statuses)
    }

    @Test
    fun `letters are uppercased and sorted so an A-Z strip renders in order`() {
        val facets = filterFacetsOf(
            listOf(
                row(titleNorm = "one piece"),
                row(titleNorm = "bleach"),
                row(titleNorm = "86 eighty six"),
                row(titleNorm = "bleach"),
                row(titleNorm = "akira"),
                row(titleNorm = ""),
            ),
        )

        assertEquals(listOf('8', 'A', 'B', 'O'), facets.letters)
    }

    @Test
    fun `every facet comes from the rows it was given`() {
        val facets = filterFacetsOf(listOf(row()))

        assertTrue(facets.genres.isEmpty())
        assertTrue(facets.years.isEmpty())
        assertTrue(facets.types.isEmpty())
        assertTrue(facets.statuses.isEmpty())
        assertTrue(facets.letters.isEmpty())
    }
}
