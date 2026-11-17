#!/usr/bin/env python3
"""Build research/summary.json from the already-crawled research/dataset/*.json (no network)."""
import json, pathlib, time

ROOT = pathlib.Path(__file__).resolve().parent
DS = ROOT / "dataset"

rows = []
for p in (1, 2, 3):
    rows.extend(json.loads((DS / f"recent_p{p}.json").read_text())["data"])

series = {}
for f in sorted(DS.glob("series_*.json")):
    j = json.loads(f.read_text())
    if j and j.get("ok"):
        series[int(f.stem.split("_")[1])] = j["data"]

keys = sorted({k for r in rows for k in r})
fields = {}
for k in keys:
    present = [r for r in rows if k in r]
    types = {}
    for r in rows:
        v = r.get(k, "<MISSING>")
        t = ("missing" if v == "<MISSING>" else "null" if v is None else
             "array" if isinstance(v, list) else "object" if isinstance(v, dict) else
             "boolean" if isinstance(v, bool) else "int" if isinstance(v, int) else
             "float" if isinstance(v, float) else "string")
        types[t] = types.get(t, 0) + 1
    nums = []
    for r in present:
        v = r[k]
        if isinstance(v, bool):
            continue
        if isinstance(v, (int, float)):
            nums.append(float(v))
        elif isinstance(v, str):
            try:
                nums.append(float(v))
            except ValueError:
                pass
    fields[k] = {
        "present_pct": round(100 * len(present) / len(rows), 1),
        "missing_pct": round(100 * (len(rows) - len(present)) / len(rows), 1),
        "types": types,
        "empty_string_pct": round(100 * sum(1 for r in present if r[k] == "") / len(rows), 1),
        "distinct": len({json.dumps(r[k], sort_keys=True) for r in present}),
        "numeric": ({"min": min(nums), "max": max(nums), "avg": round(sum(nums) / len(nums), 4)}
                    if nums else None),
    }

eps_total = sub = dub = 0
hosts, zero_ep, short, exact, over, noded = set(), [], [], [], [], []
dupnums, gaps, unsorted, ep_keys, embed_keys = [], [], [], set(), set()
for sid, d in series.items():
    a, eps = d["anime"], d.get("episodes") or []
    eps_total += len(eps)
    if not eps:
        zero_ep.append(sid)
    for e in eps:
        ep_keys.update(e.keys())
        u = e.get("embed_url") or {}
        embed_keys.update(u.keys())
        if u.get("sub"):
            sub += 1
            hosts.add(u["sub"].split("/")[2])
        if u.get("dub"):
            dub += 1
    nums = [e["number"] for e in eps]
    if len(set(nums)) != len(nums):
        dupnums.append(sid)
    if nums != sorted(nums):
        unsorted.append(sid)
    if nums and nums != list(range(nums[0], nums[0] + len(nums))):
        gaps.append(sid)
    dec = (a.get("episodes") or "").strip()
    if not dec:
        noded.append(sid)
    elif int(dec) == len(eps):
        exact.append(sid)
    elif int(dec) > len(eps):
        short.append(sid)
    else:
        over.append(sid)

summary = {
    "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "source": {"base": "https://anikotoapi.site",
               "endpoints": ["/recent-anime", "/series/{id}"],
               "domains": json.loads((DS / "recent_p1.json").read_text()).get("anikoto_domains")},
    "rate_limit": {"limit": 60, "window_s": 120, "sustained_rps": 0.5,
                   "safe_gap_s": 2.0, "observed_429": False},
    "catalog": {"total": 9404, "per_page_max": 100, "total_pages": 95,
                "sampled_rows": len(rows)},
    "anime_fields": fields,
    "terms_by_type": {
        k: {"rows_present": sum(1 for r in rows if k in (r.get("terms_by_type") or {})),
            "distinct": len({v for r in rows for v in (r.get("terms_by_type") or {}).get(k, [])}),
            "top": sorted(((v, sum(1 for r in rows if v in (r.get("terms_by_type") or {}).get(k, [])))
                           for v in {v for r in rows for v in (r.get("terms_by_type") or {}).get(k, [])}),
                          key=lambda t: -t[1])[:15]}
        for k in ("type", "genre", "studios", "producers")},
    "episodes": {
        "series_sampled": len(series), "episodes_total": eps_total,
        "with_sub": sub, "with_dub": dub,
        "dub_coverage_pct": round(100 * dub / eps_total, 1) if eps_total else 0,
        "hsub_seen": "hsub" in embed_keys,
        "episode_keys": sorted(ep_keys), "embed_keys": sorted(embed_keys),
        "hosts": sorted(hosts),
        "zero_episode_series": zero_ep,
        "declared_exact": exact, "declared_short": short, "declared_over": over,
        "declared_absent": noded,
        "duplicate_numbers": dupnums, "gaps": gaps, "not_ascending": unsorted,
    },
    "sync": {"catalog_requests": 95, "catalog_payload_bytes_est": 95 * 123859,
             "catalog_seconds_at_0_5rps": 190, "backfill_requests": 9404,
             "backfill_seconds_at_0_5rps": 18808},
}
(ROOT / "summary.json").write_text(json.dumps(summary, indent=2))
print(f"rows={len(rows)} series={len(series)} episodes={eps_total}")
print(f"sub={sub} dub={dub} hosts={sorted(hosts)} hsub={'hsub' in embed_keys}")
print(f"zero_episode={len(zero_ep)} short={len(short)} exact={len(exact)} absent={len(noded)}")
print(f"wrote {ROOT / 'summary.json'} ({len(json.dumps(summary))} bytes)")
print("is_dub present:", fields['is_dub']['present_pct'], "% | episodes field types:", fields['episodes']['types'])
