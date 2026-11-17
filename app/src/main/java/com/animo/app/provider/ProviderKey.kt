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

    SUBTITLE_HOST_A("sub_a"),
    SUBTITLE_HOST_B("sub_b"),
    SUBTITLE_HOST_B_CDN("sub_b_cdn"),
    SUBTITLE_HOST_C("sub_c"),
}
