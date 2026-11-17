package com.animo.app.data.sync

import androidx.room.withTransaction
import com.animo.app.AppLog
import com.animo.app.data.local.AnimoDatabase
import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.remote.AnikotoApi
import com.animo.app.data.remote.ApiEvent
import com.animo.app.data.remote.ApiException
import com.animo.app.data.remote.toEntity
import com.animo.app.domain.model.SyncProgress
import com.animo.app.domain.model.SyncState
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * The whole catalog job: 95 pages x 100 rows (~11.8 MB) plus a delta-driven detail pass.
 *
 * Measured timings (research/DATA_CONTRACT.md §6): 95 pages at the 2.2 s gate = 3.2 min. Detail
 * fetches are capped by [DETAIL_BUDGET] so a single job can never approach the ~10 min budget
 * WorkManager gives a worker.
 *
 * Concurrency: a non-reentrant [Mutex] with `tryLock` — a second caller while a sync is in flight
 * gets a failure instead of silently queueing a duplicate 3-minute job.
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
            val rows = response.data.map { dto -> dto.toEntity(stamps[dto.id]) }

            for (row in rows) {
                val previous = stamps[row.id]
                when {
                    previous == null -> if (force) changed.add(row.id)
                    previous.updatedAtEpoch != row.updatedAtEpoch -> changed.add(row.id)
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
        }

        if (pagesDone == 0) {
            return Result.failure(IllegalStateException("catalog came back empty"))
        }

        db.rebuildSearchIndex()
        AppLog.i("catalog pass: $pagesDone pages, $seen series, ${changed.size} changed")

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

        /** Detail round trips per job: 60 * 2.2 s ≈ 132 s on top of the ~210 s catalog pass. */
        const val DETAIL_BUDGET = 60

        /** Quota signals — stop the detail pass rather than burn the remaining window. */
        val STOP_CODES = setOf(403, 429)
    }
}
