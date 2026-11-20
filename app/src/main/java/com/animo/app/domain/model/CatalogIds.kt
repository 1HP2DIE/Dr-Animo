package com.animo.app.domain.model

/**
 * The one place that says which catalogue an `anime.id` belongs to.
 *
 * ## Why ids instead of a flag column
 * All catalogues share one `anime` table so a single grid, search and filter can span them without a
 * join. The id is therefore the discriminator: the original API is **positive** (`s_id` tops out
 * around 2.1e9), the Hindi dubs occupy two **negative** bands, and this catalogue sits below them.
 *
 * ```
 *   100,000,000 … 2,100,000,000   original API (positive)
 *   -1 … -999,999,999            Hindi source A
 *   -1,000,000,000 … -1,999,999,999  Hindi source B
 *   -2,000,000,000               RESERVED — belongs to no catalogue (see [isHindi])
 *   -2,000,000,001 … -2,100,000,000  MovieLink
 * ```
 *
 * `Anime.isHindi` used to be `id < 0`, which would silently misclassify every row below
 * -1,999,999,999. It now delegates to [isHindi], and the bands above are the single definition
 * that the browse filter, the DAO and the resolver all read.
 */
object CatalogIds {

    // ------------------------------------------------------------------ MovieLink band

    /** Lowest MovieLink id; the band is `MOVIE_LINK_MIN … MOVIE_LINK_MAX`. */
    const val MOVIE_LINK_MIN: Int = -2_100_000_000

    /** Highest (closest to zero) MovieLink id. */
    const val MOVIE_LINK_MAX: Int = -2_000_000_001

    /**
     * Number of ids in the band. Public because the hash is defined as "mod this": a reader can
     * reimplement [forSlug] from the doc without guessing whether both ends are inclusive.
     */
    const val MOVIE_LINK_WIDTH: Int = 100_000_000

    // ------------------------------------------------------------------ Hindi bands

    /** `-1 … -999,999,999` — Hindi source A. */
    const val HINDI_A_MIN: Int = -999_999_999
    const val HINDI_A_MAX: Int = -1

    /** `-1,000,000,000 … -1,999,999,999` — Hindi source B. */
    const val HINDI_B_MIN: Int = -1_999_999_999
    const val HINDI_B_MAX: Int = -1_000_000_000

    /**
     * True for the two Hindi bands **only**.
     *
     * The two ranges are adjacent, so one comparison covers both — but the bound is written out
     * rather than spelled `id < 0` on purpose: `-2_000_000_000` and everything below it are not
     * Hindi, and that is exactly the bug a bare sign test produces once a third catalogue exists.
     * The single reserved value `-2_000_000_000` is left unused rather than assigned to a neighbour,
     * so a mis-hashed row fails every classifier instead of showing up as the wrong catalogue.
     */
    fun isHindi(id: Int): Boolean = id in HINDI_B_MIN..HINDI_A_MAX

    // ------------------------------------------------------------------ classification

    fun isMovieLink(id: Int): Boolean = id in MOVIE_LINK_MIN..MOVIE_LINK_MAX

    /**
     * Stable catalogue id for a MovieLink page slug.
     *
     * ## Derivation
     * 32-bit FNV-1a over the UTF-8 bytes of the slug, then `hash mod MOVIE_LINK_WIDTH`, then
     * subtracted from [MOVIE_LINK_MAX]:
     *
     * ```
     * id = MOVIE_LINK_MAX - (fnv1a32(slug) % MOVIE_LINK_WIDTH)
     * ```
     *
     * FNV-1a is used instead of `String.hashCode()` because the latter is Java-specified only in
     * *shape*, not in collision behaviour: the Hindi spaces already hash with it and two catalogues
     * hashing the same way is more correlation than a second catalogue needs. FNV is nine lines,
     * fully specified, and trivially reimplementable — which is what "stable" has to mean when the
     * id is the only durable key a synced row has.
     *
     * ## Which part of the slug is hashed
     * The **last path segment**: `forSlug("movie/mKs_abc")` and `forSlug("mKs_abc")` return the same
     * id. The route prefix (`movie`/`series`/`drama`/`anime`) is a page property that has changed
     * between mirrors, and `anime.slug` has a unique index across all catalogues, so a title that
     * moves from `/movie/x` to `/anime/x` must keep its row rather than get a second one.
     *
     * ## Collision policy
     * The function is pure: it cannot see the table, so it cannot probe for collisions. Across the
     * measured ~15,870 titles the birthday bound is `n² / 2w ≈ 1.26` expected pairs, i.e. a
     * collision somewhere in the catalogue is **plausible, not theoretical**. Therefore the caller
     * owns the check: `MovieLinkSyncer` compares the incoming slug with the stored `anime.slug` for
     * the computed id and **skips** a card that would take over another slug's row (first writer
     * wins). That makes a collision cost one title instead of an endless two-row rewrite.
     *
     * @throws IllegalArgumentException when the slug has no usable segment. A blank slug would map
     *   thousands of cards onto one id, which is worse than failing the one card.
     */
    fun forSlug(slug: String): Int {
        val key = keyOf(slug)
        require(key.isNotEmpty()) { "CatalogIds.forSlug: blank slug" }

        var hash = FNV_OFFSET_BASIS
        for (byte in key.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFFL)
            hash = (hash * FNV_PRIME) and UINT32_MASK
        }

        val bucket = (hash % MOVIE_LINK_WIDTH).toInt()
        return MOVIE_LINK_MAX - bucket
    }

    /** `" movie/mKs_x/ "` → `"mKs_x"`; `""` when nothing is left, which [forSlug] rejects. */
    private fun keyOf(slug: String): String =
        slug.trim().trim('/').substringAfterLast('/')

    private const val FNV_OFFSET_BASIS = 0x811C9DC5L
    private const val FNV_PRIME = 0x01000193L
    private const val UINT32_MASK = 0xFFFFFFFFL
}
