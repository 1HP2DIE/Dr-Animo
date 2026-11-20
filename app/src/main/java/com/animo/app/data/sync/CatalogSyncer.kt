package com.animo.app.data.sync

import androidx.room.withTransaction
import com.animo.app.AppLog
import com.animo.app.data.local.AnimoDatabase
import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.remote.AnikotoApi
import com.animo.app.data.remote.ApiEvent
import com.animo.app.data.remote.ApiException
import com.animo.app.data.remote.toEntity
import com.animo.app.domain.model.ContentKind
import com.animo.app.domain.model.Providers
import com.animo.app.domain.model.SyncProgress
import com.animo.app.domain.model.SyncState
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Catalog delta walk.
 *
 * `/recent-anime` is ordered `updated_at` **descending** (measured across pages 1-3), so a routine
 * refresh only has to read the head of the list: once [UNCHANGED_PAGES_BEFORE_STOP] consecutive
 * pages contain nothing new and nothing moved, everything below them is already in Room. A normal
 * night is now 1-3 pages (~7 s) instead of the 95-page, 3.2-minute full walk the app used to do
 * every day — which was both slow and the fastest route to a rate limit.
 *
 * A `force` call still walks the whole catalogue: that is the user's explicit "Sync now".
 *
 * Concurrency: a non-reentrant [Mutex] with `tryLock` — a second caller while a sync is in flight
 * gets a failure instead of silently queueing a duplicate job.
 */
