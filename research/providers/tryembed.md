# TryEmbed — service, API and play chain (verified 2026-11-17)

`https://tryembed.us.cc/` is an **anime-only** embed/resolver service ("TryEmbed Premium Engine
v5.0") on Express behind Cloudflare, with an nginx origin in front of Express (`/.env` → 548-byte
nginx 404, app routes → 2,732-byte Express 404). Its own homepage points movies/TV elsewhere
("For Movies or TV Shows → VidPlays.fun"), so it has **no TMDB/IMDB path at all**.

## 1. Identity model

```
/embed/anime/{anilistId}/{episode}/{sub|dub}
/embed/anime/mal/{malId}/{episode}/{sub|dub}
```

`{audio}` is optional (`/embed/anime/172463/1` → 200). Ids are **not validated server-side**:
`/embed/anime/abc/1/sub` and `/embed/anime/1/1/sub` both return 200. MAL ids are resolved to AniList
ids server-side — the shell payload carries
`{"type":"anime","anilist_id":"151807","episode":1,"audio":"sub","id_type":"mal","mal_id":"52299"}`.

Route probe results: no `/movie/*`, `/tv/*`, `/api/<id>`, `/e/*`, `/v/*`, `/docs`, `/health`,
`/openapi.json`, `/sitemap.xml` (all 404). Query params `?id=`, `?tmdb=`, `?imdb=` are ignored
(identical 39,612-byte homepage).

## 2. The nonce handshake (the gate)

```
1  GET /embed/anime/{id}/{ep}/{audio}                 → 200 HTML (22.5 KB)
     window.BOOTSTRAP_TICKET="<372 chars base64url>"  (≈279 opaque bytes, AES-GCM-shaped)
     window.INITIAL_SHELL_PAYLOAD={…}
     window.TRYEMBED_SERVER_ORDER=["astra","beta","sane","sora"]
     Set-Cookie: tryembed_auth=<64 hex>; HttpOnly; Max-Age=1800
                 tryembed_session_3beafb1b31d103af=<32 b64>; HttpOnly; Max-Age=1200

2  POST /api/bootstrap   X-TryEmbed-Bootstrap: <ticket>  + cookies
     → {providers[…], selectedProvider, intro, outro, nextEpisode, meta, embedNonce:"<24 b64url>"}

3  GET /api/stream_data?id=&episode=&audio=&player=jw&server=astra&nonce=<nonce>
     + X-Embed-Nonce: <nonce>                        → providers[].qualities[].token
4  GET /s/{token}.m3u8                               → 302 signed proxy URL
5  GET https://god.anixx.cloud/proxy/astra?url=…&exp=…&sig=…   → master / variant / segment
```

### Error ladder (each layer isolated)

| Missing / wrong input | Response |
|---|---|
| no `Sec-Fetch-Site` / `Sec-Fetch-Mode` | `403 Forbidden: Invalid Request Signature.` |
| Sec-Fetch present, missing or foreign `Origin` | `403 Forbidden: Unauthorized access endpoint origin.` |
| `Origin: https://tryembed.us.cc` + cookies + ticket | **200** |
| ticket + wrong session cookie | `403 {"error":"Access Denied: Bootstrap binding failed."}` |
| nonce without cookies | `403 {"error":"Access Denied: Player bootstrap is incomplete."}` |
| nonce + a **different session's** cookies | same → the nonce is **session-bound**, not IP/UA-bound |
| nonce minted for id/ep 1 reused with `episode=2` | same → **tuple-bound to (session, id, episode)** |
| nonce with `audio=dub` when minted for `sub` | **200** → audio is *not* bound |
| nonce aged **+70 s** | `403 Invalid or expired nonce.` → TTL < 70 s (client refreshes every 35 s) |
| nonce replayed ~1 s later, same tuple | 200 (reusable within TTL, not single-use) |

`/api/meta?id=&episode=` is unauthenticated but useless: 21–32 s and a stub fallback
(`title: "Anime Stream - E1"`).

