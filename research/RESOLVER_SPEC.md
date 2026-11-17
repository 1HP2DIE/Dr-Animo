# Animo — Stream Resolver Spec (verified live)

All facts below were reproduced live: **15/15 API-advertised embeds resolved (100%)**, 23 attempts
across 10 series (the 8 failures were *synthesized* `/dub` URLs on sub-only episodes), 4/4 segment
403→206 referer probes. Zero WebView required.

---

## 1. Protocol — exact hops

```
┌─ HOP 1 ──────────────────────────────────────────────────────────────────
│ GET https://megaplay.buzz/stream/s-2/{embedId}/{sub|dub}
│ Headers: Referer: <any allowlisted domain>          ← MANDATORY
│          User-Agent: <desktop or mobile Chrome>
│ → HTML, contains:
│   <div id="megaplay-player" data-id="{fileId}" data-realid="{embedId}"
│        data-mediaid="{mediaId}" data-fileversion="0">
│   fileId != embedId. dub and sub share mediaId but have different fileId.
└──────────────────────────────────────────────────────────────────────────
┌─ HOP 2 ──────────────────────────────────────────────────────────────────
│ GET https://megaplay.buzz/stream/getSources?id={fileId}&platform=OTHER&st=other&_={epochSec}
│ Headers: X-Requested-With: XMLHttpRequest            ← MANDATORY (403 otherwise)
│          Referer: <same allowlisted domain>
│ → {"tracks":[...], "t":1, "intro":{start,end}, "outro":{start,end},
│    "server":4, "image":"…", "enc":"<base64url>"}
└──────────────────────────────────────────────────────────────────────────
┌─ HOP 3 ──────────────────────────────────────────────────────────────────
│ AES-256-CBC decrypt(enc)
│   key      = "i?LMTAx0Q6,:}50U"  → 32 bytes, zero-padded
│   iv       = "W0;27ToaUpl_P%'c"  → 16 bytes
│   padding  = PKCS#7
│   encoding = base64url ('-'→'+', '_'→'/', pad '='), then standard Base64 decode
│ → {"file":"https://fetch.<rotating-domain>/anime/{h1}/{h2}/master.m3u8"}
└──────────────────────────────────────────────────────────────────────────
┌─ HOP 4 ──────────────────────────────────────────────────────────────────
│ GET {file}  (master / multivariant playlist)
│ Headers: Referer: https://megaplay.buzz/            ← MANDATORY (403 Cloudflare otherwise)
│ → #EXTM3U + #EXT-X-STREAM-INF:BANDWIDTH=…,RESOLUTION=…,NAME="1080p|720p|360p"
│   (2–3 variants observed: 5500000/1920x1080, 2800000/1280x720, 800000/640x360)
└──────────────────────────────────────────────────────────────────────────
┌─ HOP 5 ──────────────────────────────────────────────────────────────────
│ GET {variant}.m3u8  +  every  GET {segment}
│ Headers: SAME Referer on BOTH                             ← MANDATORY
│ variant: 358 segments, #EXT-X-TARGETDURATION:4, #EXT-X-ENDLIST, no #EXT-X-KEY (0/15 encrypted)
│ segments: content-type image/jpeg, filename *.jpg, but REAL MPEG-TS (starts 0x47 … "FFmpeg")
└──────────────────────────────────────────────────────────────────────────
```

### MEASURED 4-way header matrix (live, `token_test.mjs matrix`)

All CDN probes used `Referer: https://megaplay.buzz/` — the only referer the CDN accepts (see §1 rule 4).

| Request | (a) bare | (b) `?token=` only | (c) token + Referer | (d) Referer only |
|---|---|---|---|---|
| master.m3u8 | **403** CF challenge | **403** CF challenge | 200 `application/vnd.apple.mpegurl` (255 B) | **200** (255 B) |
| variant `index-f1.m3u8` | **403** | **403** | 200 (50,636 B, 358 segments) | **200** |
| segment `seg-f1-00000.jpg` | **403** | **403** | **206** (2 KB range, `image/jpeg`) | **206** |

