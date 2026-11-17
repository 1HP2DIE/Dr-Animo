# VidNest — play chain (verified 2026-11-17)

The player is **not** scraped: `/movie/{id}` and `/tv/{id}/{s}/{e}` return a static shell
(`<div class="LoadingState…">FETCHING, ONE MOMENT...</div>` + RSC flight data) with **zero**
`m3u8` / `mp4` / `<video>` / `jwplayer` / `hls.js` literals. Everything happens against the Go
backend, so the whole chain is JSON.

## 1. The chain

```
HOP 1  GET https://new.vidnest.fun/{server}/movie/{tmdbId}?_n={16 random bytes, base64url}
       headers: UA + Accept-Language        (no Referer, no cookie, no nonce from HTML)
    →  {"v":2,"d":"<base64url>","n":"<response salt>"}

HOP 2  AES-256-GCM decrypt
       key = SHA-256( "<n>|" + "K3p8vQ2mX9rL5wZ0fN7bG1sH4tJ6dC9_y8S3wM1nQ5pL2" )
       raw = base64url(d);  iv = raw[0..12);  ct||tag = raw[12..)
    →  {"streams":[{"url":…,"quality":…,"language":…,"type":"hls|mp4","headers":{…}}]}

HOP 3  GET {url} with the per-stream headers the API itself ships
       (Referer / User-Agent / sometimes a CloudFront cookie triple)
```

`_n` is **16 random bytes base64url, unsigned** — no HMAC, no timestamp, no key.
`research/providers/vidnest_probe.mjs` mints one and decrypts a response end-to-end.

Header matrix (API hop):

| Request to `/{server}/movie/324857` | Result |
|---|---|
| no `_n`, UA + Referer | **403** `{"error":"missing or invalid nonce"}` (37 B), all servers |
| random 22-char base64url `_n`, UA + `Accept-Language`, no Referer, no cookies | **200**, valid envelope |

So the API gate is *presence of a well-formed random nonce* and nothing else.

## 2. Upstream liveness — the live set depends on the title

`movie 324857`: alive = `rogflix, vidzee, ophim, flixhq, hollymoviehd, allmovies` (**6/12**);
502 = `yflix, onehd, rpmvid, nextgencloudfabric, klikxxi, vidlink`.

`tv 94997/1/1`: alive = `yflix, ophim, flixhq, klikxxi, hollymoviehd` (**5/12**).

`/anime/154587/1`: **0/12** — the anime route needs its documented shape
`/anime/{anilistId}/{episode}/{lang}` (a bare id + episode returned nothing usable in this sweep).

⇒ **Failover across all 12 slugs is mandatory**, and a cached "which server works" guess is worthless
per-title. Cache the *resolved stream*, not the working server.

## 3. What a provider actually returns (observed shapes)

| Server | Payload |
|---|---|
| `rogflix` | `https://akcloud.animanga.fun/hls/<b64url>/master.m3u8` → inner `hgplaycdn.com/stream/…/master.m3u8` |
| `vidzee` | direct **MP4/MKV**: `https://s2.streamflixserver.site/movies/2018/spidrmnintspidrwrse.mkv`, headers `Referer: https://s2.streamflixserver.site/` + desktop Chrome UA |
| `ophim` | `https://m5f-cf.money-4.workers.dev/hls/<b64 JSON>` carrying CloudFront signed cookies + `Referer: https://api6.aoner…`, Cronet UA, and an **MPEG-DASH** `index_web.mpd` |
| `flixhq` | `https://ss-cf.money-4.workers.dev/hls/<b64>` → `https://new.vidnest.fun/movy/pl/<b64>/master.m3u8`, **4 variants** (2160/1080/720/480), `Referer: https://www.movy.sx/` |
| `hollymoviehd` | 2 HLS on `goodstream.cc` (`/streamsvr/…`, `/pl/…`) with `?e=<token>`, `Referer: https://goodstream.cc/embed/…` |
| `allmovies` | 3 language variants (**English / Tamil / Telugu**) on `i-arch-400.geffe448mik.com`, path contains `:1794904440:<ip>:<sha256>:` → **signed, expiring** |

Two consequences for the resolver:

1. **`type` is not trustworthy.** A `.mkv` extension, an `.m3u8`, and an `.mpd` all appear from the
   same API — sniff the first bytes / trust `type` before handing it to Media3, and never assume HLS.
2. **Per-stream headers ship with the payload.** The API hands back `headers{Referer, User-Agent}`,
   sometimes a CloudFront cookie triple. The native player must apply them as request headers (Media3
   `OkHttpDataSource`), which replaces the worker-proxy the web player needs.

## 4. Hindi

`allmovies` has an explicit `language == "hindi"` branch in its client-side handler
(`"Hindi stream not available."` when absent), and `ophim` returned `language: "Original Audio"` for a
TV episode. So the `hindi` path segment is a real request, not decoration — **but no live Hindi
playlist has been captured yet**. That is the one experiment worth running before shipping VidNest as
a Hindi source.

## 5. Subtitles are a separate service

Not in the stream payload: `sub.wyzie.io/search?id=<imdb/tmdb>` (API key in the bundle), fallback
`https://sub.vdrk.site/v2/{movie|tv}/{id}[/{season}/{episode}]` → `[{file,label}]`.

## 6. Extra infrastructure worth knowing

8 rotating CORS workers: `https://vidproxy.money-{1..8}.workers.dev/proxy?url=<enc>&headers=<enc JSON>`
and `https://vidrift-cf.money-{1..8}.workers.dev`. The web app proxies HLS through them; a native
client with per-request headers should not need them.

## 7. Verdict

- Portable to Kotlin with plain OkHttp + `javax.crypto` (`AES/GCM/NoPadding`, `MessageDigest("SHA-256")`,
  `java.util.Base64`) — **no WebView, no JS engine**.
- Simpler than the megaplay chain: one JSON hop + one decrypt, versus embed → `getSources` → AES.
- Reliability is the only real risk: per-title, per-server liveness around 50 %, so the implementation
  must try all 12 slugs and cache the resolved stream for ≤15 min (tokens live in the path).
- Not a lookup service: Animo must supply the TMDB / AniList id itself.