The page also contains a **client-side-only** soft-410 trap (`if(window.self===window.top)` →
replace the document with an atob'd "removed due a copyright violation, Error Code: 410" page). A
plain HTTP client is unaffected and still receives the ticket.

## 3. Play chain — measured hops

| # | Request | Status | Bytes | Notes |
|---|---|---|---|---|
| 1 | `GET /embed/anime/172463/1/sub` | 200 | 22,535 | ticket + cookies |
| 2 | `POST /api/bootstrap` | 200 | 3,316 | probabilistic (~50 %), retry needed |
| 3 | `GET /api/stream_data?…&nonce=` | 200 | 3,318 | nonce in **both** query and header |
| 4 | `GET /s/{559-char token}.m3u8` | **302** | 247 | needs cookie + `Origin`; **do not** add `Referer` |
| 5 | `GET god.anixx.cloud/proxy/astra?url=…master.m3u8&exp&sig` | 200 | 886 | zero headers needed |
| 6 | `…/720/index.m3u8` | 200 | 44,772 | 179 segments |
| 7 | `…/720/seg_00000.ts.jpg` | 200 / **206** | 590,884 | `video/mp2t`, `Range` honoured |

The token→URL step is synthesised client-side in `player.js` (231,648 B):
`` `${window.HOST}/s/${fallbackToken || token}.${type === "mp4" ? "mp4" : "m3u8"}` `` — no server call.

### `/s/` header matrix

| Headers | Result |
|---|---|
| bare / UA only / UA+AL | 403 `Unauthorized access endpoint origin.` |
| UA + AL + `Referer: https://tryembed.us.cc/` | **302** |
| UA + AL + `Origin` | 500 `Stream Resolution Error` / 403 `Token Validation Failed or IP Mismatch.` |
| + `X-Embed-Nonce` | **500 — never send the nonce here** |
| + `Range` | 403 (token validation runs before range) |
| cookie + UA + AL (no Referer) | 403 |

⚠️ Two probes disagree on the winning set (one got 302 with `Origin`, the other with `Referer`) and
identical replays flip between 302 and 403 — that is **per-token rate limiting** (`429 Stream request
limit exceeded.`), not header variance. One `/s/` call per playback is the design point.

### CDN proxy `god.anixx.cloud` / `sora.anixx.cloud`

**Zero headers required** (bare GET → 200). The `sig` is a server-side HMAC over the `url` value:
tampered `sig`, tampered `exp`, missing `sig`, or a changed `url` each return
`{"success":false,"error":"…"}`; extra unknown params are ignored. The client never computes it.

## 4. Media characteristics

- Master: `#EXT-X-VERSION:4`, 3 variants — 360p 900 kbps · 720p 3 Mbps · 1080p 5.3 Mbps.
- Segments: **MPEG-TS**, `content-type: video/mp2t`, first bytes `47 40 11 10` ("FFmpeg"), named
  `seg_00000.ts.jpg` (extension lies — same trap as megaplay).
- **No encryption**: zero `#EXT-X-KEY`, no `#EXT-X-MAP`, no fMP4, no discontinuities.
- Single muxed audio (no `#EXT-X-MEDIA`); subtitles are a separate proxied **WebVTT**
  (`captions[0].url`, 19,855 B, open).
- Playlist origin `hls.dramahot.top` (403 direct) and segment origin `hls1.drama1.cfd` are **different
  hosts**; both are only reachable through the proxy, which rotates between `god.` and `sora.`
  per 302 — never hardcode one.

## 5. TTL / limits

| Item | Value |
|---|---|
| proxy `exp` | **≈236 min** (~4 h) from issue |
| `tryembed_session_*` cookie | 20 min |
| `tryembed_auth` cookie | 30 min |
| bootstrap nonce | < 70 s, refreshed every 35 s |
| embed page | `no-store` |
| rate limiting | none observed on `/embed` (12/12 at ~0.4 s), but `/s/` is per-token limited |

## 6. Verdict

- **Not iframe-only**: hops 3–7 are portable to OkHttp, and hop 5–7 need *no* headers at all.
- **Hop 2 is the blocker**: bootstrap needs a 4-header client fingerprint (`Origin` +
  `Sec-Fetch-Site/Mode`), a cookie jar, a ticket↔session binding, and still succeeds only ~50 % of the
  time. Two agents reproduced it inconsistently; it may be TLS/JA3-bound.
- **Test before investing**: `curl_cffi --impersonate chrome` on hop 2. If that 200s, the gate is
  TLS-fingerprint and OkHttp can never pass it → the only clean design is a tiny server-side helper
  for hop 2, with hops 4–7 done natively in the app.
- **Role in Animo**: secondary anime source (AniList id → stream). Not a catalogue, not a title
  lookup, and no support for movies/TV. Hindi is not advertised anywhere (the `dub` path is unverified)
  → it does not replace toono/rareanimes for the Hindi track.
