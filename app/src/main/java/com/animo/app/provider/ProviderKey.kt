package com.animo.app.provider

/**
 * The runtime slots the app can ask the vault for.
 *
 * The names describe a *role*, never a provider: reading this enum tells you the app has one
 * catalogue API, one stream host, two dub catalogues and three subtitle sources, and nothing about
 * who they are. The mapping lives only inside the encrypted `assets/pv.bin`.
 */
enum class ProviderKey(val slot: String) {
    CATALOG_API("catalog_api"),

    STREAM_HOST("stream_host"),
    STREAM_REFERER("stream_referer"),
    STREAM_DOMAINS("stream_domains"),
    /** Comma-separated allowlist used when the rotating endpoint has not answered yet. */
    STREAM_REFERERS("stream_referers"),
    /** HTML id of the provider's player element, needed to parse the embed page. */
    PLAYER_MARKER("player_marker"),
    EMBED_AES_KEY("embed_aes_key"),
    EMBED_AES_IV("embed_aes_iv"),

    DUB_HOST_A("dub_host_a"),
    DUB_HOST_B("dub_host_b"),
    DUB_HOST_B_REFERER("dub_host_b_referer"),
    DUB_CDN("dub_cdn"),
    /** Dead-player mirror host, used only to recognise and rewrite links. */
    DUB_GATE_HOST("dub_gate_host"),
    DUB_GATE_PATH("dub_gate_path"),
    /** Substring that identifies the wrapper page inside an iframe src. */
    DUB_EMBED_MARKER("dub_embed_marker"),
    /** Page label that marks the preferred player link. */
    DUB_WATCH_MARKER("dub_watch_marker"),
    /** Slug prefixes already written into the catalogue; kept stable so a resync does not duplicate. */
    DUB_SLUG_PREFIX_A("dub_slug_prefix_a"),
    DUB_SLUG_PREFIX_B("dub_slug_prefix_b"),

    /** Alternative stream source: rotating API endpoints, its public origin, and the CDN headers. */
    STREAM_SOURCE_B_SHARDS("source_b_shards"),
    STREAM_SOURCE_B_ORIGIN("source_b_origin"),
    STREAM_SOURCE_B_REFERER("source_b_referer"),
    STREAM_SOURCE_B_USER_AGENT("source_b_user_agent"),

    /** File-mirror source: its JSON API (AniList-keyed) and the player host behind it. */
    MIRROR_API("mirror_api"),
    MIRROR_PLAYER("mirror_player"),

    /** Direct-file archive source: one site, title search + per-quality files. */
    ARCHIVE_HOST("archive_host"),

    /** Second catalogue source: its index pages, the referer its watch page expects, and its UA. */
    MOVIELINK_INDEX_HOST("movielink_index_host"),
    MOVIELINK_WATCH_REFERER("movielink_watch_referer"),
    /** Optional; an empty slot falls back to the shared desktop UA in `HttpClientFactory`. */
    MOVIELINK_AVATAR_UA("movielink_ua"),

    /** Community opening/ending timestamps, for sources that publish no markers. */
    SKIP_TIMES_API("skip_api"),

    SUBTITLE_HOST_A("sub_a"),
    SUBTITLE_HOST_B("sub_b"),
    SUBTITLE_HOST_B_CDN("sub_b_cdn"),
    SUBTITLE_HOST_C("sub_c"),
}