**Verdict: `Referer` is the only gate. The `?token=` signature is NOT required and does not work on
its own** (403 without Referer). Do not port `signUrl()` into the Android app — it buys nothing.
One `OkHttpDataSource.Factory` with `Referer`/`Origin` covers playlist, variant, and segments.

### TTL probe (live, `ttl/probe.mjs`)

| Age | master | variant | segment | bare (no Referer) |
|---|---|---|---|---|
| +0 s | 200 (255 B) | 200 (724 lines) | 206 `image/jpeg` | 403 |
| +2 min | 200 | 200 | 206 | — |
| +5 min | 200 | 200 | 206 | **403** |

Manifest stayed valid for the whole 5-minute window with no re-resolve (URL carries no token).
Cache 15 min, re-resolve on any 403.

### NEW: segment file extensions rotate per segment

The variant playlist for one episode lists:
```
seg-f1-00000.jpg     seg-f1-00001.html    seg-f1-00002.js     seg-f1-00003.css
```
Different extensions, same directory, all real MPEG-TS (`47 … FFmpeg Service01`).
→ never derive a segment name/extension pattern, never filter by extension, always consume the
playlist verbatim, and never trust `Content-Type` (it lies: `image/jpeg`).

### Other verified status codes

| Request | Result |
|---|---|
| embed page, no Referer | 200 + **soft-410 page** ("removed due a copyright violation") |
| getSources, no `X-Requested-With` | 403 `{"error":"Forbidden","message":"This endpoint accepts only AJAX requests."}` |
| getSources, missing `id` param | 404 `{"error":"Bad Request","message":"The \"id\" parameter is required."}` |
| dub URL on sub-only episode | 404 page ("doesn't exist or has been moved") |

### Hard rules learned

1. **The 410 page is not a deletion signal.** It means "no allowlisted Referer". Always retry with a
   Referer before marking an episode dead.
2. **404 page is distinct** (3336 B, "doesn't exist or has been moved") — a synthesized dub URL on a
   sub-only episode. Never invent `/dub`; only use `embed_url.dub` when the API provides it.
3. **`/hsub` does not exist** (404). `embed_url` only ever contains `sub` and `dub`.
4. **Any of ~34 allowlisted domains works as the *embed page* Referer**, including `megaplay.buzz`
   itself. It is a presence/allowlist check, not a specific-host match. `https://anikototv.bz/`
   worked 15/15 there.
   **BUT the stream CDN is stricter** (device-verified 2026-11-17): `fetch.*` accepts
   **only `Referer: https://megaplay.buzz/`** — `anikototv.bz` and `anikoto.cz` return **403**.
   So: page referer (any) for hops 1–2, `https://megaplay.buzz/` for hops 4–5. Never reuse the page
   referer for playback.

   | Referer sent to `fetch.*master.m3u8` | Result |
   |---|---|
   | `https://anikototv.bz/` | **403** |
   | `https://anikoto.cz/` | **403** |
   | `https://megaplay.buzz/` | **200** |
5. **Playlist host ≠ segment host.** Playlists: `fetch.nexabloom.top`. Segments: rotating
   `*.wintergrove.space`, `*.phantomharbor.space`, `*.twilightharbor.space`, `*.goldenanchor.space`.
   → headers must be applied by a **single shared data-source factory**, never per-URL.
6. **Segments are `.jpg`-named MPEG-TS.** Content-sniff; do not trust the extension. ExoPlayer's
   `DefaultHlsExtractorFactory` sniffs TS correctly.
7. **No token in the master URL** (plain path). Referer is the only gate. TTL probe running to
   confirm how long a manifest stays valid without re-resolving.

---

## 2. Kotlin implementation (port-ready)

