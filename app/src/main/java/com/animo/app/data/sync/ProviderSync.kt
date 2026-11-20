package com.animo.app.data.sync

import com.animo.app.data.local.dao.ProviderPrefDao
import com.animo.app.data.local.entity.ProviderPrefEntity
import com.animo.app.di.ServiceLocator
import com.animo.app.domain.model.Providers
import com.animo.app.domain.model.SyncMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * One facade over every catalogue's sync entry point, plus the preference gate in front of it.
 *
 * The three catalogues were built separately and their syncers disagree on small things — one
 * returns a row count, another only `Unit`; one takes a suspending progress callback, another a
 * plain one. Callers (the nightly [SyncWorker], the provider page's Sync button) should not have to
 * know any of that: they name a provider id and get a `Result<Int>`, a mode to render, and a count to
 * display.
 *
 * ## What a provider id means
 * Ids are [Providers]'s compiled-in strings, which are also the values stored in `anime.providerId`.
 * An id that is not in [Providers.ALL] is a programming error (`IllegalArgumentException`), while a
 * *known* id whose syncer was not registered in this build — the MovieLink and Hindi sources are
 * optional vault slots — is a runtime state (`IllegalStateException`, "not available in this
 * build"). The difference matters to the UI: the first is a bug, the second is a build with fewer
 * sources.
 *
 * ## Failure shape
 * [sync] never throws for a provider problem. A network, parse or database error is logged as one
 * line — the class name only, because exception messages from the transport layer can carry a
 * hostname and a hostname is not something the viewer can act on — and returned as
 * `Result.failure`. Cancellation is the one exception: it is rethrown untouched, so a cancelled
 * worker stops instead of recording a failure.
 *
 * @param prefs the per-provider preference table. Read once per [syncMode] call, which is cheap
 *   (one indexed row) and always reflects a toggle the viewer flipped a moment ago.
 * @param log one-line trace sink, no `android.util.Log` so the class runs on the JVM.
 */
class ProviderSync(
    private val prefs: ProviderPrefDao,
    private val log: (String) -> Unit = {},
) {

    /**
     * The mode that governs [providerId] right now.
     *
     * A stored row wins. Otherwise the compiled-in default applies, and an id nobody compiled in is
     * [SyncMode.MANUAL] — the safe answer, because it means "only an explicit tap may fetch".
     *
     * An unreadable stored value fails **closed** to [SyncMode.MANUAL] rather than falling through to
     * the default: a row that exists is the viewer's choice, and a corrupt one must not silently
     * promote a provider back to [SyncMode.AUTO] and start spending data. A database read error is
     * handled the same way, for the same reason.
     */
    suspend fun syncMode(providerId: String): SyncMode {
        val stored = try {
            prefs.find(providerId)?.syncMode
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log(
                "provider $providerId: preference unreadable " +
                    "(${error.javaClass.simpleName}) — treating it as manual",
            )
            return SyncMode.MANUAL
        }

        if (stored != null) return parseMode(stored) ?: SyncMode.MANUAL
        return Providers.spec(providerId)?.defaultSyncMode ?: SyncMode.MANUAL
    }

    /**
     * Records the viewer's choice.
     *
     * Choosing the provider's compiled-in default is a **reset**, not a preference: the row is
     * deleted, so the table stays empty on a fresh install and a later default (a build that ships a
     * different one) is not shadowed by a stale row. Database errors propagate — a toggle that did
     * not persist should not look like it did.
     */
    suspend fun setMode(providerId: String, mode: SyncMode) {
        if (mode == Providers.spec(providerId)?.defaultSyncMode) {
            prefs.clear(providerId)
        } else {
            prefs.put(ProviderPrefEntity(provider = providerId, syncMode = mode.name))
        }
        log("provider $providerId: sync mode ${mode.name}")
    }

    /**
     * Rows stored for [providerId].
     *
     * A count that cannot be read is logged and reported as `0`: this is a display value on the
     * provider page, and an empty list is a much better outcome than crashing the screen that would
     * have shown it.
     */
    suspend fun countFor(providerId: String): Int =
        try {
            ServiceLocator.animeDao.countForProvider(providerId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log("provider $providerId: count failed (${error.javaClass.simpleName})")
            0
        }

    /**
     * Runs [providerId]'s delta walk.
     *
     * @param force pass through to the syncer: `true` restarts from the head of the catalogue (an
     *   explicit "Sync now"), `false` continues from where the stored cursor left off.
     * @param onProgress `(done, total)` in the provider's own units — pages for MovieLink, titles for
     *   Hindi, a single `1/1` tick for the original catalogue, whose walker reports only success. A
     *   throwing callback is logged and ignored; it must not turn a successful sync into a failure.
     * @return the number of rows the run brought in, per provider:
     *   - original — **rows gained**, `count(after) - count(before)`. Its walker returns `Unit` and
     *     updates are invisible to the difference, but it is honest and useful for "N new titles".
     *   - Hindi and MovieLink — their own row counts, exactly as they report them.
     */
    suspend fun sync(
        providerId: String,
        force: Boolean,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Result<Int> {
        if (Providers.spec(providerId) == null) {
            return Result.failure(IllegalArgumentException("unknown provider: $providerId"))
        }

        // Checked before the dispatch, not inside it: OFF means "do not touch the network at all",
        // and a syncer that is not registered must not even be asked.
        if (syncMode(providerId) == SyncMode.OFF) {
            log("provider $providerId: sync is off — refused")
            return Result.failure(SyncDisabledException(providerId))
        }

        return try {
            when (providerId) {
                Providers.ORIGINAL -> syncOriginal(force, onProgress)
                Providers.HINDI -> syncHindi(force, onProgress)
                Providers.MOVIE_LINK -> syncMovieLink(force, onProgress)
                else -> Result.failure(IllegalArgumentException("unknown provider: $providerId"))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // Class name only: a transport message can carry a hostname.
            log("provider $providerId: sync failed (${error.javaClass.simpleName})")
            Result.failure(error)
        }
    }

    /**
     * Providers the nightly worker may touch: [SyncMode.AUTO] **and** wired in this build.
     *
     * The availability filter is deliberate. A catalogue whose vault slots are not provisioned has no
     * syncer, so including it would guarantee one failure per tick and, on a night when the other
     * providers are also unreachable, would turn a "nothing to do" night into a retry storm.
     *
     * Read errors propagate: unlike [syncMode], this is not a display value. The worker treats an
     * unreadable preference table as a transient failure and retries the tick rather than silently
     * skipping the night.
     */
    suspend fun autoProviderIds(): List<String> {
        val stored: Map<String, SyncMode> =
            prefs.all().associate { it.provider to (parseMode(it.syncMode) ?: SyncMode.MANUAL) }

        return Providers.ALL
            .filter { isAvailable(it.id) }
            .filter { spec -> (stored[spec.id] ?: spec.defaultSyncMode) == SyncMode.AUTO }
            .map { it.id }
    }

    // ------------------------------------------------------------------ dispatch

    private suspend fun syncOriginal(
        force: Boolean,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Result<Int> {
        // `force = false` is the cheap delta walk; the count before/after is the only row figure the
        // original catalogue can offer (see the KDoc on [sync]).
        val before = countFor(Providers.ORIGINAL)
        emit(onProgress, 0, 1)
        val refreshed = ServiceLocator.animeRepository.refreshCatalog(force)
        refreshed.exceptionOrNull()?.let { return Result.failure(it) }
        emit(onProgress, 1, 1)
        return Result.success((countFor(Providers.ORIGINAL) - before).coerceAtLeast(0))
    }

    /**
     * `HindiCatalog.sync` takes a **plain** callback, while this facade's contract is a suspending
     * one. A conflated channel bridges the two without blocking the caller's thread: the catalogue
     * drops a tick when it produces faster than the viewer's UI consumes it, which is exactly the
     * behaviour a progress bar wants, and the final tick is still delivered because the forwarder
     * drains the buffer before the scope returns.
     */
    private suspend fun syncHindi(
        force: Boolean,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Result<Int> {
        val catalog = ServiceLocator.hindiCatalog
            ?: return Result.failure(notAvailable(Providers.HINDI))

        return coroutineScope {
            val progress = Channel<Pair<Int, Int>>(Channel.CONFLATED)
            val forwarder = launch {
                for ((done, total) in progress) emit(onProgress, done, total)
            }

            val result = try {
                catalog.sync(force) { done, total -> progress.trySend(done to total) }
            } finally {
                progress.close()
            }

            forwarder.join()
            result
        }
    }

    private suspend fun syncMovieLink(
        force: Boolean,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Result<Int> {
        // Signature matches the facade's: a suspending `(done, total) -> Unit`, pages as the unit.
        val syncer = ServiceLocator.movieLinkSyncer
            ?: return Result.failure(notAvailable(Providers.MOVIE_LINK))
        return syncer.sync(force, onProgress)
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Whether this build actually wired the provider's syncer. The original catalogue is always
     * present; Hindi and MovieLink are registered by `AnimoApp` only when their vault slots exist.
     */
    private fun isAvailable(providerId: String): Boolean = when (providerId) {
        Providers.ORIGINAL -> true
        Providers.HINDI -> ServiceLocator.hindiCatalog != null
        Providers.MOVIE_LINK -> ServiceLocator.movieLinkSyncer != null
        else -> false
    }

    private fun notAvailable(providerId: String): Throwable =
        IllegalStateException("provider $providerId is not available in this build")

    /** A progress callback that throws is a UI bug; it must not fail the download behind it. */
    private suspend fun emit(
        onProgress: suspend (done: Int, total: Int) -> Unit,
        done: Int,
        total: Int,
    ) {
        try {
            onProgress(done, total)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log("provider sync: progress callback threw (${error.javaClass.simpleName})")
        }
    }

    /** Never throws: anything unreadable is `null`, and every caller decides what that means. */
    private fun parseMode(raw: String): SyncMode? {
        val name = raw.trim()
        return SyncMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * [ProviderSync.sync] was called for a provider the viewer switched to [SyncMode.OFF].
 *
 * A distinct type because this is the one failure a caller should not render as an error: the
 * refusal is the feature. It extends [IllegalStateException] so existing catch blocks still see it.
 */
class SyncDisabledException(val providerId: String) :
    IllegalStateException("sync is disabled for provider $providerId")
