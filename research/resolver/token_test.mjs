#!/usr/bin/env node
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const MEGA = 'https://megaplay.buzz';
const API = 'https://anikotoapi.site';
const CDN_REFERER = 'https://megaplay.buzz/';
const PAGE_REFERER = 'https://anikototv.bz/';
const UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36';

export const AES_KEY_STR = 'i?LMTAx0Q6,:}50U';
export const AES_IV_STR = "W0;27ToaUpl_P%'c";
export const TOKEN_KEY = 'MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s';

const HERE = path.dirname(new URL(import.meta.url).pathname);
const STATE = path.join(HERE, '.token_state.json');

/* <=2 req / 5 s per non-API host; API 1 req / 1.5 s */
const GAP_HOST_MS = 2600;
const GAP_API_MS = 1600;
const lastByHost = new Map();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function throttle(url) {
  const host = new URL(url).host;
  const gap = host === 'anikotoapi.site' ? GAP_API_MS : GAP_HOST_MS;
  const wait = (lastByHost.get(host) || 0) + gap - Date.now();
  if (wait > 0) await sleep(wait);
  lastByHost.set(host, Date.now());
}

export function aesKey() {
  const k = Buffer.alloc(32, 0);
  Buffer.from(AES_KEY_STR, 'utf8').copy(k, 0);
  return k;
}
export function aesIv() {
  const iv = Buffer.from(AES_IV_STR, 'utf8');
  if (iv.length !== 16) throw new Error('iv must be 16 bytes, got ' + iv.length);
  return iv;
}
export function b64urlDecode(s) {
  let t = String(s).replace(/-/g, '+').replace(/_/g, '/').replace(/[^A-Za-z0-9+/=]/g, '');
  while (t.length % 4 !== 0) t += '=';
  return Buffer.from(t, 'base64');
}
export const b64url = (buf) => Buffer.from(buf).toString('base64url');

export function decryptEnc(enc) {
  const raw = b64urlDecode(enc);
  const d = crypto.createDecipheriv('aes-256-cbc', aesKey(), aesIv());
  return Buffer.concat([d.update(raw), d.final()]).toString('utf8');
}

export function pathIds(u) {
  const m = new URL(u).pathname.match(/\/anime\/([a-f0-9]{32})\/([a-f0-9]{32})\//i);
  return m ? { h1: m[1].toLowerCase(), h2: m[2].toLowerCase() } : null;
}

export function signToken(fileUrl, opts) {
  opts = opts || {};
  const ids = pathIds(fileUrl);
  if (!ids) throw new Error('no /anime/<32hex>/<32hex>/ in ' + fileUrl);
  const expires = opts.expiresAt != null ? opts.expiresAt : Math.floor(Date.now() / 1000) + 90;
  const payload = expires + '|' + ids.h1 + '/' + ids.h2;
  const sig = crypto.createHmac('sha256', Buffer.from(TOKEN_KEY, 'utf8')).update(payload, 'utf8').digest();
  return b64url(Buffer.from(payload, 'utf8')) + '.' + b64url(sig);
}

export function withQuery(u, params) {
  const url = new URL(u);
  for (const k of Object.keys(params)) url.searchParams.set(k, params[k]);
  return url.toString();
}

async function get(url, headers, opts) {
  opts = opts || {};
  await throttle(url);
  const h = Object.assign({ 'User-Agent': UA, Accept: '*/*' }, headers || {});
  if (opts.range) h.Range = opts.range;
  const t0 = Date.now();
  try {
    const res = await fetch(url, { headers: h, redirect: 'follow', signal: AbortSignal.timeout(25000) });
    const buf = Buffer.from(await res.arrayBuffer());
    return {
      url: url, status: res.status, ms: Date.now() - t0, bytes: buf.length,
      contentType: res.headers.get('content-type'),
      server: res.headers.get('server'),
      cfRay: res.headers.get('cf-ray'),
      cfCache: res.headers.get('cf-cache-status'),
      body: buf, text: buf.toString('utf8'), sentHeaders: h
    };
  } catch (e) {
    return { url: url, status: 'ERR', error: String((e && e.message) || e), ms: Date.now() - t0, bytes: 0, sentHeaders: h, text: '' };
  }
}

const isCfChallenge = (r) => /<title>\s*Just a moment|cf-chl|Attention Required/i.test(r.text || '');

const attrs = (s) => {
  const o = {};
  const re = /([A-Z0-9-]+)=("[^"]*"|[^,]*)/g;
  let m;
  while ((m = re.exec(s))) o[m[1]] = m[2].charAt(0) === '"' ? m[2].slice(1, -1) : m[2];
  return o;
};
export function parseMaster(text) {
  const lines = text.split(/\r?\n/);
  const variants = [];
  const media = [];
  const keys = [];
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i].trim();
    if (!l) continue;
    if (l.startsWith('#EXT-X-MEDIA:')) media.push(attrs(l.slice(13)));
    else if (l.startsWith('#EXT-X-KEY:')) keys.push(attrs(l.slice(11)));
    else if (l.startsWith('#EXT-X-STREAM-INF:')) {
      const a = attrs(l.slice(18));
      let j = i + 1;
      while (j < lines.length && (!lines[j].trim() || lines[j].trim().startsWith('#'))) j++;
      variants.push({ bandwidth: Number(a.BANDWIDTH || 0), resolution: a.RESOLUTION || null, codecs: a.CODECS || null, uri: (lines[j] || '').trim() });
    }
  }
  return { isMaster: variants.length > 0, variants: variants, media: media, keys: keys };
}
export function parseMedia(text) {
  const lines = text.split(/\r?\n/);
  const segs = [];
  const keys = [];
  let dur = null, map = null, mediaSeq = 0;
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i].trim();
    if (!l) continue;
    if (l.startsWith('#EXTINF:')) dur = parseFloat(l.slice(8).split(',')[0]);
    else if (l.startsWith('#EXT-X-KEY:')) keys.push(attrs(l.slice(11)));
    else if (l.startsWith('#EXT-X-MAP:')) map = attrs(l.slice(11)).URI || null;
    else if (l.startsWith('#EXT-X-MEDIA-SEQUENCE:')) mediaSeq = Number(l.slice(22)) || 0;
    else if (l.charAt(0) !== '#') { segs.push({ uri: l, duration: dur }); dur = null; }
  }
  return { segments: segs, keys: keys, map: map, mediaSeq: mediaSeq, segmentCount: segs.length };
}
const abs = (base, uri) => new URL(uri, base).toString();

