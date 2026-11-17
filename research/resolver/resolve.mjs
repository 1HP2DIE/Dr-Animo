#!/usr/bin/env node
/**
 * Animo -- native stream resolver (no WebView needed)
 * ===================================================
 * Reverse-engineered pipeline:
 *
 *   1. anikotoapi.site /series/{id}            -> episodes[].episode_embed_id + embed_url{sub,dub}
 *   2. megaplay.buzz /stream/s-2/{embedId}/{lang}  (+ Referer: anikototv domain)
 *         -> HTML with <div id="megaplay-player" data-id="FILEID" data-realid="EMBEDID" ...>
 *   3. megaplay.buzz /stream/getSources?id=FILEID&platform=OTHER&st=other&_=EPOCH
 *         (+ Referer + X-Requested-With: XMLHttpRequest)
 *         -> JSON { tracks[], t, intro, outro, server, image, enc }
 *   4. enc = base64url( AES-256-CBC( {"file":"https://fetch.<domain>/anime/<h1>/<h2>/master.m3u8"} ) )
 *         key = "i?LMTAx0Q6,:}50U"   -> utf8 bytes, zero padded to 32
 *         iv  = "W0;27ToaUpl_P%'c"   -> exactly 16 bytes
 *         PKCS#7 padding
 *   5. master.m3u8 + variants + segments require Referer: https://megaplay.buzz/
 *
 * Usage:
 *   node resolve.mjs <embedUrl> [--playlist] [--json] [--quiet]
 *   node resolve.mjs --series 9064 [--all] [--playlist]
 *   node resolve.mjs --file 180179 [--lang sub]
 *   node resolve.mjs --domains
 *
 * Exports resolveEmbed()/resolveFromSeries() for batch.mjs.
 */
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

export const RESOLVER_VERSION = '1.0.0';

export const UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36';

export const MEGA = 'https://megaplay.buzz';
export const API = 'https://anikotoapi.site';

/* --- crypto constants (verified against live traffic) --- */
export const AES_KEY_STR = 'i?LMTAx0Q6,:}50U';
export const AES_IV_STR = "W0;27ToaUpl_P%'c";

/* Referers that the megaplay /stream page accepts. First one that renders wins. */
export const PAGE_REFERERS = [
  'https://anikototv.bz/',
  'https://megaplay.buzz/',
  'https://anikototv.to/',
  'https://anikoto.cz/'
];

/* Referer required by the fetch.* CDN for master.m3u8 / variants / segments */
export const CDN_REFERER = 'https://megaplay.buzz/';

/* Rate limits: anikotoapi.site = 60 req / 120 s per IP. Stay well under. */
export const API_MIN_GAP_MS = 1200;
export const NET_MIN_GAP_MS = 300;

/* ------------------------------------------------------------------ */
/* throttling                                                          */
/* ------------------------------------------------------------------ */
const lastByHost = new Map();

export const sleep = function (ms) {
  return new Promise(function (r) { setTimeout(r, ms); });
};

export async function throttle(url, gapMs) {
  let host = 'unknown';
  try { host = new URL(url).host; } catch (e) { /* keep default */ }
  const last = lastByHost.get(host) || 0;
  const wait = last + gapMs - Date.now();
  if (wait > 0) await sleep(wait);
  lastByHost.set(host, Date.now());
}

/* ------------------------------------------------------------------ */
/* crypto                                                              */
/* ------------------------------------------------------------------ */

/** base64url -> Buffer (tolerates missing '=' padding and std base64) */
export function b64urlDecode(str) {
  let t = String(str).replace(/-/g, '+').replace(/_/g, '/').replace(/[^A-Za-z0-9+/=]/g, '');
  while (t.length % 4 !== 0) t += '=';
  return Buffer.from(t, 'base64');
}

/** The 16-char ASCII key is copied to offset 0 of a 32-byte zero-filled buffer. */
export function aesKey() {
  const k = Buffer.alloc(32, 0x00);
  Buffer.from(AES_KEY_STR, 'utf8').copy(k, 0);
  return k;
}

export function aesIv() {
  const iv = Buffer.from(AES_IV_STR, 'utf8');
  if (iv.length !== 16) throw new Error('AES iv must be 16 bytes, got ' + iv.length);
  return iv;
}

