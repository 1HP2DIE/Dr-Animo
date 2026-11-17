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
}
