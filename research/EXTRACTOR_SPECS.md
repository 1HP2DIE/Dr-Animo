# Animo — Extractor Specs (source-verified + live-measured)

Two kinds of sources in the app:

1. **megaplay** (Anikoto's embed host) — AES-encrypted JSON, no self-hosting crypto needed beyond AES.
2. **Direct extractors** (vidtube, streamwish, kwik) — plain HTML/JS scraping.

All request recipes below are traced to `research/vendor/animestream/lib/...` with `file:line`.

---

## 0. What the live measurement settled (do not re-litigate)

`Referer` is the **only** gate on the CDN. The signed `?token=` is optional and useless alone.

| Request | bare | `?token=` only | token + Referer | Referer only |
|---|---|---|---|---|
| master | 403 | 403 | 200 | **200** |
| variant | 403 | 403 | 200 | **200** |
| segment | 403 | 403 | 206 | **206** |

→ `megaplayCrypto.signUrl()` is **not needed** in the Android app. Keep only the AES decrypt.

Segment extensions rotate per segment (`00000.jpg`, `00001.html`, `00002.js`, `00003.css`) and the
bytes are always MPEG-TS. Never infer an extension; always use the playlist verbatim.

---

## 1. megaplay (Anikoto) — P0

```
GET {embedUrl}                       Referer: <allowlisted site>       ← else soft-410
   → <div id="megaplay-player" data-id="{fileId}" data-realid="{embedId}" data-mediaid="{mediaId}">
GET https://megaplay.buzz/stream/getSources?id={fileId}&platform=OTHER&st=other&_={sec}
   X-Requested-With: XMLHttpRequest   Referer: <same>
   → {tracks, intro, outro, image, enc?}      // sometimes {sources:{file}} instead of enc
AES-256-CBC(enc) → {"file":"https://fetch.<rot>/anime/{h1}/{h2}/master.m3u8"}
GET file                             Referer: https://megaplay.buzz/
```

Kotlin (compiles on the locked stack):

```kotlin
object MegaplayCrypto {
    private val KEY = "i?LMTAx0Q6,:}50U".toByteArray().copyOf(32)   // zero-padded
    private val IV  = "W0;27ToaUpl_P%'c".toByteArray()               // 16 bytes

    fun decrypt(enc: String): String {
        val b64 = enc.replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        return String(c.doFinal(Base64.getDecoder().decode(b64)), Charsets.UTF_8).trim('\u0000')
    }

    /** Optional: HMAC token. Measured unnecessary — Referer alone returns 200. */
    fun sign(fileUrl: String): String? {
        val ids = Regex("/[a-f0-9]{32}/[a-f0-9]{32}/").find(fileUrl)?.value ?: return null
        val payload = "${System.currentTimeMillis() / 1000 + 90}|${ids.trim('/')}"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec("MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s".toByteArray(), "HmacSHA256"))
        }
        fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        return b64(payload.toByteArray()) + "." + b64(mac.doFinal(payload.toByteArray()))
    }
}
```

Extraction regexes (mind the bug that already bit us):

```kotlin
private val DIV_ID   = Regex("""id=["']megaplay-player["']""")
private val FILE_ID  = Regex("""data-id=["'](\d+)["']""")      // lowercase attr — do NOT reuse an uppercase HLS attr regex
```

Failure modes: soft-410 page (no referer), 404 page (dub on sub-only), 403 getSources (no XHR
header), 404 `{"message":"The \"id\" parameter is required."}` (used embedId instead of fileId).

---

## 2. vidtube — P1 (simplest of the direct extractors: no crypto at all)

```
GET {embed page}
   → #megaplay-player[data-id]
GET https://vidtube.site/stream/getSourcesNew?id={id}&type={last path segment}
   X-Requested-With: XMLHttpRequest
   Referer: https://vidtube.site/     Origin: https://vidtube.site
   → plain JSON, sources.file = playlist
dub  ⇔ type == "dub"
```
Tracks: `kind == "captions"`, prefer English as default.

---

## 3. StreamWish — P2

```
GET {embed url}
  try   file:\s*"(.*?)"
  else  eval(function(p,a,c,k,e,d)… → JsUnpack → sources:\s*\[([\s\S]*?)\]
        also resolve indirect refs: var links = {…};
subtitles: tracks:[…] with label:"English" + kind:"captions"
headers on playback: Referer: {embed url}   Origin: https://{embed host}
```
Failures: `WRONG FORMAT!`, empty `streamLink`.

---

## 4. Kwik (AnimePahe's host) — P2, only useful if we ever port AnimePahe

```
GET {embed}   Referer: https://animepahe.pw/
JsUnpack the eval(function(p,a,c,k,e,d) blob
regex: const source\s*=\s*'([^']+\.m3u8)'
playback header: Referer: https://kwik.cx/
```
Note: AnimePahe is Cloudflare-403 from this network → **do not port yet**.
The vendored `kwik.dart` has a buggy `_replaceStrings` (`substring(strings.length)`); the real
unpacker is the token-substitution loop at `kwik.dart:51-78`.

---

## 5. HLS layer requirements (from `commons/hlsParser`)

| Feature | Needed? | Notes |
|---|---|---|
| Multivariant → variant selection | **yes** | Media3 does it natively |
| `#EXT-X-KEY` AES-128-CBC segments | no (0/15 playlists) | Media3 handles; hex IV or 16-byte big-endian media-sequence IV |
| `#EXT-X-MAP` fMP4 init | no (TS only) | supported by Media3 |
| `#EXT-X-ENDLIST` VOD | yes | present; no live streams observed |
| `.jpg/.html/.js/.css` named TS segments | **yes** | content sniff, ignore extension and Content-Type |

DASH (`manifest.mpd`) only if we add AnimeOnsen → extra dependency `media3-exoplayer-dash`.

---

## 6. Error taxonomy (shared by all extractors)

| Code | Signal | Action |
|---|---|---|
| `ok` | playlist URL obtained | play |
| `dead` | soft-410 page after referer retry | mark episode unavailable |
| `not_found` | 404 page / API `code:not_found` / missing `id` param | hide option, do not retry |
| `blocked` | 403 CF challenge on any hop | re-resolve once → next provider |
| `parse` | no player div / no `enc` / empty `file` / regex miss | next provider |
| `network` | IOException / timeout | exponential backoff, 3 tries |

---

## 7. Provider porting traps (learned the hard way in this project)

1. **`data-id` ≠ `episode_embed_id`** — `data-id` is the getSources key; `data-realid` equals the
   embed id. Reusing the embed id yields 404 "id parameter is required".
2. **Do not reuse an HLS-attribute regex for HTML attributes** — `([A-Z0-9-]+)` is uppercase-only and
   silently misses `data-id`. Use a separate lowercase-tolerant parser.
3. **`getSources` has two flavours** — `getSources` and `getSourcesNew` (Dart falls back). Try both
   before declaring `parse` failure.
4. **Never synthesize `/dub`** — only play dub when the API actually returns `embed_url.dub`.
5. **Segment host ≠ playlist host, every time** — one shared data-source factory with default headers.
6. **`thumbnail` is null** for every Anikoto episode; use `poster` or the HLS `image` field.