/** Manual PKCS#7 strip, done explicitly so the Kotlin port is 1:1. */
export function pkcs7Strip(buf) {
  if (!buf || buf.length === 0) throw new Error('empty ciphertext plaintext');
  const pad = buf[buf.length - 1];
  if (pad < 1 || pad > 16 || pad > buf.length) throw new Error('bad pkcs7 pad byte ' + pad);
  for (let i = buf.length - pad; i < buf.length; i++) {
    if (buf[i] !== pad) throw new Error('bad pkcs7 padding at ' + i);
  }
  return buf.subarray(0, buf.length - pad);
}

/** Decrypt a megaplay 'enc' blob -> plaintext utf8 (usually a JSON object). */
export function decryptEnc(enc) {
  const raw = b64urlDecode(enc);
  if (raw.length === 0 || raw.length % 16 !== 0) {
    throw new Error('ciphertext not a multiple of 16 (len=' + raw.length + ')');
  }
  const d = crypto.createDecipheriv('aes-256-cbc', aesKey(), aesIv());
  d.setAutoPadding(false);
  const padded = Buffer.concat([d.update(raw), d.final()]);
  return pkcs7Strip(padded).toString('utf8');
}

/** Same as decryptEnc but tolerates payloads that are bare strings. */
export function resolveEncPayload(enc) {
  const plain = decryptEnc(enc);
  const trimmed = plain.trim();
  if (trimmed.startsWith('{')) {
    try { return { json: JSON.parse(trimmed), raw: plain }; } catch (e) { /* fallthrough */ }
  }
  return { json: null, raw: plain };
}

/* ------------------------------------------------------------------ */
/* http                                                                */
/* ------------------------------------------------------------------ */

export async function httpGet(url, opts) {
  opts = opts || {};
  const gap = url.indexOf('anikotoapi.site') !== -1 ? API_MIN_GAP_MS : NET_MIN_GAP_MS;
  await throttle(url, opts.gapMs || gap);
  const headers = Object.assign({
    'User-Agent': UA,
    'Accept': '*/*',
    'Accept-Language': 'en-US,en;q=0.9'
  }, opts.headers || {});
  const t0 = Date.now();
  const res = await fetch(url, {
    headers: headers,
    redirect: 'follow',
    signal: AbortSignal.timeout(opts.timeoutMs || 25000)
  });
  const buf = Buffer.from(await res.arrayBuffer());
  return {
    status: res.status,
    ok: res.ok,
    headers: Object.fromEntries(res.headers.entries()),
    body: buf,
    text: buf.toString('utf8'),
    finalUrl: res.url,
    ms: Date.now() - t0,
    requestedHeaders: headers
  };
}

/* ------------------------------------------------------------------ */
/* HTML / JSON parsers                                                 */
/* ------------------------------------------------------------------ */

