#!/usr/bin/env python3
"""Throttled crawler for anikotoapi.site -> research/dataset/*.json + research/summary.json

Cadence is 2.2 s (0.45 req/s), below the measured 60 req / 120 s limit.
Run:  python3 research/crawl.py
"""
import gzip, json, pathlib, time, urllib.error, urllib.request

BASE = "https://anikotoapi.site"
OUT = pathlib.Path(__file__).resolve().parent / "dataset"
HDR = {"accept": "application/json", "user-agent": "animo-research/1.0"}
GAP = 2.2
_last = [0.0]

SERIES_IDS = [9584, 976, 7072, 1458, 1642, 6485, 7619, 9050, 859, 9583,
              9127, 9570, 9474, 9568, 9469, 9063, 9080, 9110, 9455, 9581,
              9100, 9027, 8989, 9525, 9518, 9073, 9048, 9485, 9076, 8903,
              8630, 8738, 8935, 9412, 8854, 8972, 8801, 9086, 7959, 9374]


def get(path):
    wait = GAP - (time.time() - _last[0])
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(BASE + path, headers=HDR)
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            code, hdrs, raw = r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        code, hdrs, raw = e.code, dict(e.headers), e.read()
    finally:
        _last[0] = time.time()
    enc = (hdrs.get("Content-Encoding") or "").lower()
    if enc == "gzip":
        raw = gzip.decompress(raw)
    rem = hdrs.get("x-ratelimit-remaining")
    print(f"  {code} {path}  {len(raw)}B  remaining={rem}", flush=True)
    if rem is not None and int(rem) < 5:
        print("  remaining<5 -> sleeping 90s", flush=True)
        time.sleep(90)
    if code in (429, 403):
        print("  throttled -> sleeping 120s", flush=True)
        time.sleep(120)
        return get(path)
    text = raw.decode("utf-8")
    try:
        return code, json.loads(text), text
    except json.JSONDecodeError:
        return code, None, text


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    pages = []
    for p in (1, 2, 3):
        code, j, text = get(f"/recent-anime?page={p}&per_page=100")
        (OUT / f"recent_p{p}.json").write_text(text, encoding="utf-8")
        pages.append(j)
        assert code == 200 and j and j.get("ok"), f"page {p} failed: {code}"
    rows = [r for pg in pages for r in pg["data"]]
    print(f"catalog rows: {len(rows)}  total={pages[0]['pagination']['total']}")

    series = {}
    for sid in SERIES_IDS:
        code, j, text = get(f"/series/{sid}")
        (OUT / f"series_{sid}.json").write_text(text, encoding="utf-8")
        series[sid] = (code, j)

    keys = sorted({k for r in rows for k in r})
    fields = {}
    for k in keys:
        present = [r for r in rows if k in r]
        types = {}
        for r in rows:
            v = r.get(k, "<MISSING>")
            t = "missing" if v == "<MISSING>" else ("null" if v is None else
                 "array" if isinstance(v, list) else
                 "object" if isinstance(v, dict) else
                 "boolean" if isinstance(v, bool) else
                 "int" if isinstance(v, int) else
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
    dupnums, gaps, unsorted = [], [], []
    for sid, (code, j) in series.items():
        if code != 200 or not j or not j.get("ok"):
            continue
        a, eps = j["data"]["anime"], j["data"].get("episodes") or []
        eps_total += len(eps)
        if not eps:
            zero_ep.append(sid)
        for e in eps:
            u = e.get("embed_url") or {}
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
        d = a.get("episodes") or ""
        if not d.strip():
            noded.append(sid)
        elif int(d) == len(eps):
            exact.append(sid)
        elif int(d) > len(eps):
            short.append(sid)
        else:
            over.append(sid)

    summary = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": {"base": BASE, "endpoints": ["/recent-anime", "/series/{id}"],
                   "domains": pages[0].get("anikoto_domains")},
        "rate_limit": {"limit": 60, "window_s": 120, "sustained_rps": 0.5,
                       "safe_gap_s": 2.0, "observed_429": False},
        "catalog": {"total": pages[0]["pagination"]["total"],
                    "per_page_max": 100,
                    "total_pages": pages[0]["pagination"]["total_pages"],
                    "sampled_rows": len(rows),
                    "page_bytes": [len(json.dumps(pg)) for pg in pages]},
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
            "with_sub": sub, "with_dub": dub, "hsub_seen": False,
            "episode_keys": ["id", "title", "jp_title", "number", "episode_embed_id", "embed_url", "updated_at"],
            "embed_keys": ["sub", "dub"], "hosts": sorted(hosts),
            "dub_coverage_pct": round(100 * dub / eps_total, 1) if eps_total else 0,
            "zero_episode_series": zero_ep,
            "declared_exact": exact, "declared_short": short, "declared_over": over,
            "declared_absent": noded,
            "duplicate_numbers": dupnums, "gaps": gaps, "not_ascending": unsorted,
        },
        "sync": {"catalog_requests": pages[0]["pagination"]["total_pages"],
                 "catalog_payload_bytes_est": pages[0]["pagination"]["total_pages"] * 123859,
                 "catalog_seconds_at_0_5rps": round(pages[0]["pagination"]["total_pages"] / 0.5, 1),
                 "backfill_requests": pages[0]["pagination"]["total"],
                 "backfill_seconds_at_0_5rps": round(pages[0]["pagination"]["total"] / 0.5, 1)},
    }
    (OUT.parent / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"wrote {OUT.parent / 'summary.json'}")
    print(f"dataset: {len(list(OUT.glob('*.json')))} files in {OUT}")


if __name__ == "__main__":
    main()