/* HTML attrs are lowercase; the HLS-only regex above would drop them */
const htmlAttrs = (tag) => {
  const o = {};
  const re = /([A-Za-z0-9_:-]+)\s*=\s*["']([^"']*)["']/g;
  let m;
  while ((m = re.exec(tag))) o[m[1].toLowerCase()] = m[2];
  return o;
};

function loadState() { try { return JSON.parse(fs.readFileSync(STATE, 'utf8')); } catch (e) { return {}; } }
function saveState(s) { fs.writeFileSync(STATE, JSON.stringify(s, null, 2)); }

async function stageResolve(opts) {
  const st = loadState();

  if (opts.series) {
    console.log('[1/4] GET ' + API + '/series/' + opts.series + '   (1 API request)');
    const r = await get(API + '/series/' + opts.series, { Accept: 'application/json' });
    if (r.status !== 200) { console.error('series HTTP ' + r.status + ' :: ' + r.text.slice(0, 200)); process.exit(1); }
    const j = JSON.parse(r.text);
    const eps = (j.data && j.data.episodes) || [];
    const pick = eps.find((e) => e.embed_url && (e.embed_url.sub || e.embed_url.dub));
    if (!pick) { console.error('no episode with embed_url'); process.exit(1); }
    const lang = pick.embed_url.sub ? 'sub' : 'dub';
    st.series = {
      id: opts.series,
      title: j.data && j.data.anime && j.data.anime.title,
      episode: pick.number, episodeId: pick.id,
      embedId: pick.episode_embed_id, lang: lang, embedUrl: pick.embed_url[lang]
    };
    console.log('      ep ' + pick.number + '  embedId=' + pick.episode_embed_id + '  ' + st.series.embedUrl);
    saveState(st);
  }

  const embedUrl = opts.embed || (st.series && st.series.embedUrl);
  if (!embedUrl) { console.error('no embed url; pass --embed or --series'); process.exit(1); }

  console.log('[2/4] GET ' + embedUrl + '   Referer=' + PAGE_REFERER);
  const page = await get(embedUrl, { Referer: PAGE_REFERER });
  if (page.status !== 200) { console.error('page HTTP ' + page.status); process.exit(1); }
  const div = page.text.match(/<div[^>]*id=["']megaplay-player["'][^>]*>/i);
  if (!div) {
    console.error('no #megaplay-player :: page error = ' + ((page.text.match(/Error Code:\s*\d+/) || ['none'])[0]));
    process.exit(1);
  }
  const a = htmlAttrs(div[0]);
  st.player = { tag: div[0].replace(/\s+/g, ' '), fileId: a['data-id'], realId: a['data-realid'], mediaId: a['data-mediaid'], version: a['data-fileversion'] };
  console.log('      ' + st.player.tag);
  console.log('      data-id=' + st.player.fileId + '  data-realid=' + st.player.realId + '  version=' + st.player.version);

  const srcUrl = MEGA + '/stream/getSources?id=' + st.player.fileId + '&platform=OTHER&st=other&_=' + Math.floor(Date.now() / 1000);
  console.log('[3/4] GET ' + srcUrl);
  const src = await get(srcUrl, {
    Referer: CDN_REFERER, Origin: MEGA, 'X-Requested-With': 'XMLHttpRequest',
    Accept: 'application/json, text/javascript, */*; q=0.01'
  });
  if (src.status !== 200) { console.error('getSources HTTP ' + src.status + ' :: ' + src.text.slice(0, 300)); process.exit(1); }
  const payload = JSON.parse(src.text);
  st.sources = { keys: Object.keys(payload), encLen: payload.enc && payload.enc.length, tracks: payload.tracks, intro: payload.intro, outro: payload.outro, server: payload.server, image: payload.image };
  console.log('      keys=' + st.sources.keys.join(',') + '  enc=' + st.sources.encLen + ' chars');

  console.log('[4/4] AES-256-CBC decrypt enc');
  const plain = decryptEnc(payload.enc);
  st.encPlain = plain;
  st.masterUrl = JSON.parse(plain).file;
  st.ids = pathIds(st.masterUrl);
  console.log('      plaintext = ' + plain);
  console.log('      masterUrl = ' + st.masterUrl);
  saveState(st);
}

function matrixSet(label, url) {
  const tokUrl = withQuery(url, { token: signToken(url) });
  return [
    { id: 'a', label: label + '  bare (no headers)', url: url, headers: {} },
    { id: 'b', label: label + '  ?token= only', url: tokUrl, headers: {} },
    { id: 'c', label: label + '  ?token= + Referer', url: tokUrl, headers: { Referer: CDN_REFERER, Origin: 'https://megaplay.buzz' } },
    { id: 'd', label: label + '  Referer only', url: url, headers: { Referer: CDN_REFERER, Origin: 'https://megaplay.buzz' } }
  ];
}

async function runProbes(probes, range) {
  const rows = [];
  for (let i = 0; i < probes.length; i++) {
    const p = probes[i];
    const r = await get(p.url, p.headers, { range: range });
    rows.push({
      id: p.id, label: p.label, url: p.url, headers: p.headers,
      status: r.status, bytes: r.bytes,
      contentType: r.contentType, server: r.server, cfRay: r.cfRay,
      cfChallenge: isCfChallenge(r),
      snippet: (r.text || '').slice(0, 160).replace(/\s+/g, ' ')
    });
  }
  return rows;
}

function printMatrix(title, rows) {
  console.log('\n=== ' + title + ' ===');
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i];
    console.log('  (' + r.id + ') ' + r.status + '  ' + String(r.bytes).padStart(7) + 'B  ct=' + r.contentType + (r.cfChallenge ? '  [CF-CHALLENGE]' : '') + '  server=' + (r.server || '-'));
    console.log('        ' + r.label);
    if (r.snippet) console.log('        body~ ' + r.snippet.slice(0, 120));
  }
}

