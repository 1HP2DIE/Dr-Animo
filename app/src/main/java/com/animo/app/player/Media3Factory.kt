@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.animo.app.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * All Media3 wiring for this app.
 *
 * The critical invariant (research/RESOLVER_SPEC.md §3, research/STACK.md): one
 * `OkHttpDataSource.Factory` **per playback**, because `setDefaultRequestProperties` mutates the
 * factory and is inherited by data sources that already exist — a shared factory leaks one host's
 * Referer onto another host's segments and races between simultaneous playbacks. The [OkHttpClient]
 * itself is immutable and safely shared.
 */
@OptIn(UnstableApi::class)
object Media3Factory {

    const val DEFAULT_USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Verified gate for the argon/juicy CDNs behind the Hindi sources (see [headers]). */
    const val ACCEPT_LANGUAGE: String = "en-US,en;q=0.6"

    private const val RETRY_COUNT = 5
    private const val CONNECT_TIMEOUT_S = 15L
    private const val READ_TIMEOUT_S = 20L

    private const val MIN_BUFFER_MS = 15_000
    private const val MAX_BUFFER_MS = 60_000
    private const val BUFFER_FOR_PLAYBACK_MS = 2_000
    private const val BUFFER_AFTER_REBUFFER_MS = 5_000

    /** Shared connection pool; the per-playback data-source factory is what must stay unshared. */
    val playbackClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Headers applied to the multivariant playlist, the media playlist AND every segment.
     *
     * Measured on both CDN families (2026-11-17):
     *  - megaplay: `Referer` is the gate (RESOLVER_SPEC §3); `Origin`/`Accept` keep Cloudflare happy.
     *  - argon/juicy (the Hindi providers): the gate is **`Accept-Language`**, not Referer. Measured
     *    matrix on a fresh master: UA only 403 · UA+Accept-Language **200** · UA+Referer 403 ·
     *    Referer only 403. A browser is not needed — only this header was missing.
     *
     * Sending Accept-Language to megaplay as well is harmless (it is not in any allowlist check),
     * so one header map keeps both families working instead of splitting the player contract.
     */
    fun headers(referer: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "Origin" to referer.trimEnd('/'),
        "Accept" to "*/*",
        "Accept-Language" to ACCEPT_LANGUAGE,
    )

    fun dataSourceFactory(
        referer: String,
        userAgent: String = DEFAULT_USER_AGENT,
        client: OkHttpClient = playbackClient,
    ): OkHttpDataSource.Factory = OkHttpDataSource.Factory(client)
        .setUserAgent(userAgent)
        .setDefaultRequestProperties(headers(referer))

    fun mediaItem(masterUrl: String, mediaId: String = masterUrl): MediaItem =
        MediaItem.Builder()
            .setUri(masterUrl)
            .setMediaId(mediaId)
            .build()

    /** ~5 exponential-backoff retries for manifest and segment loads (research/STACK.md). */
    fun errorPolicy(): LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(RETRY_COUNT)

    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_AFTER_REBUFFER_MS,
        )
        .build()

    /**
     * HLS source with chunkless preparation. The extractor is left at its default on purpose:
     * segments are `.jpg`/`.html`/`.js`-named MPEG-TS and `DefaultHlsExtractorFactory` sniffs them
     * correctly (RESOLVER_SPEC §1 — never infer an extension).
     */
    fun hlsSource(
        dataSourceFactory: DataSource.Factory,
        item: MediaItem,
        policy: LoadErrorHandlingPolicy = errorPolicy(),
    ): HlsMediaSource = HlsMediaSource.Factory(dataSourceFactory)
        .setAllowChunklessPreparation(true)
        .setLoadErrorHandlingPolicy(policy)
        .createMediaSource(item)

    fun newPlayer(context: Context, mediaSourceFactory: MediaSourceFactory): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

    /**
     * Builds a player **for a single playback**. The media source is attached here so the caller
     * only has to prepare/seek; nothing is started on the main thread beyond construction.
     */
    fun buildPlayer(
        context: Context,
        masterUrl: String,
        referer: String,
        userAgent: String = DEFAULT_USER_AGENT,
        client: OkHttpClient = playbackClient,
        mediaId: String = masterUrl,
        /** A single MP4/MKV file instead of an HLS playlist. */
        progressive: Boolean = false,
        /** Language to pick inside a multi-audio file; `null` keeps the file's default. */
        preferredAudioLanguage: String? = null,
    ): ExoPlayer {
        // DefaultDataSource routes file:// (a downloaded episode) to the file system and everything
        // else to the Referer-bearing OkHttp factory.
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
            context,
            dataSourceFactory(referer, userAgent, client),
        )
        val policy = errorPolicy()
        val player = newPlayer(
            context,
            DefaultMediaSourceFactory(dataSourceFactory).setLoadErrorHandlingPolicy(policy),
        )
        val item = mediaItem(masterUrl, mediaId)
        player.setMediaSource(
            if (progressive) {
                // Matroska and MP4 extractors are both in the default set; the file sniffs itself.
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(policy)
                    .createMediaSource(item)
            } else {
                hlsSource(dataSourceFactory, item, policy)
            },
        )
        if (preferredAudioLanguage != null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(preferredAudioLanguage)
                .build()
        }
        return player
    }
}

/**
 * Finds the HTTP status behind a [PlaybackException] so 403/410 can trigger a re-resolve
 * (research/RESOLVER_SPEC.md §3). Media3 wraps source errors in up to a few layers.
 */
@OptIn(UnstableApi::class)
internal fun Throwable.httpStatusCode(): Int? {
    var cause: Throwable? = this
    var depth = 0
    while (cause != null && depth < 8) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
        depth++
    }
    return null
}

@OptIn(UnstableApi::class)
internal fun PlaybackException.toResolveException(): ResolveException {
    httpStatusCode()?.let { code ->
        return when (code) {
            403 -> ResolveException.Blocked()
            404 -> ResolveException.NotFound()
            410 -> ResolveException.Dead()
            else -> ResolveException.Parse("HTTP $code")
        }
    }
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> ResolveException.Network(this)

        else -> ResolveException.Parse("player: $errorCodeName")
    }
}
