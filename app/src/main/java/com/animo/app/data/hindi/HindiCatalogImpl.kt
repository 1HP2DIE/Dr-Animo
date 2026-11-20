package com.animo.app.data.hindi

import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault

import androidx.sqlite.db.SimpleSQLiteQuery
import com.animo.app.data.local.FilterSql
import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.local.dao.AnimeStamp
import com.animo.app.data.local.dao.EpisodeDao
import com.animo.app.data.local.entity.AnimeEntity
import com.animo.app.data.remote.Normalize
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.Filters
import com.animo.app.domain.model.SortOrder
import com.animo.app.domain.repo.HindiCatalog
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Which provider a negative id came from — the only decision a resolver has to make for a Hindi row.
 *
 * [slugPrefix] is what the row's `slug` carries; `anime.slug` has a **unique** index and both
 * catalogues reuse bare slugs, so the prefix is what keeps `tarzan-jane` (toono movie) and
 * `tarzan-jane` (rareanimes post) as two different rows.
 */
enum class HindiOrigin(val label: String, private val prefixKey: ProviderKey) {
    SOURCE_A("Hindi source A", ProviderKey.DUB_SLUG_PREFIX_A),
    SOURCE_B("Hindi source B", ProviderKey.DUB_SLUG_PREFIX_B);

    /** Read from the vault: the literal prefix never ships, and the stored value stays stable. */
    val slugPrefix: String get() = ProviderVault.text(prefixKey)
}

/**
 * The negative id space shared by [HindiCatalogImpl] and the Hindi stream resolver.
 *
 * `Anime.isHindi` documents the two ranges; this object is the code behind that doc. Keeping the
 * derivation in one place matters because the resolver only ever sees an `Int` from Room — it must
 * be able to say "this is a toono title" (and, for rareanimes, recover the WordPress post id) from
 * the id alone, without a join and without a flag column that could drift.
 *
 * Both derivations are pure and stable: the same provider row always produces the same id, so a
 * repeated sync is an idempotent upsert rather than a duplicate row.
 */
object HindiIds {

    // ------------------------------------------------------------------ ranges

    /** `-1 … -999_999_999` — toono.app. */
    const val TOONO_MIN = -999_999_999
    const val TOONO_MAX = -1

    /** `-1_000_000_000 … -1_999_999_999` — rareanimes.foo. */
    const val RARE_MIN = -1_999_999_999
    const val RARE_MAX = -1_000_000_000

    /**
     * Width of one series' episode-id block. Episode numbers are **in-season** numbers (toono
     * `-1x3` → 3, rareanimes `Episode 03 (373)` → 3), so a four-digit number would already be a
     * parsing regression; the block still bounds the id to its own series if it ever happens.
     */
    const val EPISODE_BLOCK = 1_000L

    private const val TOONO_SPAN = 999_999_999L
    private const val RARE_BASE = 1_000_000_000L
    private const val RARE_SPAN = 999_999_999

    // ------------------------------------------------------------------ catalogue ids

    /**
     * toono row id from its slug, the only stable key the site has (`/series/<slug>/`, `/movies/<slug>/`).
     *
     * The `+ 1` is what makes the `-1` edge explicit: the bucket is `0 … 999_999_998`, so the id can
     * never be `0` — and `0` would be an original-catalogue id, not a Hindi one.
     *
     * The hash is taken through [Long] before `abs`: `abs(Int.MIN_VALUE)` is still `Int.MIN_VALUE`
     * (negative) in Kotlin, which would otherwise flip the result into the positive space for one
     * slug in four billion.
     */
    fun toono(slug: String): Int {
        val key = slug.trim()
        require(key.isNotEmpty()) { "HindiIds: blank slug" }
        val bucket = abs(key.hashCode().toLong()) % TOONO_SPAN
        return -(bucket + 1L).toInt()
    }

    /** rareanimes row id from the WordPress post id — the one key that survives a slug edit. */
    fun rare(postId: Int): Int {
        require(postId in 1..RARE_SPAN) { "HindiIds.rare: post id out of range: $postId" }
        return -(RARE_BASE + postId).toInt()
    }

