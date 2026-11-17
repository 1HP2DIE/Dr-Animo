#!/usr/bin/env node
/**
 * batch.mjs -- run the resolver across many episodes/series and produce results.json
 *
 * node batch.mjs --series-count 8 --per-series 2 --segment-probes 3 --out research/resolver/results.json
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  resolveEmbed, getRecent, getSeries, listEpisodeSources,
  httpGet, CDN_REFERER, hostOf
} from './resolve.mjs';

function parseArgv(argv) {
  const o = { seriesCount: 8, perSeries: 2, segmentProbes: 3 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--series-count') o.seriesCount = Number(argv[++i]);
    else if (a === '--per-series') o.perSeries = Number(argv[++i]);
    else if (a === '--segment-probes') o.segmentProbes = Number(argv[++i]);
    else if (a === '--out') o.out = argv[++i];
    else if (a === '--ids') o.ids = argv[++i].split(',').map(Number);
    else if (a === '--page') o.page = Number(argv[++i]);
  }
  return o;
}

function log(s) { process.stderr.write(s + '\n'); }

async function main() {
  const opts = parseArgv(process.argv.slice(2));
  const outPath = opts.out || 'research/resolver/results.json';
  const startedAt = new Date().toISOString();
  const report = {
    startedAt: startedAt,
    finishedAt: null,
    resolverVersion: '1.0.0',
    config: opts,
    series: [],
    results: [],
    summary: {},
    notes: []
  };

  /* --- 1. discover series from the recent feed --- */
  const recent = await getRecent(opts.page || 1, 20);
  let ids = opts.ids;
  if (!ids) {
    ids = recent.data.slice(0, opts.seriesCount).map(function (d) { return d.id; });
  }
  report.recentIds = ids;
  log('series ids: ' + ids.join(', '));

  /* --- 2. pull each series, collect candidate episodes --- */
  const embedUrlKeyCounts = {};
  const candidates = [];

  for (let i = 0; i < ids.length; i++) {
    let js;
    try {
      js = await getSeries(ids[i]);
    } catch (e) {
      report.series.push({ id: ids[i], error: String(e.message || e) });
      log('series ' + ids[i] + ' FAILED: ' + e.message);
      continue;
    }
    const anime = js.data.anime;
    const eps = js.data.episodes || [];
    const keysSeen = {};
    for (let k = 0; k < eps.length; k++) {
      const u = eps[k].embed_url || {};
      Object.keys(u).forEach(function (kk) { keysSeen[kk] = (keysSeen[kk] || 0) + 1; embedUrlKeyCounts[kk] = (embedUrlKeyCounts[kk] || 0) + 1; });
    }
    const noDub = eps.filter(function (e) { return !(e.embed_url && e.embed_url.dub); });

    report.series.push({
      id: anime.id,
      title: anime.title,
      slug: anime.slug,
      episodes: eps.length,
      is_dub: anime.is_dub,
      is_sub: anime.is_sub,
      embedUrlKeyHistogram: keysSeen,
      episodesWithoutDubKey: noDub.length,
      firstEpisodeWithoutDub: noDub[0] ? noDub[0].number : null
    });

    /* pick: one sub, one dub (if present), one explicit dub URL for a sub-only episode */
    const withSub = eps.filter(function (e) { return e.embed_url && e.embed_url.sub; });
    const withDub = eps.filter(function (e) { return e.embed_url && e.embed_url.dub; });

    if (withSub.length) {
      const e = withSub[withSub.length - 1];
      candidates.push({ seriesId: anime.id, seriesTitle: anime.title, number: e.number, language: 'sub', embedUrl: e.embed_url.sub, exists: true });
    }
    if (withDub.length && opts.perSeries > 1) {
      const e = withDub[withDub.length - 1];
      candidates.push({ seriesId: anime.id, seriesTitle: anime.title, number: e.number, language: 'dub', embedUrl: e.embed_url.dub, exists: true });
    }
    if (opts.perSeries > 2 && noDub.length) {
      const e = noDub[noDub.length - 1];
      candidates.push({
        seriesId: anime.id, seriesTitle: anime.title, number: e.number, language: 'dub',
        embedUrl: 'https://megaplay.buzz/stream/s-2/' + e.episode_embed_id + '/dub',
        exists: false, note: 'synthesised dub URL for a sub-only episode'
      });
    }
    log('series ' + anime.id + ' ' + anime.title + ' eps=' + eps.length + ' noDub=' + noDub.length);
  }

  report.embedUrlKeys = embedUrlKeyCounts;

  /* --- 3. resolve each candidate --- */
  let segmentProbes = 0;
  for (let i = 0; i < candidates.length; i++) {
    const c = candidates[i];
    const doSegment = segmentProbes < opts.segmentProbes;
    const r = await resolveEmbed(c.embedUrl, { playlist: true, probeVariant: true, probeSegment: doSegment });
    if (doSegment && r.segmentProbe) segmentProbes++;
    r.candidate = c;
    report.results.push(r);
    log('[' + (i + 1) + '/' + candidates.length + '] ' + c.seriesId + ' ep' + c.number + ' ' + c.language +
      ' -> ' + (r.ok ? 'OK ' + r.masterHost + ' v=' + r.variants.length : 'FAIL ' + r.code));
  }

  /* --- 4. summary --- */
  const total = report.results.length;
  const ok = report.results.filter(function (r) { return r.ok; });
  const codes = {};
  report.results.forEach(function (r) { codes[r.code] = (codes[r.code] || 0) + 1; });
  const masterHosts = {};
  const regionHosts = {};
  const variantCounts = {};
  const langStats = {};
  report.results.forEach(function (r) {
    if (!r.ok) return;
    masterHosts[r.masterHost] = (masterHosts[r.masterHost] || 0) + 1;
    if (r.segmentProbe && r.segmentProbe.withReferer && r.segmentProbe.withReferer.url) {
      const h = hostOf(r.segmentProbe.withReferer.url);
      regionHosts[h] = (regionHosts[h] || 0) + 1;
    }
    variantCounts[r.variants.length] = (variantCounts[r.variants.length] || 0) + 1;
    const L = r.language;
    langStats[L] = langStats[L] || { ok: 0, fail: 0 };
    langStats[L].ok++;
  });
  report.results.forEach(function (r) {
    if (r.ok) return;
    const L = r.language;
    langStats[L] = langStats[L] || { ok: 0, fail: 0 };
    langStats[L].fail++;
  });

  const referersUsed = {};
  ok.forEach(function (r) { referersUsed[r.refererUsed] = (referersUsed[r.refererUsed] || 0) + 1; });

  report.summary = {
    seriesAttempted: ids.length,
    embedsAttempted: total,
    ok: ok.length,
    failed: total - ok.length,
    successRate: total ? Math.round((ok.length / total) * 1000) / 10 : 0,
    byCode: codes,
    masterHosts: masterHosts,
    segmentHosts: regionHosts,
    variantCountHistogram: variantCounts,
    byLanguage: langStats,
    refererUsedHistogram: referersUsed,
    distinctSeriesInResults: Object.keys(report.results.reduce(function (a, r) { a[r.candidate.seriesId] = 1; return a; }, {})).length,
    resolutionsSeen: (function () {
      const s = {};
      ok.forEach(function (r) { r.variants.forEach(function (v) { if (v.resolution) s[v.resolution] = (s[v.resolution] || 0) + 1; }); });
      return s;
    })(),
    bandwidthSeen: (function () {
      const s = {};
      ok.forEach(function (r) { r.variants.forEach(function (v) { if (v.bandwidth) s[v.bandwidth] = (s[v.bandwidth] || 0) + 1; }); });
      return s;
    })(),
    encryptionObserved: (function () {
      const s = {};
      ok.forEach(function (r) {
        r.variants.forEach(function (v) { /* no keys at master level */ });
      });
      const withKeys = ok.filter(function (r) { return r.variantProbe && r.variantProbe.keys && r.variantProbe.keys.length; }).length;
      const noKeys = ok.filter(function (r) { return r.variantProbe && r.variantProbe.keys && r.variantProbe.keys.length === 0; }).length;
      s.variantPlaylistsWithExtXKey = withKeys;
      s.variantPlaylistsWithoutExtXKey = noKeys;
      return s;
    })(),
    segmentProbeSummary: ok.filter(function (r) { return r.segmentProbe; }).map(function (r) {
      return {
        embedId: r.embedId,
        withReferer: r.segmentProbe.withReferer.status,
        withoutReferer: r.segmentProbe.withoutReferer.status,
        withoutRefererContentType: r.segmentProbe.withoutReferer.contentType || null,
        url: r.segmentProbe.withReferer.url
      };
    })
  };

  report.finishedAt = new Date().toISOString();
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, JSON.stringify(report, null, 2));
  log('wrote ' + outPath);
  log(JSON.stringify(report.summary, null, 2));
}

main().catch(function (e) {
  process.stderr.write('FATAL ' + (e && e.stack ? e.stack : e) + '\n');
  process.exitCode = 1;
});