```kotlin
package com.animo.app.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SourcePayload(val file: String)

@Serializable
data class SourcesResponse(
    val enc: String? = null,
    val tracks: List<Track> = emptyList(),
    val intro: Marker? = null,
    val outro: Marker? = null,
    val image: String? = null,
)
@Serializable data class Track(val file: String, val kind: String? = null)
@Serializable data class Marker(val start: Long = 0, val end: Long = 0)

sealed class ResolveError(message: String) : Exception(message) {
    object Dead : ResolveError("file gone (410)")
    object NotFound : ResolveError("episode/series not found (404)")
    object Blocked : ResolveError("blocked by CDN (403)")
    object Parse : ResolveError("unexpected page/source shape")
    class Network(cause: Throwable) : ResolveError("network: ${cause.message}")
}

data class PlaybackSource(
    val masterUrl: String,
    val referer: String,          // "https://megaplay.buzz/"
    val userAgent: String,
    val resolutions: List<String> = emptyList(),
    val tracks: List<Track> = emptyList(),
)

class StreamResolver(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    companion object {
        private const val AES_KEY = "i?LMTAx0Q6,:}50U"
        private const val IV = "W0;27ToaUpl_P%'c"
        private const val PLAYER_REFERER = "https://megaplay.buzz/"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        // Refreshed from /domains; first entry works for the page hop.
        val FALLBACK_REFERERS = listOf(
            "https://anikototv.bz/", "https://anikototv.to/", "https://anikoto.cz/",
            "https://megaplay.buzz/",
        )
        private val FILE_ID = Regex("""data-id=["'](\d+)["']""")
        private val PLAYER_DIV = Regex("""id=["']megaplay-player["']""")
    }

    suspend fun resolve(embedUrl: String): PlaybackSource = withContext(Dispatchers.IO) {
        var last: ResolveError = ResolveError.Parse
        for (ref in FALLBACK_REFERERS) {
            try {
                return@withContext attempt(embedUrl, ref)
            } catch (e: ResolveError) {
                last = e
                if (e is ResolveError.NotFound) throw e   // 404 is definitive, don't retry
            } catch (e: Exception) { last = ResolveError.Network(e) }
        }
        throw last
    }

    private fun attempt(embedUrl: String, referer: String): PlaybackSource {
        val page = get(embedUrl, referer) ?: throw ResolveError.Network(IllegalStateException("page null"))
        val html = page.body
        if (html.contains("Error Code: 404")) throw ResolveError.NotFound
        if (!PLAYER_DIV.containsMatchIn(html) || html.contains("Error Code: 410")) throw ResolveError.Dead
        val fileId = FILE_ID.find(html)?.groupValues?.get(1) ?: throw ResolveError.Parse

        val srcUrl = "https://megaplay.buzz/stream/getSources?id=$fileId" +
            "&platform=OTHER&st=other&_=${System.currentTimeMillis() / 1000}"
        val src = get(srcUrl, referer, xhr = true)
            ?: throw ResolveError.Network(IllegalStateException("sources null"))
        if (src.status == 403) throw ResolveError.Blocked
        if (!src.status.isSuccess()) throw ResolveError.Parse

        val parsed = json.decodeFromString<SourcesResponse>(src.body)
        val enc = parsed.enc ?: throw ResolveError.Parse
        val payload = json.decodeFromString<SourcePayload>(decryptAes(enc))
        if (payload.file.isBlank()) throw ResolveError.Parse

        return PlaybackSource(payload.file, PLAYER_REFERER, UA, tracks = parsed.tracks)
    }

    // ---- AES-256-CBC / PKCS7 ----
    fun decryptAes(b64url: String): String {
        val key = ByteArray(32).also { AES_KEY.toByteArray(Charsets.UTF_8).copyInto(it) }
        val iv = IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        val raw = b64url.replace('-', '+').replace('_', '/').let {
            it + "=".repeat((4 - it.length % 4) % 4)
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")   // PKCS5 == PKCS7 here
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), iv)
        val out = cipher.doFinal(Base64.getDecoder().decode(raw))
        return String(out, Charsets.UTF_8).trim('\u0000')
    }

    private fun get(url: String, referer: String, xhr: Boolean = false): Resp? {
        val rb = Request.Builder().url(url)
            .header("Referer", referer)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
        if (xhr) rb.header("X-Requested-With", "XMLHttpRequest")
        client.newCall(rb.build()).execute().use {
            return Resp(it.code, it.body?.string().orEmpty())
        }
    }

    private data class Resp(val status: Int, val body: String)
    private fun Int.isSuccess() = this in 200..299
}
```

