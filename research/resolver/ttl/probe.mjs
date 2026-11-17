const M = "https://fetch.nexabloom.top/anime/450fa8c229cb0c4caf6bd73c427d3244/b4480b0b853a094c9d8ade21e5e1708f/master.m3u8";
const H = { Referer: "https://megaplay.buzz/", "User-Agent": "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36" };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const t0 = Date.now();
async function probe(label) {
  const t = ((Date.now() - t0) / 1000).toFixed(0);
  try {
    const r = await fetch(M, { headers: H });
    const body = await r.text();
    let variant = null, seg = null;
    const v = body.split("\n").find(l => l && !l.startsWith("#"));
    if (v) {
      const vu = new URL(v, M).toString();
      const vr = await fetch(vu, { headers: H });
      const vb = await vr.text();
      const s = vb.split("\n").find(l => l && !l.startsWith("#"));
      if (s) seg = new URL(s, vu).toString();
      variant = `variant ${vr.status} (${vb.split("\n").length} lines)`;
    }
    let segRes = "n/a";
    if (seg) { const sr = await fetch(seg, { headers: { ...H, Range: "bytes=0-1023" } }); segRes = `segment ${sr.status} ct=${sr.headers.get("content-type")}`; }
    console.log(`[+${t}s] ${label}: master ${r.status} ct=${r.headers.get("content-type")} len=${body.length} | ${variant} | ${segRes}`);
  } catch (e) { console.log(`[+${t}s] ${label}: ERROR ${e.message}`); }
}
await probe("t0");
await sleep(120000); await probe("t+2m");
await sleep(180000); await probe("t+5m");
const v = await fetch(M, { headers: {} }); console.log(`no-referer after TTL: ${v.status}`);