    /** Inverse of [rare]; `null` when [id] is not a rareanimes row. */
    fun rarePostId(id: Int): Int? =
        if (id in RARE_MIN..RARE_MAX) (-RARE_BASE - id.toLong()).toInt().takeIf { it > 0 } else null

    fun originOf(id: Int): HindiOrigin? = when {
        id in TOONO_MIN..TOONO_MAX -> HindiOrigin.SOURCE_A
        id in RARE_MIN..RARE_MAX -> HindiOrigin.SOURCE_B
        else -> null
    }

    // ------------------------------------------------------------------ episode ids

    /**
     * Episode primary key. `episode.episodeId` is a `Long` because the original API's ids are, and
     * the Hindi side must not land on any of them: original ids are positive, these are negative.
     *
     * Each series owns a block of [EPISODE_BLOCK] slots, so `(-1000, ep 1)` and `(-1001, ep 1)` can
     * never meet. The sign is carried by the whole expression, so a series' magnitude and its
     * episode number can never be confused for one another either.
     */
    fun episodeId(animeId: Int, episodeNumber: Int): Long {
        val series = abs(animeId.toLong())
        val slot = episodeNumber.coerceIn(1, (EPISODE_BLOCK - 1L).toInt()).toLong()
        return -(series * EPISODE_BLOCK + slot)
    }
}

/**
 * Room-backed [HindiCatalog]: pulls both Hindi providers into the `anime` table and parses one
 * title's episode list on demand.
 *
 * ## Why it is separate from [com.animo.app.data.repo.AnimeRepositoryImpl]
 * The Hindi layer has its own providers, its own rate budget (toono's AJAX is cheap, rareanimes is
 * paced at 3 s) and its own failure modes, and the UI must keep working when both are down. This
 * class therefore swallows provider failures per phase, reports progress per page and returns a
 * count instead of a `Unit` result.
 *
 * ## What it writes
 * Rows are ordinary `anime` rows with a negative id ([HindiIds]) and `originUrl` pointing at the
 * provider page — the resolver re-walks that URL because neither site has an episode or stream API.
 * Every column mirrors what `AnimeDto.toEntity` fills for the original catalogue
 * ([com.animo.app.data.remote.DtoMappers]), except that `isHindi` is implied by the id and no flag
 * column is written.
 *
 * Page writes are one `upsertAll` per provider page, never one row at a time, and each page carries
 * the previous `episodeCount` / `detailFetchedAt` forward the same way the original ingest does —
 * a catalog pass rewrites the whole row and must not forget what the detail pass already learned.
 *
 * ## Rate budget (measured 2026-11-17)
 * toono: 126 pages × 10 = **1,260 titles**, page 127 answers an empty `html` — stop on empty, plus a
 * 200-page ceiling. rareanimes: 18 pages × 100 = **1,715 titles** via `x-wp-totalpages`. The
 * providers rate-limit themselves; the only pacing added here is a small gap between toono pages.
 *
 * Pure JVM otherwise: no `android.*`, so the id maths is unit-testable and the class can be built
 * from a worker without an Android runtime beyond Room itself.
 *
 * @param log one-line trace sink. Defaults to a no-op so the class never touches `android.util.Log`.
 */