class CatalogSyncer(
    private val api: AnikotoApi,
    private val db: AnimoDatabase,
    private val animeDao: AnimeDao,
    private val seriesFetcher: SeriesFetcher,
) {

    private val mutex = Mutex()

    suspend fun sync(
        force: Boolean = false,
        onProgress: suspend (SyncProgress) -> Unit,
    ): Result<Unit> {
        if (!mutex.tryLock()) {
            return Result.failure(IllegalStateException("catalog sync already running"))
        }

        try {
            return coroutineScope {
                val latest = AtomicReference(SyncProgress(0, 0, SyncState.RUNNING, "starting"))

                val watcher = launch {
                    api.events.collect { event ->
                        val current = latest.get()
                        when (event) {
                            is ApiEvent.Throttled -> onProgress(
                                current.copy(
                                    state = SyncState.THROTTLED,
                                    message = "backoff " + (event.waitMs / 1000L) + "s",
                                ),
                            )

                            is ApiEvent.LowQuota -> onProgress(
                                current.copy(
                                    state = SyncState.THROTTLED,
                                    message = "quota " + event.remaining + ", waiting " +
                                        (event.waitMs / 1000L) + "s",
                                ),
                            )
                        }
                    }
                }

                try {
                    runSync(force) { progress ->
                        latest.set(progress)
                        onProgress(progress)
                    }
                } finally {
                    watcher.cancel()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            AppLog.e("catalog sync failed", error)
            onProgress(SyncProgress(0, 0, SyncState.ERROR, error.message ?: "sync failed"))
            return Result.failure(error)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runSync(
        force: Boolean,
        emit: suspend (SyncProgress) -> Unit,
    ): Result<Unit> {
        emit(SyncProgress(0, 0, SyncState.RUNNING, "catalog"))

        val changed = LinkedHashSet<Int>()
        var page = 1
        var pagesDone = 0
        var totalPages = 0
        var seen = 0
        var unchangedStreak = 0
        var newRows = 0

        while (page <= MAX_PAGES) {
            val response = api.recentAnime(page).getOrElse { error ->
                emit(
                    SyncProgress(
                        done = pagesDone,
                        total = totalPages.coerceAtLeast(1),
                        state = SyncState.ERROR,
                        message = error.message ?: "page $page failed",
                    ),
                )
                return Result.failure(error)
            }

            // `page > total_pages` answers 200 + [] — that is the only reliable end signal.
            if (response.data.isEmpty()) break

            if (response.pagination.totalPages > 0) totalPages = response.pagination.totalPages

            val stamps = animeDao.stampsFor(response.data.map { it.id }).associateBy { it.id }
            // The DTO mapper predates the v6 owner columns, so this walk stamps them: `/recent-anime`
            // is one catalogue with one kind and one audio language, whatever the row's format is.
            val rows = response.data.map { dto ->
                dto.toEntity(stamps[dto.id]).copy(
                    providerId = Providers.ORIGINAL,
                    contentKind = ContentKind.ANIME.name,
                    language = ORIGINAL_LANGUAGE,
                )
            }

            var pageChanged = 0
            for (row in rows) {
                val previous = stamps[row.id]
                when {
                    previous == null -> {
                        newRows++
                        pageChanged++
                        // A brand-new row has no detail yet; the episode queue picks it up first.
                        changed.add(row.id)
                    }
                    previous.updatedAtEpoch != row.updatedAtEpoch -> {
                        changed.add(row.id)
                        pageChanged++
                    }
                }
            }

            db.withTransaction { animeDao.upsertAll(rows) }

            pagesDone = page
            seen += rows.size

            emit(
                SyncProgress(
                    done = pagesDone,
                    total = totalPages.coerceAtLeast(pagesDone),
                    state = SyncState.RUNNING,
                    message = "catalog " + seen + " series",
                ),
            )

            page++

            // Early stop: the list is newest-first, so a run of untouched pages means the tail is
            // already local. `force` is the user's explicit full refresh and never stops early.
            unchangedStreak = if (pageChanged == 0) unchangedStreak + 1 else 0
            if (!force && unchangedStreak >= UNCHANGED_PAGES_BEFORE_STOP) {
                AppLog.i("catalog delta: stop after $pagesDone page(s), tail is unchanged")
                break
            }
        }

        if (pagesDone == 0) {
            return Result.failure(IllegalStateException("catalog came back empty"))
        }

        db.rebuildSearchIndex()
        AppLog.i(
            "catalog pass: $pagesDone pages, $seen series, ${changed.size} changed, $newRows new",
        )

        val detailsFetched = fetchChangedDetails(changed, emit)

        emit(
            SyncProgress(
                done = seen,
                total = totalPages.coerceAtLeast(pagesDone),
                state = SyncState.IDLE,
                message = "catalog " + seen + ", details " + detailsFetched,
            ),
        )
        return Result.success(Unit)
    }

    /**
     * Only rows whose `updated_at` moved are re-fetched — a first-ever pass would otherwise want
     * 9,404 detail calls (5.2 h, DATA_CONTRACT §6). Brand-new rows are left to the on-demand
     * backfill that runs when a detail screen opens.
     */
    private suspend fun fetchChangedDetails(
        changed: Set<Int>,
        emit: suspend (SyncProgress) -> Unit,
    ): Int {
        if (changed.isEmpty()) return 0

        var fetched = 0
        for (id in changed) {
            if (fetched >= DETAIL_BUDGET) {
                AppLog.w("detail budget ($DETAIL_BUDGET) exhausted; ${changed.size - fetched} rows deferred")
                break
            }

            val result = seriesFetcher.fetch(id)
            if (result.isSuccess) {
                fetched++
                emit(
                    SyncProgress(
                        done = fetched,
                        total = changed.size,
                        state = SyncState.RUNNING,
                        message = "details " + fetched + "/" + changed.size,
                    ),
                )
                continue
            }

            val error = result.exceptionOrNull()
            if (error is ApiException && error.code in STOP_CODES) {
                AppLog.w("detail pass stopped after $fetched (HTTP ${error.code})")
                break
            }
        }
        return fetched
    }

    private companion object {
        /** Hard ceiling for a runaway `total_pages`; the real catalog is 95. */
        const val MAX_PAGES = 400

        /** The original API catalogues Japanese-audio anime; its spec declares the same tag. */
        const val ORIGINAL_LANGUAGE = "ja"

        /**
         * Stop after this many consecutive pages with nothing new and nothing moved. Two, not one:
         * a single quiet page can happen right after a catalog reshuffle, and the cost of a false
         * stop (missing an update until the next run) is higher than two extra requests.
         */
        const val UNCHANGED_PAGES_BEFORE_STOP = 2

        /** Detail round trips per job: 60 * 2.2 s ≈ 132 s on top of the ~210 s catalog pass. */
        const val DETAIL_BUDGET = 60

        /** Quota signals — stop the detail pass rather than burn the remaining window. */
        val STOP_CODES = setOf(403, 429)
    }
}
