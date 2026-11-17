package com.animo.app.data.remote

import com.animo.app.BuildConfig
import com.animo.app.data.remote.dto.AnimoJson
import com.animo.app.data.remote.dto.RecentAnimeResponse
import com.animo.app.data.remote.dto.SeriesDataDto
import com.animo.app.data.remote.dto.SeriesResponse
import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Wired failure carrying the HTTP status, so the sync layer can tell retryable from terminal. */
class ApiException(
    val code: Int,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * The only class in the app that knows the JSON field names and the endpoint paths.
 *
 * Everything is gated through [RequestGate] so a 9,404-series sync can never self-throttle into a
 * 403. The public surface is deliberately two calls: one catalog page and one series detail.
 */
class AnikotoApi(
    private val client: OkHttpClient,
    private val json: Json = AnimoJson,
    private val baseUrl: String = ProviderVault.url(ProviderKey.CATALOG_API),
) {

    private val _events = MutableSharedFlow<ApiEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Throttle signals for the sync layer; nothing else needs them. */
    val events: SharedFlow<ApiEvent> = _events.asSharedFlow()

    private val gate = RequestGate { event -> _events.tryEmit(event) }

    private val base: String = baseUrl.trimEnd('/')

    /**
     * `GET /recent-anime?page={page}&per_page={perPage}`.
     *
     * `per_page` above 100 is silently clamped server-side, so clamp locally instead of wasting a
     * round trip. `page > total_pages` answers 200 + `data: []` (never 404) — callers must treat an
     * empty list as "end of catalog", never trust their own pagination arithmetic.
     */
    suspend fun recentAnime(
        page: Int,
        perPage: Int = BuildConfig.SYNC_PAGE_SIZE,
    ): Result<RecentAnimeResponse> {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, BuildConfig.SYNC_PAGE_SIZE)

        val body = get("/recent-anime?page=$safePage&per_page=$safePerPage").getOrElse { error ->
            return Result.failure(error)
        }

        val parsed = try {
            json.decodeFromString(RecentAnimeResponse.serializer(), body)
        } catch (error: Exception) {
            return Result.failure(
                ApiException(200, "catalog page $safePage: unparseable body (${error.message})", error),
            )
        }

        if (!parsed.ok) {
            return Result.failure(ApiException(200, "catalog page $safePage: ok=false"))
        }
        return Result.success(parsed)
    }

    /**
     * `GET /series/{id}` -> `data.anime` + `data.episodes`.
     *
     * `data.episodes` is legitimately `[]` for 7/40 series with HTTP 200, so an empty list is not an
     * error and must never wipe locally stored episodes.
     */
    suspend fun series(id: Int): Result<SeriesDataDto> {
        val body = get("/series/$id").getOrElse { error ->
            return Result.failure(error)
        }

        val parsed = try {
            json.decodeFromString(SeriesResponse.serializer(), body)
        } catch (error: Exception) {
            return Result.failure(ApiException(200, "series $id: unparseable body (${error.message})", error))
        }

        val data = parsed.data
        if (!parsed.ok || data == null) {
            return Result.failure(ApiException(404, parsed.error ?: "series $id unavailable"))
        }
        return Result.success(data)
    }

    // ------------------------------------------------------------------ transport

    private suspend fun get(path: String): Result<String> {
        var lastError: Throwable? = null

        for (attempt in 0 until MAX_ATTEMPTS) {
            gate.acquire()
            val request = buildRequest(path)

            val outcome = try {
                client.newCall(request).awaitResponse().use { response ->
                    gate.onResponse(response)
                    if (response.isSuccessful) {
                        FetchOutcome.Body(response.body?.string().orEmpty())
                    } else {
                        FetchOutcome.HttpError(response.code)
                    }
                }
            } catch (error: IOException) {
                FetchOutcome.Failure(error)
            }

            when (outcome) {
                is FetchOutcome.Body -> return Result.success(outcome.text)

                is FetchOutcome.HttpError -> {
                    if (outcome.code !in RETRYABLE_CODES) {
                        return Result.failure(ApiException(outcome.code, "HTTP ${outcome.code} on $path"))
                    }
                    lastError = ApiException(outcome.code, "HTTP ${outcome.code} on $path")
                    gate.penalize(outcome.code, attempt)
                }

                is FetchOutcome.Failure -> {
                    lastError = outcome.error
                    gate.penalize(0, attempt)
                }
            }
        }

        return Result.failure(lastError ?: ApiException(0, "request failed: $path"))
    }

    private fun buildRequest(path: String): Request = Request.Builder()
        .url(base + path)
        .get()
        .header("User-Agent", HttpClientFactory.UA_DESKTOP)
        .header("Accept", "application/json, text/plain, */*")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Referer", "$base/")
        .build()

    private sealed interface FetchOutcome {
        data class Body(val text: String) : FetchOutcome
        data class HttpError(val code: Int) : FetchOutcome
        data class Failure(val error: IOException) : FetchOutcome
    }

    private companion object {
        const val MAX_ATTEMPTS = 3

        /** 403 is included on purpose: the API answers 403 rather than 429 when it judges abuse. */
        val RETRYABLE_CODES = setOf(403, 408, 429, 500, 502, 503, 504)
    }
}

/**
 * Cancellation-aware bridge from OkHttp's callback API to coroutines. Blocking `execute()` would pin
 * a thread through every 2.2 s gate wait.
 */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resume(response)
            }

            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        },
    )
    continuation.invokeOnCancellation {
        runCatching { cancel() }
    }
}