class HindiCatalogImpl(
    private val animeDao: AnimeDao,
    private val episodeDao: EpisodeDao,
    private val toono: ToonoProvider,
    private val rareAnimes: RareAnimesProvider,
    private val log: (String) -> Unit = {},
) : HindiCatalog {

    // ------------------------------------------------------------------ sync

    /**
     * toono first, then rareanimes. A provider that fails is logged and skipped instead of aborting
     * the other one — but when *every* targeted provider fails the caller gets a failure, so the UI
     * can say "provider down" rather than "0 new titles".
     *
     * `force = false` is the cheap re-entry: a provider whose rows are already in Room is not walked
     * again. Presence is sampled from the catalogue ([hindiOriginsPresent]) rather than counted,
     * because the DAO has no `WHERE id < 0` aggregate — the failure mode of a sample is only a
     * redundant (idempotent) sync, never a skipped provider.
     */
    override suspend fun sync(force: Boolean, onProgress: (Int, Int) -> Unit): Result<Int> =
        try {
            syncInternal(force, onProgress)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log("hindi sync aborted: ${error.message}")
            Result.failure(error)
        }

    private suspend fun syncInternal(force: Boolean, onProgress: (Int, Int) -> Unit): Result<Int> {
        val present = if (force) emptySet() else hindiOriginsPresent()
        val targets = HindiOrigin.values().filter { it !in present }

        if (targets.isEmpty()) {
            log("hindi sync: both providers already have rows — nothing to do without force")
            return Result.success(0)
        }
        log("hindi sync: targets ${targets.joinToString { it.label }} (force=$force)")

        // A coarse but monotone estimate: toono has no total in its AJAX payload and rareanimes
        // advertises `x-wp-totalpages`, so the number is raised as pages are actually observed.
        var pages = 0
        var total = targets.sumOf { origin ->
            when (origin) {
                HindiOrigin.SOURCE_A -> TOONO_ESTIMATED_PAGES
                HindiOrigin.SOURCE_B -> RARE_ESTIMATED_PAGES
            }
        }

        fun emit(remaining: Int?) {
            pages += 1
            if (remaining != null && remaining > 0) total = maxOf(total, pages + remaining)
            if (pages > total) total = pages
            val done = pages
            val estimated = total
            try {
                onProgress(done, estimated)
            } catch (received: Throwable) {
                log("hindi progress callback threw: ${received.message}")
            }
        }

        val failures = ArrayList<String>(2)
        var written = 0

        if (HindiOrigin.SOURCE_A in targets) {
            try {
                written += syncToono { remaining -> emit(remaining) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failures += "source A: ${error.message ?: error.javaClass.simpleName}"
                log("hindi sync: source A phase failed — ${error.message}")
            }
        }

        if (HindiOrigin.SOURCE_B in targets) {
            try {
                written += syncRare { remaining -> emit(remaining) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failures += "source B: ${error.message ?: error.javaClass.simpleName}"
                log("hindi sync: source B phase failed — ${error.message}")
            }
        }

        if (failures.size == targets.size) {
            return Result.failure(
                IllegalStateException(failures.joinToString("; ")),
            )
        }
        if (failures.isNotEmpty()) {
            log("hindi sync: partial — ${failures.joinToString("; ")} ($written rows written)")
        }
        log("hindi sync: $written titles written over $pages pages")
        return Result.success(written)
    }

    private suspend fun syncToono(onPage: (Int?) -> Unit): Int {
        var written = 0
        var page = 1

        while (page <= TOONO_MAX_PAGES) {
            val titles = toono.catalog(page, hindiOnly = true)
            if (titles.isEmpty()) {
                // Measured: page 126 is the last and page 127+ answers `html:""`. An empty page is
                // the end signal, never an error.
                log("source A: page $page is empty — catalogue ended after ${page - 1} pages")
                break
            }

            val rows = writeToonoPage(titles)
            written += rows
            if (page % TOONO_LOG_EVERY == 0) log("source A: page $page, $rows rows, $written total")
            onPage(null)

            page += 1
            if (page <= TOONO_MAX_PAGES) delay(TOONO_PAGE_INTERVAL_MS)
        }

        if (page > TOONO_MAX_PAGES) log("source A: stopped at the page ceiling")
        return written
    }

    private suspend fun syncRare(onPage: (Int?) -> Unit): Int {
        var written = 0
        var page = 1
        var totalPages: Int? = null

        while (page <= RARE_MAX_PAGES) {
            // The provider paces itself at 3 s per request and turns a `400` past-the-end into an
            // empty page, so there is nothing to add here.
            val chunk = rareAnimes.hindiCatalogPage(page)
            chunk.totalPages?.let { advertised -> totalPages = advertised }

            if (chunk.titles.isEmpty()) {
                log("ra: page $page is empty — catalogue ended")
                break
            }

            val rows = writeRarePage(chunk.titles)
            written += rows
            log("ra: page $page/${totalPages ?: "?"}, $rows rows, $written total")
            onPage(totalPages?.let { it - page }?.takeIf { it > 0 })

            if (totalPages != null && page >= totalPages) break
            page += 1
        }

        if (totalPages == null) log("ra: x-wp-totalpages was never reported")
        if (page > RARE_MAX_PAGES) log("source B: stopped at the page ceiling")
        return written
    }

    // ------------------------------------------------------------------ mapping: toono

    /**
     * One AJAX page (10 cards) in one `upsertAll`.
     *
     * Cards carry title, slug, poster, year and genres — no date, no status, no episode list. Those
     * three stay empty/0 rather than being guessed: `statusNorm = "finished_airing"` for a show that
     * is still airing is a lie the filter sheet would then offer.
     */
    private suspend fun writeToonoPage(titles: List<DubTitleA>): Int {
        val usable = titles.filter { it.slug.trim().isNotEmpty() }
        if (usable.size != titles.size) {
            log("source A: dropped ${titles.size - usable.size} cards with a blank slug")
        }
        if (usable.isEmpty()) return 0

        val ids = usable.map { HindiIds.toono(it.slug) }
        val stamps = animeDao.stampsFor(ids).associateBy { it.id }
        val now = nowSeconds()

        val rows = usable
            .mapNotNull { title ->
                val id = HindiIds.toono(title.slug)
                runCatching { toonoRow(title, id, stamps[id], now) }
                    .onFailure { log("source A: unmappable card '${title.slug}': ${it.message}") }
                    .getOrNull()
            }
            .distinctBy { it.id }

        if (rows.isEmpty()) return 0
        animeDao.upsertAll(rows)
        return rows.size
    }

    private fun toonoRow(
        title: DubTitleA,
        id: Int,
        previous: AnimeStamp?,
        now: Long,
    ): AnimeEntity {
        val episodeCount = previousEpisodeCount(previous, title.episodeUrls.size)
        val kind = if (title.id.startsWith(TOONO_MOVIE_PATH)) TYPE_MOVIE else TYPE_TV

        return AnimeEntity(
            id = id,
            title = Normalize.cleanText(title.title).ifEmpty { title.slug },
            alternative = "",
            titles = "",
            native = "",
            // Unique index: the provider prefix is what separates two catalogues that reuse slugs.
            slug = HindiOrigin.SOURCE_A.slugPrefix + Normalize.slugOrFallback(title.slug, id),
            rating = "",
            ratingNorm = "",
            poster = title.poster.orEmpty(),
            // `isDub` is an episode COUNT, not a boolean: a Hindi row's dub is its episode list.
            isDub = episodeCount.takeIf { it > 0 },
            isSub = null,
            description = Normalize.cleanText(title.synopsis),
            aired = "",
            season = "",
            seasonNorm = Normalize.normalizeSeason(""),
            year = title.year?.takeIf { it in MIN_YEAR..MAX_YEAR },
            duration = "",
            // The listing publishes neither, and `finished_airing` is NOT a safe default.
            status = "",
            statusNorm = "",
            score = title.rating?.takeIf { it in 0.0..10.0 },
            malId = null,
            episodesDeclared = null,
            episodeCount = episodeCount,
            aniId = null,
            source = null,
            sId = 0L,
            backgroundImage = "",
            genres = Normalize.joinCsv(title.genres),
            studios = "",
            producers = "",
            type = Normalize.normalizeType(kind) ?: kind,
            updatedAt = "",
            updatedAtEpoch = now,
            nextAirAt = null,
            nextAirEp = null,
            detailFetchedAt = previous?.detailFetchedAt,
            // The title page: series and movies are separate routes and the resolver needs the one
            // this row came from (a movie page is also its own player page).
            originUrl = title.url,
        )
    }

    // ------------------------------------------------------------------ mapping: rareanimes

    /**
     * One REST page (100 posts) in one `upsertAll`.
     *
     * The listing has no poster (`featured_media` is only resolved by `title()`), no genre names
     * (categories are numeric ids with no vocabulary worth mapping) and no year, so those columns
     * stay empty instead of being invented from the post date.
     */
    private suspend fun writeRarePage(titles: List<RareTitle>): Int {
        val usable = titles.filter { it.id > 0 }
        if (usable.size != titles.size) {
            log("ra: dropped ${titles.size - usable.size} posts without a post id")
        }
        if (usable.isEmpty()) return 0

        val ids = usable.map { HindiIds.rare(it.id) }
        val stamps = animeDao.stampsFor(ids).associateBy { it.id }
        val now = nowSeconds()

        val rows = usable
            .mapNotNull { post ->
                val id = HindiIds.rare(post.id)
                runCatching { rareRow(post, id, stamps[id], now) }
                    .onFailure { log("ra: unmappable post ${post.id}: ${it.message}") }
                    .getOrNull()
            }
            .distinctBy { it.id }

        if (rows.isEmpty()) return 0
        animeDao.upsertAll(rows)
        return rows.size
    }

    private fun rareRow(
        post: RareTitle,
        id: Int,
        previous: AnimeStamp?,
        now: Long,
    ): AnimeEntity {
        val episodeCount = previousEpisodeCount(previous, post.episodes.size)
        val kind = if (post.isMovie) TYPE_MOVIE else TYPE_TV
        val publishedAt = parsePostDate(post.date)

        return AnimeEntity(
            id = id,
            title = Normalize.cleanText(post.title).ifEmpty { post.slug.ifEmpty { "Hindi #$id" } },
            alternative = "",
            titles = "",
            native = "",
            slug = HindiOrigin.SOURCE_B.slugPrefix + Normalize.slugOrFallback(post.slug, id),
            rating = "",
            ratingNorm = "",
            poster = post.poster,
            isDub = episodeCount.takeIf { it > 0 },
            isSub = null,
            description = Normalize.cleanText(post.synopsis),
            aired = "",
            season = "",
            seasonNorm = Normalize.normalizeSeason(""),
            year = null,
            duration = "",
            status = "",
            statusNorm = "",
            score = null,
            malId = null,
            episodesDeclared = null,
            episodeCount = episodeCount,
            aniId = null,
            source = null,
            sId = 0L,
            backgroundImage = "",
            genres = "",
            studios = "",
            producers = "",
            type = Normalize.normalizeType(kind) ?: kind,
            updatedAt = post.date,
            updatedAtEpoch = publishedAt ?: now,
            nextAirAt = null,
            nextAirEp = null,
            detailFetchedAt = previous?.detailFetchedAt,
            // The WordPress article. `?p=<id>` is measured-reachable, so a blank `link` (a parse
            // regression) still leaves a page the resolver can walk.
            originUrl = post.link.ifBlank { "$RARE_FALLBACK_BASE/?p=${post.id}" },
        )
    }

    // ------------------------------------------------------------------ episodes

    /**
     * Episode rows for one Hindi title, parsed from the provider page — neither site has an episode
     * API, and there is no episode page on rareanimes at all.
     *
     * The rule is the same for both providers: **`subUrl` is the resolution input** and `dubUrl` is
     * always `null`, because the only audio track these catalogues carry is the Hindi dub. `subUrl`
     * being non-null is also what makes `Episode.hasAudio(SUB)` true, which is what the player asks.
     */
    override suspend fun episodes(animeId: Int, originUrl: String): Result<List<Episode>> {
        val origin = HindiIds.originOf(animeId)
            ?: return Result.failure(IllegalArgumentException("$animeId is not a Hindi id"))

        if (originUrl.isBlank()) {
            return Result.failure(
                IllegalStateException("${origin.label} row $animeId has no provider page"),
            )
        }

        return try {
            val episodes = when (origin) {
                HindiOrigin.SOURCE_A -> toonoEpisodes(animeId, originUrl)
                HindiOrigin.SOURCE_B -> rareEpisodes(animeId, originUrl)
            }
            if (episodes.isEmpty()) {
                val cached = runCatching { episodeDao.countForAnime(animeId) }.getOrDefault(0)
                Result.failure(
                    IllegalStateException(
                        "${origin.label} published no playable episode for $animeId" +
                            if (cached > 0) " ($cached rows are already cached)" else "",
                    ),
                )
            } else {
                Result.success(episodes)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log("hindi episodes($animeId) failed: ${error.message}")
            Result.failure(
                IllegalStateException(
                    "Could not read the ${origin.label} episode list: " +
                        (error.message ?: error.javaClass.simpleName),
                    error,
                ),
            )
        }
    }

    /**
     * toono: the title page lists `<li data-episode-id=…>` rows whose anchors point at
     * `/episode/<slug>/`, and each of those pages resolves to the argon embed in two hops.
     *
     * Both are stored — the id saves the resolver the walk, and the page URL is what it re-walks if
     * the id ever stops working. A failed embed is not fatal: the row still carries its page, which
     * is enough for the resolver.
     *
     * Movies have no episode rows at all; the film plays on the title page, so that page is the
     * single episode's URL.
     */
    private suspend fun toonoEpisodes(animeId: Int, originUrl: String): List<Episode> {
        val page = toono.title(originUrl)
        val now = nowSeconds()

        if (page.episodeUrls.isEmpty()) {
            log("source A: $originUrl has no episode rows — treating it as a movie")
            return listOf(
                Episode(
                    id = HindiIds.episodeId(animeId, 1),
                    animeId = animeId,
                    number = 1,
                    title = Normalize.cleanText(page.title),
                    embedId = "",
                    subUrl = originUrl,
                    dubUrl = null,
                    updatedAtEpoch = now,
                ),
            )
        }

        val byNumber = LinkedHashMap<Int, Episode>(page.episodeUrls.size)
        page.episodeUrls.forEachIndexed { index, url ->
            // Slug shape is measured as `<title-slug>-1x3`; the index is the fallback for a page
            // whose slug stopped carrying the season marker.
            val number = episodeNumber(url) ?: (index + 1)
            if (byNumber.containsKey(number)) {
                log("source A: duplicate episode number $number on $originUrl ignored")
                return@forEachIndexed
            }
            if (index > 0) delay(TOONO_EMBED_INTERVAL_MS)
            byNumber[number] = Episode(
                id = HindiIds.episodeId(animeId, number),
                animeId = animeId,
                number = number,
                title = "",
                // Deliberately NOT resolved here: the resolver walks this page at play time anyway,
                // and pre-walking meant one embed request per episode — One Piece (1000+ rows) took
                // minutes before the list even appeared.
                embedId = "",
                subUrl = url,
                dubUrl = null,
                updatedAtEpoch = now,
            )
        }

        return byNumber.values.sortedBy { it.number }
    }

    /**
     * rareanimes: every episode is a `(number → links)` row inside the post body, so one post fetch
     * is the whole list.
     *
     * The player URL for an episode is the `WatchMultiQuality` / argon row; the other hosts on the
     * row are file hosts the resolver explicitly refuses, which is why the first *player* link is
     * the fallback rather than the first link.
     *
     * Movies have no numbered rows: their hosts sit in the null-episode bucket and become episode 1.
     */
    private suspend fun rareEpisodes(animeId: Int, originUrl: String): List<Episode> {
        val postId = HindiIds.rarePostId(animeId)
            ?: return emptyList()

        val stub = RareTitle(
            id = postId,
            slug = "",
            title = "",
            link = originUrl,
            date = "",
        )
        val post = rareAnimes.title(stub)
        val now = nowSeconds()

        val byNumber = LinkedHashMap<Int, Episode>(post.episodes.size)
        for (episode in post.episodes) {
            val link = pickPlayerLink(episode.links)
            if (link == null) {
                log("ra: post ${post.id} episode ${episode.number} has no player link")
                continue
            }
            byNumber[episode.number] = Episode(
                id = HindiIds.episodeId(animeId, episode.number),
                animeId = animeId,
                number = episode.number,
                title = Normalize.cleanText(episode.name),
                // Filled only when the URL already *is* an embed — otherwise the resolver's
                // `embedUrl` hop is what produces the id.
                embedId = argonIdOf(link.url),
                subUrl = link.url,
                dubUrl = null,
                updatedAtEpoch = now,
            )
        }

        if (byNumber.isEmpty()) {
            val movie = pickPlayerLink(post.batchLinks) ?: pickPlayerLink(post.episodeLinks)
            if (movie != null) {
                byNumber[1] = Episode(
                    id = HindiIds.episodeId(animeId, 1),
                    animeId = animeId,
                    number = 1,
                    title = Normalize.cleanText(post.title),
                    embedId = argonIdOf(movie.url),
                    subUrl = movie.url,
                    dubUrl = null,
                    updatedAtEpoch = now,
                )
            }
        }

        return byNumber.values.sortedBy { it.number }
    }

    /** The row the player can open: multiquality/argon first, then any host `embedUrl` can rewrite. */
    private fun pickPlayerLink(links: List<RareLink>): RareLink? {
        val players = links.filter { it.isPlayerLink }
        if (players.isEmpty()) return null

        return players.firstOrNull { link ->
            link.label.contains(WATCH_MARKER, ignoreCase = true) ||
                link.url.contains(EMBED_PATH, ignoreCase = true)
        } ?: players.firstOrNull { it.url.contains(GATE_PATH, ignoreCase = true) }
            ?: players.first()
    }

    /**
     * `…/episode/<slug>/` → the in-season number from `-1x3`, then a trailing `-3`, else `null` so
     * the caller can fall back to the row's position.
     */
    private fun episodeNumber(episodeUrl: String): Int? {
        val slug = episodeUrl.trim().trimEnd('/').substringAfterLast('/')
        SEASON_EPISODE.find(slug)?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
            ?.let { return it }
        TRAILING_NUMBER.find(slug)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
            ?.let { return it }
        return null
    }

    /** `https://argon.razorshell.space/embed/<id>` → `<id>`; anything else → `""`. */
    private fun argonIdOf(url: String): String {
        val id = EMBED_ID.find(url)?.groupValues?.getOrNull(1) ?: return ""
        return if (VIDEO_ID.matches(id)) id else ""
    }

    // ------------------------------------------------------------------ reads

    /**
     * Titles currently in the Hindi catalogue.
     *
     * `animeDao.count()` cannot be used: it is the whole table, and the original catalogue is the
     * larger half of it. The exact figure is paged out of the browse statement the app already
     * uses ([FilterSql] with `hindiOnly`), which is a primary-key range scan on `id < 0` — three
     * queries for the full ~3,000-row catalogue and no schema change.
     *
     * TODO(dao): replace with `@Query("SELECT COUNT(*) FROM anime WHERE id < 0")` once the DAO is
     *  ours to edit; this class deliberately does not modify it.
     */
    override suspend fun catalogCount(): Int {
        var offset = 0
        var total = 0
        var pages = 0

        while (pages < COUNT_PAGE_LIMIT) {
            val rows = hindiRows(limit = COUNT_PAGE_SIZE, offset = offset, sort = SortOrder.RECENT)
            total += rows.size
            if (rows.size < COUNT_PAGE_SIZE) return total
            offset += COUNT_PAGE_SIZE
            pages += 1
        }

        log("hindi: catalogCount stopped at the $COUNT_PAGE_LIMIT-page ceiling")
        return total
    }

    /**
     * Which providers already have rows, sampled from the sorted catalogue.
     *
     * Deliberately title-ordered, not recent-ordered: toono rows are stamped with the wall clock at
     * every sync, so a "recent" sample would hold toono alone and declare rareanimes missing on
     * every re-entry. A sample that misses a provider costs a redundant (idempotent) sync; one that
     * invents a provider is impossible, because the ids are classified by range.
     */
    private suspend fun hindiOriginsPresent(): Set<HindiOrigin> =
        hindiRows(limit = PRESENCE_SAMPLE, offset = 0, sort = SortOrder.TITLE_ASC)
            .mapNotNull { HindiIds.originOf(it.id) }
            .toSet()

    private suspend fun hindiRows(limit: Int, offset: Int, sort: SortOrder): List<AnimeEntity> {
        val statement = FilterSql.build(
            match = null,
            filters = Filters(hindiOnly = true, sort = sort),
            limit = limit,
            offset = offset,
        )
        return animeDao
            .observeFiltered(SimpleSQLiteQuery(statement.sql, statement.args.toTypedArray()))
            .first()
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A catalog page rewrites the whole row, and the detail pass is what owns `episodeCount`
     * (`updateDetailMeta`). Carry the larger of "what Room already knows" and "what this listing
     * carries" so a sync never resets a 12-episode title to 0 — the same rule `DtoMappers` applies
     * with its `previous` stamp.
     */
    private fun previousEpisodeCount(previous: AnimeStamp?, knownNow: Int): Int =
        maxOf(previous?.episodeCount ?: 0, knownNow)

    /**
     * WordPress REST `date` is `2026-11-17T09:12:44` (site-local, no offset); the app's column is
     * UTC seconds, the unit every other row carries (`Normalize.parseUtcSeconds`).
     */
    private fun parsePostDate(raw: String): Long? {
        val value = raw.trim().replace('T', ' ').take(DATE_LENGTH)
        val epoch = Normalize.parseUtcSeconds(value)
        return epoch.takeIf { it > 0L }
    }

    /**
     * Epoch **seconds**, matching the column's meaning for API rows. A millisecond fallback would
     * outrank the entire original catalogue in every `RECENT` sort for as long as the install lives.
     */
    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private companion object {
        const val MIN_YEAR = 1900
        const val MAX_YEAR = 2100

        const val TYPE_TV = "TV"
        const val TYPE_MOVIE = "Movie"
        const val TOONO_MOVIE_PATH = "movies/"

        /** Measured: 126 pages × 10 = 1,260 titles; page 127 is empty. */
        const val TOONO_ESTIMATED_PAGES = 126
        const val TOONO_MAX_PAGES = 200
        const val TOONO_LOG_EVERY = 10

        /** The AJAX endpoint is cheap (one small JSON body) — a browser-like gap is enough. */
        const val TOONO_PAGE_INTERVAL_MS = 250L

        /**
         * Gap between the two-hop embed walks of one series. `ToonoProvider` has no pacing of its
         * own, and a 100-episode page would otherwise fire 200 requests back to back.
         */
        const val TOONO_EMBED_INTERVAL_MS = 120L

        /** Measured: 18 pages × 100 = 1,715 titles; the provider paces itself at 3 s. */
        const val RARE_ESTIMATED_PAGES = 18
        const val RARE_MAX_PAGES = 60

        const val COUNT_PAGE_SIZE = FilterSql.MAX_LIMIT
        const val COUNT_PAGE_LIMIT = 40
        const val PRESENCE_SAMPLE = 300

        const val DATE_LENGTH = 19
        val RARE_FALLBACK_BASE: String get() = ProviderVault.url(ProviderKey.DUB_HOST_B)

        val WATCH_MARKER: String get() = ProviderVault.text(ProviderKey.DUB_WATCH_MARKER)
        const val EMBED_PATH = "/embed/"
        val GATE_PATH: String get() = ProviderVault.text(ProviderKey.DUB_GATE_PATH)

        val SEASON_EPISODE = Regex("(\\d{1,2})x(\\d{1,4})")
        val TRAILING_NUMBER = Regex("-(\\d{1,4})$")
        val EMBED_ID = Regex("/embed/([A-Za-z0-9_-]{3,64})")
        val VIDEO_ID = Regex("^[A-Za-z0-9_-]{3,64}$")
    }
}
