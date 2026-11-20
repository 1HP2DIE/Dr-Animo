package com.animo.app.data.sync

import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.local.dao.SlugStamp
import com.animo.app.data.local.dao.SyncCursorDao
import com.animo.app.data.local.entity.AnimeEntity
import com.animo.app.data.local.entity.SyncCursorEntity
import com.animo.app.data.remote.provider.movielink.MovieLinkCatalogue
import com.animo.app.data.remote.provider.movielink.MovieLinkParser

/**
 * Where the MovieLink walk stopped, in domain terms.
 *
 * [lastPage] is the last page **fully written** to Room — not the next page to fetch — so a run
 * that is cancelled, killed or blocked resumes at [nextPage] and can never skip a page that was
 * only half committed. A page number at or past the catalogue end wraps to page 1: the listing is
 * newest-first, so the next run re-walks the head and picks up titles added since.
 */
data class MovieLinkCursor(
    val lastPage: Int = 0,
    val lastSyncEpoch: Long = 0L,
) {
    /** First page of the next run. `0` (never synced) and a wrapped catalog both answer `1`. */
    val nextPage: Int
        get() {
            if (lastPage >= MovieLinkParser.MAX_PAGE) return 1
            return (lastPage + 1).coerceAtLeast(1)
        }
}

/**
 * The two reads and one write the syncer needs from the `anime` table.
 *
 * Narrow on purpose: the walk is unit-tested on the JVM, and an Android-free seam is the difference
 * between a fake with two methods and a fake [AnimeDao] with thirty.
 */
interface MovieLinkStore {

    /**
     * Existing rows for [ids] — [SlugStamp.slug] is what detects a hash collision, the other two
     * columns are what a page upsert must carry forward instead of resetting.
     */
    suspend fun slugStamps(ids: List<Int>): List<SlugStamp>

    /** One statement per page. Row-level conflicts are the caller's problem, not the store's. */
    suspend fun upsertAll(rows: List<AnimeEntity>)
}

/** [MovieLinkStore] over the real DAO. Writes nothing but `anime` rows. */
class RoomMovieLinkStore(private val animeDao: AnimeDao) : MovieLinkStore {

    override suspend fun slugStamps(ids: List<Int>): List<SlugStamp> =
        if (ids.isEmpty()) emptyList() else animeDao.slugStampsFor(ids)

    override suspend fun upsertAll(rows: List<AnimeEntity>) {
        if (rows.isNotEmpty()) animeDao.upsertAll(rows)
    }
}

/** Persistence seam for [MovieLinkCursor]. */
interface MovieLinkCursorStore {
    suspend fun read(): MovieLinkCursor
    suspend fun write(cursor: MovieLinkCursor)
}

/**
 * [MovieLinkCursorStore] over [SyncCursorDao], one row per provider.
 *
 * [provider] defaults to the catalogue's id so the movement of a cursor is impossible to get wrong
 * at a call site; it stays a parameter so a test (or a second mirror) can use its own row.
 */
class RoomMovieLinkCursorStore(
    private val dao: SyncCursorDao,
    private val provider: String = MovieLinkCatalogue.PROVIDER_ID,
) : MovieLinkCursorStore {

    override suspend fun read(): MovieLinkCursor =
        dao.find(provider)?.let { MovieLinkCursor(it.lastPage, it.lastSyncEpoch) } ?: MovieLinkCursor()

    override suspend fun write(cursor: MovieLinkCursor) {
        dao.put(SyncCursorEntity(provider, cursor.lastPage, cursor.lastSyncEpoch))
    }
}