async function stageMatrix() {
  const st = loadState();
  if (!st.masterUrl) { console.error('run "resolve" first'); process.exit(1); }
  const master = st.masterUrl;

  const mRows = await runProbes(matrixSet('master', master));
  printMatrix('MASTER  ' + master, mRows);

  const good = mRows.find((r) => r.status === 200);
  if (!good) { console.error('\nno 200 on master -> cannot continue'); saveState(st); return; }
  const probe = await get(good.url, good.id === 'a' ? {} : { Referer: CDN_REFERER, Origin: 'https://megaplay.buzz' });
  const pm = parseMaster(probe.text);
  console.log('\nmaster parsed: variants=' + pm.variants.length + ' media=' + pm.media.length + ' keys=' + JSON.stringify(pm.keys));
  for (let i = 0; i < pm.variants.length; i++) console.log('   ' + pm.variants[i].resolution + '  bw=' + pm.variants[i].bandwidth + '  ' + pm.variants[i].uri);
  console.log('   raw:\n' + probe.text.slice(0, 900));
  st.variants = pm.variants.map((v) => ({ resolution: v.resolution, bandwidth: v.bandwidth, uri: v.uri, url: abs(master, v.uri) }));
  if (!st.variants.length) { saveState(st); return; }

  const variantUrl = st.variants[0].url;
  const vRows = await runProbes(matrixSet('variant', variantUrl));
  printMatrix('VARIANT  ' + variantUrl, vRows);

  const vGood = vRows.find((r) => r.status === 200);
  if (vGood) {
    const vp = await get(vGood.url, vGood.id === 'a' ? {} : { Referer: CDN_REFERER, Origin: 'https://megaplay.buzz' });
    const mp = parseMedia(vp.text);
    console.log('\nvariant parsed: segments=' + mp.segmentCount + ' keys=' + JSON.stringify(mp.keys) + ' map=' + mp.map);
    console.log('   raw head:\n' + vp.text.split(/\r?\n/).slice(0, 14).join('\n'));
    st.segments = mp.segments.slice(0, 3).map((s) => ({ duration: s.duration, url: abs(variantUrl, s.uri), uri: s.uri }));
    st.firstSegmentUrl = st.segments[0] ? st.segments[0].url : null;
    st.hlsKeys = mp.keys;
    st.variantSegmentCount = mp.segmentCount;
  }

  if (st.firstSegmentUrl) {
    const sRows = await runProbes(matrixSet('segment', st.firstSegmentUrl), 'bytes=0-2047');
    printMatrix('SEGMENT (Range: bytes=0-2047)  ' + st.firstSegmentUrl, sRows);
    st.matrix = { master: mRows, variant: vRows, segment: sRows };
  } else {
    st.matrix = { master: mRows, variant: vRows };
  }
  saveState(st);
}

