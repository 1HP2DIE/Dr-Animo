package com.animo.app.domain.model

/**
 * One playable backend for the current episode.
 *
 * The app has more than one way to obtain a stream, and they are not interchangeable: a server can
 * be down, or carry a different set of languages. Presenting them as numbered servers keeps the
 * player honest — the user picks a server when they want a language, and playback falls through to
 * the next one when a server fails.
 */
enum class StreamServer(val number: Int) {
    /** The embed chain the catalogue itself links to. */
    PRIMARY(1),

    /** The multi-language alternative, keyed by AniList id. */
    ALTERNATIVE(2);

    val label: String get() = "Server $number"

    companion object {
        fun fromNumber(number: Int): StreamServer? = entries.firstOrNull { it.number == number }
    }
}

/**
 * One selectable language on one server.
 *
 * [id] is what the server wants (`sub`/`dub` for the primary, an ISO code such as `hi` for the
 * alternative). [audio] is only used by the primary to pick which embed URL to resolve; the
 * alternative encodes the language in the request itself.
 */
data class ServerLanguage(
    val id: String,
    val label: String,
    val audio: AudioTrack,
    val languageCode: String? = null,
)

/** A server plus the languages it actually offers for this episode. */
data class ServerOption(
    val server: StreamServer,
    val languages: List<ServerLanguage> = emptyList(),
    /** True once a probe has answered; an unprobed server is shown as "checking". */
    val probed: Boolean = false,
    val reachable: Boolean = true,
) {
    val isUsable: Boolean get() = languages.isNotEmpty()
}
