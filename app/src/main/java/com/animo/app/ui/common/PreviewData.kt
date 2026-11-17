package com.animo.app.ui.common

import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.Episode

/** Sample rows for @Preview only — never referenced by production composables. */
internal object PreviewData {

    val anime = Anime(
        id = 1458,
        title = "Sousou no Frieren",
        alternative = "Frieren: Beyond Journey's End",
        native = "葬送のフリーレン",
        slug = "sousou-no-frieren",
        rating = "PG-13",
        poster = "",
        background = "",
        description = "After the party of heroes defeated the Demon King, they restored peace to the " +
            "land and returned to their separate lives. Frieren, the elf mage, outlives her " +
            "companions by centuries and sets out to understand the humans she fought beside.",
        score = 9.03,
        year = 2023,
        season = "fall",
        status = "Finished Airing",
        type = "TV",
        genres = listOf("Adventure", "Drama", "Fantasy"),
        studios = listOf("Madhouse"),
        episodesDeclared = 28,
        episodeCount = 28,
        subCount = 28,
        dubCount = 28,
        updatedAtEpoch = 1_760_000_000L,
    )

    val episode = Episode(
        id = 992_343L,
        animeId = 1458,
        number = 12,
        title = "A Real Hero",
        embedId = "992343",
        subUrl = "https://example.invalid/stream/s-2/992343/sub",
        dubUrl = null,
        updatedAtEpoch = 1_760_000_000L,
    )

    val episodes: List<Episode> = List(12) { index ->
        episode.copy(
            id = 900_000L + index,
            number = index + 1,
            title = if (index % 3 == 0) "" else "Episode title ${index + 1}",
            subUrl = "https://example.invalid/stream/s-2/${900_000 + index}/sub",
            dubUrl = if (index % 2 == 0) "https://example.invalid/stream/s-2/${900_000 + index}/dub" else null,
        )
    }
}