/** Returns 'gone_410' | 'not_found_404' | 'error_XXX' | null */
export function htmlToText(html) {
  return String(html)
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * The error page is served with HTTP 200. Real codes live in the text as
 * 'Error Code: <b>410</b>' -- strip markup before matching.
 */
export function detectPageError(html) {
  const txt = htmlToText(html);
  const m = txt.match(/Error Code:\s*(\d{3})/i) ||
            String(html).match(/Error Code:[\s\S]{0,40}?(\d{3})/i);
  if (m) {
    if (m[1] === '410') return 'gone_410';
    if (m[1] === '404') return 'not_found_404';
    return 'error_' + m[1];
  }
  if (/can't find the file you are looking for/i.test(txt)) return 'gone_410';
  if (/doesn't exist or has been moved/i.test(txt)) return 'not_found_404';
  if (/copyright violation/i.test(txt)) return 'gone_410';
  return null;
}

export function isSoft410(html) {
  if (detectPageError(html) === 'gone_410') return true;
  return /removed due a?\s*copyright violation/i.test(html);
}

export function parsePlayerDiv(html) {
  const m = html.match(/<div[^>]*id=["']megaplay-player["'][^>]*>/i);
  if (!m) return null;
  const attrs = {};
  const re = /([A-Za-z0-9_:-]+)\s*=\s*["']([^"']*)["']/g;
  let x;
  while ((x = re.exec(m[0])) !== null) attrs[x[1].toLowerCase()] = x[2];
  return {
    tag: m[0].replace(/\s+/g, ' ').trim(),
    attrs: attrs,
    fileId: attrs['data-id'] || null,
    realId: attrs['data-realid'] || null,
    mediaId: attrs['data-mediaid'] || null,
    fileVersion: attrs['data-fileversion'] || null
  };
}

export function parseAttrList(s) {
  const out = {};
  const re = /([A-Z0-9-]+)=("[^"]*"|[^,]*)/g;
  let m;
  while ((m = re.exec(s)) !== null) {
    let v = m[2];
    if (v.charAt(0) === '"') v = v.slice(1, -1);
    out[m[1]] = v;
  }
  return out;
}

/** Parse an HLS master playlist. */
export function parseMaster(text) {
  const lines = text.split(/\r?\n/);
  const variants = [];
  const media = [];
  const other = [];
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i].trim();
    if (!l) continue;
    if (l.indexOf('#EXT-X-MEDIA:') === 0) {
      media.push(parseAttrList(l.slice('#EXT-X-MEDIA:'.length)));
    } else if (l.indexOf('#EXT-X-STREAM-INF:') === 0) {
      const a = parseAttrList(l.slice('#EXT-X-STREAM-INF:'.length));
      let j = i + 1;
      while (j < lines.length && (!lines[j].trim() || lines[j].trim().charAt(0) === '#')) j++;
      variants.push({
        bandwidth: a.BANDWIDTH ? Number(a.BANDWIDTH) : null,
        averageBandwidth: a['AVERAGE-BANDWIDTH'] ? Number(a['AVERAGE-BANDWIDTH']) : null,
        resolution: a.RESOLUTION || null,
        codecs: a.CODECS || null,
        frameRate: a['FRAME-RATE'] || null,
        uri: (lines[j] || '').trim() || null
      });
    } else if (l.charAt(0) === '#') {
      if (other.length < 40) other.push(l);
    }
  }
  return { isMaster: variants.length > 0, variants: variants, media: media, tags: other };
}

/** Parse a media (variant) playlist. */
export function parseMediaPlaylist(text) {
  const lines = text.split(/\r?\n/);
  const segments = [];
  const keys = [];
  let pendingDuration = null;
  let targetDuration = null;
  let endList = false;
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i].trim();
    if (!l) continue;
    if (l.indexOf('#EXTINF:') === 0) {
      pendingDuration = parseFloat(l.slice('#EXTINF:'.length).split(',')[0]);
    } else if (l.indexOf('#EXT-X-TARGETDURATION:') === 0) {
      targetDuration = Number(l.split(':')[1]);
    } else if (l.indexOf('#EXT-X-KEY:') === 0) {
      keys.push(parseAttrList(l.slice('#EXT-X-KEY:'.length)));
    } else if (l === '#EXT-X-ENDLIST') {
      endList = true;
    } else if (l.charAt(0) !== '#') {
      segments.push({ uri: l, duration: pendingDuration });
      pendingDuration = null;
    }
  }
  let total = 0;
  for (let i = 0; i < segments.length; i++) total += segments[i].duration || 0;
  return {
    segments: segments,
    segmentCount: segments.length,
    targetDuration: targetDuration,
    approxDurationSec: Math.round(total * 100) / 100,
    endList: endList,
    keys: keys
  };
}

export function absolutize(baseUrl, uri) {
  try { return new URL(uri, baseUrl).toString(); } catch (e) { return uri; }
}

export function hostOf(url) {
  try { return new URL(url).host; } catch (e) { return null; }
}

/* ------------------------------------------------------------------ */
/* core resolver                                                       */
/* ------------------------------------------------------------------ */

export function parseEmbedUrl(embedUrl) {
  let u;
  try { u = new URL(embedUrl); } catch (e) { return { ok: false, reason: 'bad_url' }; }
  const m = u.pathname.match(/\/stream\/([^/]+)\/([^/]+)\/([A-Za-z0-9_]+)\/?$/);
  if (!m) return { ok: false, reason: 'bad_embed_path', path: u.pathname };
  return {
    ok: true,
    origin: u.origin,
    serverPath: m[1],
    embedId: m[2],
    language: m[3],
    host: u.host
  };
}

/**
 * Full resolve: embed url -> playable master.m3u8 + headers.
 * opts: { playlist:boolean, probeVariant:boolean, probeSegment:boolean,
 *         refererOnly:string, skipPage:boolean, language:string }
 */
export async function resolveEmbed(embedUrl, opts) {
  opts = opts || {};
  const started = Date.now();
  const result = {
    ok: false,
    code: null,
    input: embedUrl,
    resolverVersion: RESOLVER_VERSION,
    ts: new Date().toISOString(),
    embedId: null,
    language: null,
    fileId: null,
    mediaId: null,
    fileVersion: null,
    refererUsed: null,
    masterUrl: null,
    masterHost: null,
    playbackHeaders: null,
    tracks: null,
    intro: null,
    outro: null,
    server: null,
    thumbnails: null,
    pageAttempts: [],
    getSourcesAttempts: [],
    masterStatus: null,
    masterContentType: null,
    variants: [],
    media: [],
    variantProbe: null,
    segmentProbe: null,
    stage: 'parse',
    error: null,
    ms: 0
  };

  const parsed = parseEmbedUrl(embedUrl);
  if (!parsed.ok) {
    result.code = parsed.reason;
    result.error = parsed.path || embedUrl;
    result.ms = Date.now() - started;
    return result;
  }
  result.embedId = parsed.embedId;
  result.language = opts.language || parsed.language;
  const pageUrl = parsed.origin + '/stream/' + parsed.serverPath + '/' + parsed.embedId + '/' + result.language;

  /* ---- stage 1: page, sweeping referers ---- */
  result.stage = 'page';
  const referers = opts.refererOnly ? [opts.refererOnly] : PAGE_REFERERS;
  let player = null;
  let pageHtml = null;
  let lastPageError = null;

  for (let i = 0; i < referers.length; i++) {
    const ref = referers[i];
    const att = { referer: ref, status: null, pageError: null, hasPlayer: false, bytes: 0 };
    try {
      const res = await httpGet(pageUrl, { headers: { 'Referer': ref } });
      att.status = res.status;
      att.bytes = res.text.length;
      pageHtml = res.text;
      const err = detectPageError(res.text);
      player = parsePlayerDiv(res.text);
      att.pageError = err;
      att.hasPlayer = !!player;
      result.pageAttempts.push(att);
      if (player) { result.refererUsed = ref; break; }
      lastPageError = err || ('http_' + res.status);
    } catch (e) {
      att.error = String(e && e.message ? e.message : e);
      result.pageAttempts.push(att);
      lastPageError = 'network';
    }
  }

  if (!player) {
    result.code = lastPageError === 'gone_410' ? 'code_dead'
      : lastPageError === 'not_found_404' ? 'code_not_found'
      : (lastPageError || 'page_blocked');
    result.error = 'no megaplay-player div after ' + referers.length + ' referer attempts';
    result.ms = Date.now() - started;
    return result;
  }

  result.fileId = player.fileId;
  result.mediaId = player.mediaId;
  result.fileVersion = player.fileVersion;
  if (player.realId && String(player.realId) !== String(result.embedId)) {
    result.realIdMismatch = { embedId: result.embedId, realId: player.realId };
  }

  /* ---- stage 2: getSources ---- */
  result.stage = 'getSources';
  const _cb = Math.floor(Date.now() / 1000);
  const srcUrl = MEGA + '/stream/getSources?id=' + player.fileId +
    '&platform=OTHER&st=other&_=' + _cb;

  const srcReferers = [result.refererUsed].concat(PAGE_REFERERS.filter(function (r) { return r !== result.refererUsed; }));
  let payload = null;
  let srcRefUsed = null;

  for (let i = 0; i < srcReferers.length; i++) {
    const ref = srcReferers[i];
    const att = { referer: ref, status: null, ajaxRejected: false };
    try {
      const res = await httpGet(srcUrl, {
        headers: {
          'Referer': ref,
          'X-Requested-With': 'XMLHttpRequest',
          'Accept': 'application/json, text/javascript, */*; q=0.01',
          'Origin': MEGA
        }
      });
      att.status = res.status;
      att.bytes = res.text.length;
      if (res.status === 403) {
        att.ajaxRejected = /only AJAX requests/i.test(res.text);
        result.getSourcesAttempts.push(att);
        continue;
      }
      let j = null;
      try { j = JSON.parse(res.text); } catch (e) { att.parseError = true; }
      if (!j) { result.getSourcesAttempts.push(att); continue; }
      if (j.enc) {
        payload = j;
        srcRefUsed = ref;
        att.ok = true;
        result.getSourcesAttempts.push(att);
        break;
      }
      att.noEnc = true;
      att.keys = Object.keys(j);
      result.getSourcesAttempts.push(att);
    } catch (e) {
      att.error = String(e && e.message ? e.message : e);
      result.getSourcesAttempts.push(att);
    }
  }

  if (!payload) {
    result.code = 'sources_failed';
    result.error = 'getSources returned no enc payload';
    result.ms = Date.now() - started;
    return result;
  }

  result.tracks = payload.tracks || null;
  result.intro = payload.intro || null;
  result.outro = payload.outro || null;
  result.server = payload.server === undefined ? null : payload.server;
  result.image = payload.image || null;
  if (payload.tracks) {
    for (let i = 0; i < payload.tracks.length; i++) {
      if (payload.tracks[i] && payload.tracks[i].kind === 'thumbnails') result.thumbnails = payload.tracks[i].file;
    }
  }

  /* ---- stage 3: decrypt ---- */
  result.stage = 'decrypt';
  let fileUrl = null;
  try {
    const dec = resolveEncPayload(payload.enc);
    result.enc = { len: payload.enc.length, plain: dec.raw.slice(0, 400) };
    if (dec.json && dec.json.file) fileUrl = dec.json.file;
    else if (dec.raw.trim().indexOf('http') === 0) fileUrl = dec.raw.trim();
  } catch (e) {
    result.code = 'decrypt_failed';
    result.error = String(e && e.message ? e.message : e);
    result.ms = Date.now() - started;
    return result;
  }

  if (!fileUrl) {
    result.code = 'no_file_in_payload';
    result.ms = Date.now() - started;
    return result;
  }

  result.masterUrl = fileUrl;
  result.masterHost = hostOf(fileUrl);
  result.playbackHeaders = { 'Referer': CDN_REFERER, 'User-Agent': UA };

  /* ---- stage 4: probe master playlist ---- */
  if (opts.playlist !== false) {
    result.stage = 'master';
    const refs = [CDN_REFERER, result.refererUsed, 'https://megaplay.buzz'];
    for (let i = 0; i < refs.length; i++) {
      try {
        const res = await httpGet(fileUrl, { headers: { 'Referer': refs[i] } });
        if (res.status === 200 && res.text.indexOf('#EXTM3U') !== -1) {
          result.masterStatus = res.status;
          result.masterContentType = res.headers['content-type'] || null;
          result.masterRefererUsed = refs[i];
          const pm = parseMaster(res.text);
          result.variants = pm.variants;
          result.media = pm.media;
          result.masterTags = pm.tags.slice(0, 20);
          if (opts.rawMaster) result.masterRaw = res.text.slice(0, 4000);
          break;
        }
        result.masterStatus = res.status;
        result.masterContentType = (res.headers && res.headers['content-type']) || null;
        result.masterErrSample = res.text.slice(0, 200);
      } catch (e) {
        result.masterStatus = 'ERR';
        result.error = String(e && e.message ? e.message : e);
      }
    }
  }

  /* ---- stage 5: probe one variant ---- */
  if (opts.probeVariant !== false && result.variants.length > 0) {
    result.stage = 'variant';
    const pick = opts.variantIndex !== undefined
      ? result.variants[opts.variantIndex]
      : result.variants[0];
    if (pick && pick.uri) {
      const vUrl = absolutize(fileUrl, pick.uri);
      try {
        const res = await httpGet(vUrl, { headers: { 'Referer': CDN_REFERER } });
        const pm = parseMediaPlaylist(res.text);
        result.variantProbe = {
          url: vUrl,
          status: res.status,
          bytes: res.text.length,
          segmentCount: pm.segmentCount,
          targetDuration: pm.targetDuration,
          approxDurationSec: pm.approxDurationSec,
          endList: pm.endList,
          keys: pm.keys,
          firstSegment: pm.segments[0] ? absolutize(vUrl, pm.segments[0].uri) : null,
          firstSegmentDuration: pm.segments[0] ? pm.segments[0].duration : null
        };
      } catch (e) {
        result.variantProbe = { url: vUrl, error: String(e && e.message ? e.message : e) };
      }
    }
  }

  /* ---- stage 6: probe one segment with and without Referer ---- */
  if (opts.probeSegment && result.variantProbe && result.variantProbe.firstSegment) {
    result.stage = 'segment';
    const segUrl = result.variantProbe.firstSegment;
    const withRef = { url: segUrl };
    const noRef = { url: segUrl };
    try {
      const res = await httpGet(segUrl, { headers: { 'Referer': CDN_REFERER, 'Range': 'bytes=0-1023' } });
      withRef.status = res.status;
      withRef.bytes = res.body.length;
      withRef.contentType = res.headers['content-type'];
    } catch (e) { withRef.error = String(e && e.message ? e.message : e); }
    try {
      const res = await httpGet(segUrl, { headers: { 'Range': 'bytes=0-1023' } });
      noRef.status = res.status;
      noRef.bytes = res.body.length;
      noRef.contentType = res.headers['content-type'];
      noRef.sample = res.text.slice(0, 120);
    } catch (e) { noRef.error = String(e && e.message ? e.message : e); }
    result.segmentProbe = { withReferer: withRef, withoutReferer: noRef };
  }

  result.ok = true;
  result.code = 'ok';
  result.stage = 'done';
  result.ms = Date.now() - started;
  return result;
}

/* ------------------------------------------------------------------ */
/* api helpers                                                         */
/* ------------------------------------------------------------------ */

export async function getSeries(seriesId) {
  const res = await httpGet(API + '/series/' + seriesId);
  if (!res.ok) throw new Error('series ' + seriesId + ' HTTP ' + res.status);
  return JSON.parse(res.text);
}

export async function getRecent(page, perPage) {
  const res = await httpGet(API + '/recent-anime?page=' + (page || 1) + '&per_page=' + (perPage || 20));
  if (!res.ok) throw new Error('recent HTTP ' + res.status);
  return JSON.parse(res.text);
}

/** Pick episodes from a series payload -> [{number, embedId, language, embedUrl}] */
export function listEpisodeSources(seriesJson, opts) {
  opts = opts || {};
  const out = [];
  const eps = (seriesJson && seriesJson.data && seriesJson.data.episodes) || [];
  for (let i = 0; i < eps.length; i++) {
    const e = eps[i];
    const urls = e.embed_url || {};
    const langs = Object.keys(urls);
    for (let k = 0; k < langs.length; k++) {
      const lang = langs[k];
      if (opts.languages && opts.languages.indexOf(lang) === -1) continue;
      out.push({
        number: e.number,
        episodeId: e.id,
        embedId: e.episode_embed_id,
        language: lang,
        embedUrl: urls[lang]
      });
    }
    if (opts.includeMissingDub) {
      const hasDub = Object.prototype.hasOwnProperty.call(urls, 'dub');
      if (!hasDub) {
        out.push({
          number: e.number,
          episodeId: e.id,
          embedId: e.episode_embed_id,
          language: 'dub',
          embedUrl: 'https://megaplay.buzz/stream/s-2/' + e.episode_embed_id + '/dub',
          synthesised: true
        });
      }
    }
  }
  return out;
}

/** Fetch + decrypt the allowed-referer domain list. */
export async function getDomains() {
  const h = new Date().toISOString().slice(0, 13).replace(/[-T]/g, '');
  const url = MEGA + '/domains?h=' + h;
  const res = await httpGet(url, { headers: { 'Referer': CDN_REFERER } });
  let j = null;
  try { j = JSON.parse(res.text); } catch (e) { /* ignore */ }
  if (!j || !j.enc) {
    const res2 = await httpGet(MEGA + '/domains', { headers: { 'Referer': CDN_REFERER } });
    try { j = JSON.parse(res2.text); } catch (e) { j = null; }
    if (!j) throw new Error('domains endpoint returned non-JSON');
  }
  const dec = resolveEncPayload(j.enc);
  return { url: url, encLen: j.enc.length, payload: dec.json, raw: dec.raw };
}

/* ------------------------------------------------------------------ */
/* CLI                                                                 */
/* ------------------------------------------------------------------ */

function parseArgv(argv) {
  const o = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--playlist') o.playlist = true;
    else if (a === '--no-playlist') o.playlist = false;
    else if (a === '--json') o.json = true;
    else if (a === '--quiet') o.quiet = true;
    else if (a === '--segment') o.probeSegment = true;
    else if (a === '--all') o.all = true;
    else if (a === '--domains') o.domains = true;
    else if (a === '--raw') o.rawMaster = true;
    else if (a === '--series') { o.series = argv[++i]; }
    else if (a === '--file') { o.file = argv[++i]; }
    else if (a === '--lang') { o.lang = argv[++i]; }
    else if (a === '--referer') { o.refererOnly = argv[++i]; }
    else if (a === '--variant') { o.variantIndex = Number(argv[++i]); }
    else o._.push(a);
  }
  return o;
}