Dependencies already in the stack: OkHttp 4.12.0, kotlinx-serialization-json 1.7.3. `javax.crypto`,
`java.util.Base64` are platform.

---

## 3. Media3 wiring (the part that must not be gotten wrong)

```kotlin
@OptIn(UnstableApi::class)
fun buildPlayer(source: PlaybackSource, client: OkHttpClient): ExoPlayer {
    val ds = OkHttpDataSource.Factory(client)
        .setUserAgent(source.userAgent)
        .setDefaultRequestProperties(
            mapOf("Referer" to source.referer, "Origin" to source.referer.trimEnd('/'), "Accept" to "*/*")
        )
    val mediaSource = HlsMediaSource.Factory(ds)
        .setAllowChunklessPreparation(true)
        .createMediaSource(MediaItem.fromUri(source.masterUrl))

    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMsForStreaming(15_000, 60_000, 2_000, 5_000)
                .build()
        )
        .build()
        .apply { setMediaSource(mediaSource); prepare() }
}
```

- `setDefaultRequestProperties` applies to **the multivariant playlist, the media playlist, and every
  segment** — this is why one factory covers hop 4 and hop 5.
- **One factory per playback.** The factory is mutable and inherited by already-created data sources;
  a shared singleton leaks host A's Referer onto host B → 403 and races between playbacks.
- `.jpg` segments: `DefaultHlsExtractorFactory` sniffs MPEG-TS correctly. Do not add an extension
  based extractor override.
- Re-resolve trigger: `HttpDataSource.InvalidResponseCodeException.responseCode` ∈ {403, 410} →
  invalidate cache, `resolver.resolve(embedUrl)` again, swap `MediaItem` at `player.currentPosition`.
- Track selection: variants expose `NAME`/`RESOLUTION`; read `player.currentTracks` for a quality menu.

---

## 4. Error taxonomy

| Code | Detected by | App behaviour |
|---|---|---|
| `ok` | player div + enc decrypted | play |
| `dead` (soft-410) | HTML contains `Error Code: 410` **after** referer retry | mark episode unavailable, next mirror |
| `not_found` (404) | HTML contains `Error Code: 404` / API `code:not_found` | hide episode/dub option |
| `blocked` (403) | getSources/master/variant/segment 403 | re-resolve once, then surface "source blocked" |
| `parse` | no player div / no `enc` / empty `file` | try next provider |
| `network` | IOException | retry with backoff |

---

## 5. Caching

| Item | TTL | Invalidate when |
|---|---|---|
| master URL + referer | 15 min | any 403/410 during playback |
| variant playlists | 5 min | 403 |
| resolve result per episode | 15 min (memory) | episode changes / manual retry |
| `/domains` allowlist | 6 h | all referers fail |

Master URL carries no token, so a cached URL keeps working while the CDN host is alive; the only
real expiry risk is domain rotation. Re-resolve is cheap (2 requests + AES).

---

## 6. Fragility ranking

1. **Domain rotation** — megaplay domain, the 34 referer allowlist entries, and random CDN hosts
   rotate. Treat all of them as data (refresh from `/domains`), never as constants.
2. **Referer on every hop** — including each segment; one shared factory only.
3. **Dub availability** — API omits `dub` for most episodes; never synthesize.
4. **`.jpg`-named TS segments** — content-sniff, never trust the extension.
5. **Soft-410 misread as dead** — always retry with referer before marking unavailable.