async function stageExpiry() {
  const st = loadState();
  if (!st.masterUrl) { console.error('run "resolve" first'); process.exit(1); }
  const url = st.masterUrl;

  const expiredTok = signToken(url, { expiresAt: Math.floor(Date.now() / 1000) - 10 });
  const freshTok = signToken(url);
  const tampered = freshTok.slice(0, freshTok.length - 4) + 'AAAA';
  const futureTok = signToken(url, { expiresAt: Math.floor(Date.now() / 1000) + 1800 });

  const cases = [
    { id: 'x1', label: 'expired token (exp = now-10s)', url: withQuery(url, { token: expiredTok }) },
    { id: 'x2', label: 'tampered signature (exp = now+90s)', url: withQuery(url, { token: tampered }) },
    { id: 'x3', label: 'fresh token (exp = now+90s)', url: withQuery(url, { token: freshTok }) },
    { id: 'x4', label: 'far-future token (exp = now+1800s)', url: withQuery(url, { token: futureTok }) },
    { id: 'x5', label: 'no token, Referer only', url: url, headers: { Referer: CDN_REFERER, Origin: 'https://megaplay.buzz' } },
    { id: 'x6', label: 'no token, no headers', url: url }
  ];

  console.log('\n=== TOKEN SEMANTICS (master) ===');
  const out = [];
  for (let i = 0; i < cases.length; i++) {
    const c = cases[i];
    const r = await get(c.url, c.headers || {});
    const row = { id: c.id, label: c.label, status: r.status, bytes: r.bytes, ct: r.contentType, snippet: (r.text || '').slice(0, 140).replace(/\s+/g, ' ') };
    out.push(row);
    console.log('  (' + c.id + ') ' + r.status + '  ' + String(r.bytes).padStart(6) + 'B  ' + c.label);
    if (row.snippet) console.log('        body~ ' + row.snippet);
  }

  const realTok = signToken(url);
  const realUrl = withQuery(url, { token: realTok });
  const first = await get(realUrl, {});
  console.log('\n  (t0) same-token baseline: ' + first.status + '  ' + first.bytes + 'B');
  console.log('  sleeping 100 s to cross the 90 s expiry ...');
  await sleep(100000);
  const after = await get(realUrl, {});
  console.log('  (t0+100s) same token again: ' + after.status + '  ' + after.bytes + 'B');
  if (after.status !== 200) console.log('     body~ ' + (after.text || '').slice(0, 160).replace(/\s+/g, ' '));

  st.expiry = { out: out, timeline: { baseline: first.status, afterExpiry: after.status, afterBytes: after.bytes } };
  saveState(st);
}

const argv = process.argv.slice(2);
const cmd = argv[0] || 'all';
const flag = (n) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : undefined; };

async function main() {
  if (cmd === 'resolve') return stageResolve({ series: flag('--series'), embed: flag('--embed') });
  if (cmd === 'matrix') return stageMatrix();
  if (cmd === 'expiry') return stageExpiry();
  if (cmd === 'all') {
    await stageResolve({ series: flag('--series'), embed: flag('--embed') });
    await stageMatrix();
    await stageExpiry();
    return;
  }
  console.error('usage: token_test.mjs resolve|matrix|expiry|all [--series id] [--embed url]');
  process.exit(2);
}
main().catch((e) => { console.error('FATAL ' + (e && e.stack ? e.stack : e)); process.exit(1); });

