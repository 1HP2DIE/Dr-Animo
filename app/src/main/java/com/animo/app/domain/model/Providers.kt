package com.animo.app.domain.model

/**
 * When a provider is allowed to fetch on its own.
 *
 * The three built-in catalogues are [AUTO] because they were already shipped and already synced
 * before this setting existed; anything added later starts [MANUAL] so a new source cannot spend
 * the user's data or run time without being asked. [OFF] is not "no sync" — it also hides the rows
 * behind a provider that has been retired, without deleting them.
 */
enum class SyncMode {

    /** The nightly worker refreshes it. */
    AUTO,

    /** Only the Sync button on the provider page touches it. */
    MANUAL,

    /** Nothing fetches, and its rows are treated as archived. */
    OFF,
}

/**
 * One stream backend inside a provider.
 *
 * [label] is what the viewer sees and must stay free of hostnames — the same rule the changelog
 * follows, because a hostname is not something the viewer can act on.
 */
data class ProviderServer(
    val id: String,
    val label: String,
)

/**
 * A catalogue the app knows how to fetch, and the shape of what it returns.
 *
 * This is static, compiled-in metadata: providers are decided at build time, not discovered at
 * runtime, so nothing here is stored in Room. Only the *preference* ([SyncMode]) is persisted, by
 * `ProviderPrefEntity`.
 *
 * [servers] is keyed by [ContentKind] because the same provider often serves a film through a
 * different backend than a series — that is exactly the `provider -> anime [servers], movie
 * [servers]` shape the provider page renders. A kind missing from the map means the provider does
 * not carry it at all.
 */
data class ProviderSpec(
    val id: String,
    val displayName: String,
    /** ISO-639-1 tags this catalogue is (mostly) in. Empty = mixed / not stated. */
    val languages: Set<String>,
    val kinds: Set<ContentKind>,
    val servers: Map<ContentKind, List<ProviderServer>>,
    val defaultSyncMode: SyncMode = SyncMode.MANUAL,
    /** One line for the provider page; no hostnames. */
    val summary: String = "",
    /**
     * The tag to store when the catalogue itself never says which language a title is.
     *
     * Some sources publish a single language and never state it per row; leaving the column null
     * would make those rows invisible to the language filter forever. Null means "genuinely mixed,
     * do not guess".
     */
    val defaultLanguage: String? = null,
) {

    fun serversFor(kind: ContentKind): List<ProviderServer> = servers[kind].orEmpty()

    fun supports(kind: ContentKind): Boolean = kind in kinds
}

/**
 * The compiled-in provider list.
 *
 * The ids are the strings stored in `anime.providerId`, so they are part of the database contract:
 * renaming one needs a migration, not just an edit here.
 */
object Providers {

    /** The original anime catalogue (positive ids). */
    const val ORIGINAL: String = "anikoto"

    /** Both Hindi-dub sources merged into one catalogue (ids `-1 … -1_999_999_999`). */
    const val HINDI: String = "hindi"

    /** The film/series catalogue (ids `-2_000_000_001 … -2_100_000_000`). */
    const val MOVIE_LINK: String = "movielink"

    val ORIGINAL_SPEC: ProviderSpec = ProviderSpec(
        id = ORIGINAL,
        displayName = "Anime",
        languages = setOf("ja"),
        kinds = setOf(ContentKind.ANIME),
        servers = mapOf(
            ContentKind.ANIME to listOf(
                ProviderServer("megaplay", "Primary"),
                ProviderServer("mirror", "Mirror"),
                ProviderServer("archive", "Archive"),
            ),
        ),
        defaultSyncMode = SyncMode.AUTO,
        summary = "The main catalogue: airing and finished anime.",
    )

    val HINDI_SPEC: ProviderSpec = ProviderSpec(
        id = HINDI,
        displayName = "Hindi",
        languages = setOf("hi"),
        kinds = setOf(ContentKind.ANIME, ContentKind.MOVIE),
        servers = mapOf(
            ContentKind.ANIME to listOf(
                ProviderServer("hindi_1", "Server 1"),
                ProviderServer("hindi_2", "Server 2"),
            ),
            ContentKind.MOVIE to listOf(
                ProviderServer("hindi_1", "Server 1"),
                ProviderServer("hindi_2", "Server 2"),
            ),
        ),
        defaultSyncMode = SyncMode.AUTO,
        summary = "Hindi-dubbed anime and films.",
    )

    val MOVIE_LINK_SPEC: ProviderSpec = ProviderSpec(
        id = MOVIE_LINK,
        displayName = "Movies & Series",
        languages = emptySet(),
        kinds = setOf(ContentKind.MOVIE, ContentKind.SERIES),
        servers = mapOf(
            ContentKind.MOVIE to listOf(ProviderServer("primary", "Server 1")),
            ContentKind.SERIES to listOf(ProviderServer("primary", "Server 1")),
        ),
        defaultSyncMode = SyncMode.AUTO,
        summary = "Films and series outside the anime catalogue.",
        // The listing carries title, year, poster, kind and genre tags — and no language at all.
        // Hindi is the declared default; a title that names its own language overrides it (see
        // [languageFromTitle]), which is how the Tamil and Telugu rows stay findable.
        defaultLanguage = "hi",
    )

    /** Order is the order the provider page lists them. */
    val ALL: List<ProviderSpec> = listOf(ORIGINAL_SPEC, HINDI_SPEC, MOVIE_LINK_SPEC)

    fun spec(id: String?): ProviderSpec? = ALL.firstOrNull { it.id == id }

    /**
     * A language a title declares about itself, e.g. `Vadala (Telugu)` or `… Hindi Dubbed`.
     *
     * Only explicit mentions count: a source that publishes dozens of Indian languages labels the
     * exception, not the rule, so "no mention" must stay null and let the caller fall back to
     * [ProviderSpec.defaultLanguage] instead of guessing Hindi for a Tamil film.
     */
    fun languageFromTitle(title: String): String? {
        val lower = title.lowercase()
        return when {
            lower.contains("telugu") -> "te"
            lower.contains("tamil") -> "ta"
            lower.contains("malayalam") -> "ml"
            lower.contains("kannada") -> "kn"
            lower.contains("bengali") || lower.contains("bangla") -> "bn"
            lower.contains("marathi") -> "mr"
            lower.contains("punjabi") -> "pa"
            lower.contains("hindi") -> "hi"
            else -> null
        }
    }

    /** Provider that owns a row, derived from its id band when the column is blank. */
    fun idFor(animeId: Int): String = when {
        CatalogIds.isMovieLink(animeId) -> MOVIE_LINK
        CatalogIds.isHindi(animeId) -> HINDI
        else -> ORIGINAL
    }
}
