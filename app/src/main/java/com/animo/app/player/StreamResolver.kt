package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream

/**
 * Turns a megaplay embed URL into a playable HLS manifest.
 * Protocol: research/RESOLVER_SPEC.md (AES-256-CBC, Referer required, X-Requested-With on getSources).
 */
interface StreamResolver {
    suspend fun resolve(embedUrl: String, audio: AudioTrack): Result<PlayableStream>
}

sealed class ResolveException(message: String) : Exception(message) {
    class Dead(message: String = "episode gone (410)") : ResolveException(message)
    class NotFound(message: String = "not found (404)") : ResolveException(message)
    class Blocked(message: String = "blocked (403)") : ResolveException(message)
    class Parse(message: String) : ResolveException(message)
    class Network(cause: Throwable) : ResolveException("network: ${cause.message}")

    /**
     * The link itself is a **rotating token** that has aged out, and the origin now refuses it.
     *
     * Measured on movielinkbd: a `/p/<token>` captured days earlier answers `400 bad config`, while a
     * token read from the same title page seconds ago answers `200`. That is not a network failure and
     * not a "the episode is gone" failure — the correct response is to re-read a fresh token from the
     * page that issues them and replay the request, which is why this is its own type instead of
     * [Parse] or [Network].
     */
    class Stale(message: String = "stale link token (400)") : ResolveException(message)
}
