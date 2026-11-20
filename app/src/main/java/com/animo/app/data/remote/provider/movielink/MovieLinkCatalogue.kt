package com.animo.app.data.remote.provider.movielink

/**
 * The part of [MovieLinkProvider] the catalogue sync needs, as an interface.
 *
 * The sync lives in `data.sync` and is unit-tested on the JVM; a concrete `MovieLinkProvider` would
 * drag OkHttp and a mirror host into every test of the page loop. Narrowing to three members keeps
 * the fake honest: a sync can only page the catalogue, ask whether it is configured, and resolve a
 * poster — it cannot reach the transport, the retry policy or the player.
 */
interface MovieLinkCatalogue {

    /** False when the vault slot held no usable host; a sync must fail fast rather than page forever. */
    val isConfigured: Boolean

    /** One listing page, parsed. The only request a sync makes per page. */
    suspend fun cataloguePage(page: Int): Result<MovieLinkPage>

    /**
     * Root-relative card `src` → the absolute URL Coil can load.
     *
     * Resolving belongs to the provider because it is the only component that knows the current
     * mirror; a stored relative `src` would break every row after the next host rotation.
     */
    fun posterUrl(raw: String): String

    companion object {
        /** `anime.source` and the `sync_cursor` row key for this catalogue. */
        const val PROVIDER_ID = "movielink"
    }
}