function printResult(r, opts) {
  if (opts.json || !opts.quiet) {
    process.stdout.write(JSON.stringify(r, null, 2) + '\n');
  } else {
    process.stdout.write((r.ok ? 'OK  ' : 'FAIL') + ' ' + (r.masterUrl || r.code) + '\n');
  }
}

async function main() {
  const argv = process.argv.slice(2);
  const opts = parseArgv(argv);

  if (opts.domains) {
    const d = await getDomains();
    process.stdout.write(JSON.stringify(d, null, 2) + '\n');
    return;
  }

  /* resolve a single embed url */
  if (opts._.length > 0) {
    const r = await resolveEmbed(opts._[0], opts);
    printResult(r, opts);
    if (opts.playlist && r.ok) {
      const res = await httpGet(r.masterUrl, { headers: { 'Referer': CDN_REFERER } });
      process.stderr.write('=== master.m3u8 (' + res.status + ') ===\n');
      process.stderr.write(res.text + '\n');
      if (r.variantProbe && r.variantProbe.url) {
        const v = await httpGet(r.variantProbe.url, { headers: { 'Referer': CDN_REFERER } });
        process.stderr.write('=== variant (' + v.status + ') ' + r.variantProbe.url + ' ===\n');
        const lines = v.text.split(/\r?\n/);
        process.stderr.write(lines.slice(0, 25).join('\n') + '\n...[' + lines.length + ' lines total]\n');
      }
    }
    return;
  }

  /* direct file id */
  if (opts.file) {
    const url = MEGA + '/stream/s-2/' + opts.file + '/' + (opts.lang || 'sub');
    const r = await resolveEmbed(url, opts);
    printResult(r, opts);
    return;
  }

  /* series mode */
  if (opts.series) {
    const js = await getSeries(opts.series);
    const eps = listEpisodeSources(js, { languages: opts.lang ? [opts.lang] : null });
    if (!opts.all) {
      process.stdout.write(JSON.stringify({ series: js.data && js.data.anime ? js.data.anime.title : null, total: eps.length, episodes: eps }, null, 2) + '\n');
      return;
    }
    const out = [];
    for (let i = 0; i < eps.length; i++) {
      const r = await resolveEmbed(eps[i].embedUrl, opts);
      r.seriesEpisode = eps[i];
      out.push(r);
      process.stderr.write('[' + (i + 1) + '/' + eps.length + '] ep' + eps[i].number + ' ' + eps[i].language + ' -> ' + (r.ok ? 'ok ' + r.masterHost : r.code) + '\n');
    }
    process.stdout.write(JSON.stringify(out, null, 2) + '\n');
    return;
  }

  process.stderr.write('usage: node resolve.mjs <embedUrl> [--playlist] [--segment] [--json]\n');
  process.stderr.write('       node resolve.mjs --series <id> [--all] [--lang sub|dub]\n');
  process.stderr.write('       node resolve.mjs --file <embedId> [--lang sub]\n');
  process.stderr.write('       node resolve.mjs --domains\n');
  process.exitCode = 2;
}

const isMain = (function () {
  try {
    return import.meta.url === pathToFileURL(process.argv[1]).href;
  } catch (e) { return false; }
})();

if (isMain) {
  main().catch(function (e) {
    process.stderr.write('FATAL: ' + (e && e.stack ? e.stack : e) + '\n');
    process.exitCode = 1;
  });
}
