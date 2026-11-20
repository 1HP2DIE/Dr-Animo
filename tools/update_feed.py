#!/usr/bin/env python3
"""Generate the update feed for the landing page and the app.

Inputs : GitHub releases (via `gh api`), plus each release tag's
         `app/build.gradle.kts` for the real versionName/versionCode.
Outputs: docs/update.json   - what the Android app polls
         docs/index.html    - the static "Version history" block, replaced
                              between <!-- versions:start --> and <!-- versions:end -->

Release-body convention (everything else in the body is ignored):

    ## Headline
    A single sentence a viewer would repeat to a friend.

    ## Changes
    - NEW: something that did not exist before
    - IMPROVED: something that works better now
    - FIXED: a bug that used to bite

Bullets outside the `## Changes` section and anything inside fenced code
blocks are ignored, so install snippets never turn into changelog entries.

Usage:
    python3 tools/update_feed.py --repo 1HP2DIE/Dr-Animo [--dry-run]
"""

from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INDEX = ROOT / "docs" / "index.html"
FEED = ROOT / "docs" / "update.json"

KIND_ORDER = {"NEW": 0, "IMPROVED": 1, "FIXED": 2}

# Rule: the feed carries only the last few releases. The app only ever needs `latest` to decide
# whether to prompt, and the newest few to render "what changed since your build" — shipping the
# whole history would make a file every installed device downloads grow forever. The landing page
# keeps the full history: it is HTML, read by people, and is where the archive belongs.
MAX_FEED_RELEASES = 5
FENCE = re.compile(r"```.*?```", re.S)
SECTION = re.compile(r"^##\s+(.+?)\s*$", re.M)
BULLET = re.compile(r"^\s*[-*]\s*(NEW|IMPROVED|FIXED)\s*:\s*(.+?)\s*$", re.I | re.M)


def run(*args: str) -> str:
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout


def gradle_version(tag: str) -> tuple[str | None, int | None]:
    """versionName/versionCode as of the tagged commit — never guessed from the tag text."""
    try:
        text = run("git", "show", f"{tag}:app/build.gradle.kts")
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None, None
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    return (name.group(1) if name else None), (int(code.group(1)) if code else None)


def section(body: str, title: str) -> str:
    """Text of one `## title` section, fenced code stripped."""
    body = FENCE.sub("", body)
    parts = SECTION.split(body)
    # parts = [preamble, title1, body1, title2, body2, ...]
    for i in range(1, len(parts) - 1, 2):
        if parts[i].strip().lower() == title.lower():
            return parts[i + 1].strip()
    return ""


def parse_release(rel: dict, repo: str) -> dict | None:
    tag = rel["tag_name"]
    body = rel.get("body") or ""
    name, code = gradle_version(tag)
    version = name or tag.lstrip("v")

    changes: list[dict] = []
    block = section(body, "Changes") or body
    for kind, text in BULLET.findall(block):
        changes.append({"kind": kind.upper(), "text": text.strip()})
    changes.sort(key=lambda c: KIND_ORDER.get(c["kind"], 9))

    headline = " ".join(section(body, "Headline").split())
    if not headline:
        # Fallback: first prose paragraph that is not a heading or bullet.
        for chunk in FENCE.sub("", body).split("\n\n"):
            line = " ".join(chunk.split())
            if line and not line.startswith(("#", "-", "*", "|", "`")):
                headline = line
                break

    if code is None:
        print(f"warning: no versionCode at {tag}, skipping", file=sys.stderr)
        return None

    apk_url = ""
    for asset in rel.get("assets") or []:
        if asset.get("name", "").endswith(".apk"):
            apk_url = asset["browser_download_url"]
            size = asset.get("size", 0)
            break
    else:
        size = 0

    if not apk_url:
        apk_url = f"https://github.com/{repo}/releases/download/{tag}/Animo-{version}.apk"

    published = rel.get("published_at") or rel.get("created_at") or ""
    date = ""
    stated = " ".join(section(body, "Released").split())
    if stated:
        date = stated  # the changelog's own date wins over the publish timestamp
    elif published:
        date = dt.date.fromisoformat(published[:10]).strftime("%d %b %Y")

    return {
        "version": version,
        "versionCode": code,
        "tag": tag,
        "date": date,
        "publishedAt": published,
        "headline": headline,
        "apkUrl": apk_url,
        "sizeBytes": size,
        "releaseUrl": rel.get("html_url", ""),
        "changes": changes,
    }


def trim_feed(releases: list[dict]) -> tuple[list[dict], int]:
    """Newest-first list, cut to [MAX_FEED_RELEASES]. Returns (kept, total)."""
    return releases[:MAX_FEED_RELEASES], len(releases)


def build_feed(repo: str) -> tuple[dict, list[dict]]:
    raw = json.loads(
        run(
            "gh", "api",
            f"repos/{repo}/releases?per_page=100",
            "-H", "Accept: application/vnd.github+json",
        )
    )
    releases = []
    for rel in raw:
        if rel.get("draft"):
            continue
        parsed = parse_release(rel, repo)
        if parsed:
            releases.append(parsed)
    releases.sort(key=lambda r: r["versionCode"], reverse=True)

    if not releases:
        raise SystemExit("no releases with a versionCode found")

    latest = releases[0]
    # The feed is cut to the last few releases; the landing page keeps the whole archive.
    everything = list(releases)
    # Newest first, then cut. `latest` is taken before the cut so it can never be dropped.
    releases, total = trim_feed(releases)
    return {
        "schema": 1,
        "generated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "repo": repo,
        "releaseLimit": MAX_FEED_RELEASES,
        "totalReleases": total,
        "latest": {
            "version": latest["version"],
            "versionCode": latest["versionCode"],
            "date": latest["date"],
            "headline": latest["headline"],
            "apkUrl": latest["apkUrl"],
            "sizeBytes": latest["sizeBytes"],
            "releaseUrl": latest["releaseUrl"],
        },
        "releases": releases,
    }, everything


def esc(text: str) -> str:
    return html.escape(text, quote=False)


def html_block(releases: list[dict], latest: dict) -> str:
    rows = []
    for rel in releases:
        badge = ' <span class="pill">latest</span>' if rel["version"] == latest["version"] else ""
        items = "".join(
            f'<li class="k-{c["kind"].lower()}"><span class="kind">{c["kind"]}</span>{esc(c["text"])}</li>'
            for c in rel["changes"]
        )
        rows.append(
            f'<article class="rel">'
            f'<header><h3>v{esc(rel["version"])}{badge}</h3>'
            f'<span class="date">{esc(rel["date"])}</span></header>'
            f'<p class="headline">{esc(rel["headline"])}</p>'
            f'<ul class="changes">{items}</ul>'
            f'<a class="dllink" href="{esc(rel["apkUrl"])}">Download v{esc(rel["version"])} APK</a>'
            f"</article>"
        )
    return "\n".join(rows)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "1HP2DIE/Dr-Animo"))
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--selftest", action="store_true",
                    help="check the 5-release cap without touching GitHub or the working tree")
    args = ap.parse_args()

    if args.selftest:
        fake = [{"version": f"1.{i}.0", "versionCode": i} for i in range(12, 3, -1)]
        kept, total = trim_feed(fake)
        assert total == 9, total
        assert len(kept) == MAX_FEED_RELEASES, len(kept)
        assert kept[0]["versionCode"] == 12, "newest must survive the cut"
        assert [r["versionCode"] for r in kept] == [12, 11, 10, 9, 8], kept
        print(f"selftest ok: {total} releases in, {len(kept)} kept, newest {kept[0]['version']}")
        return 0

    feed, everything = build_feed(args.repo)
    block = html_block(everything, feed["latest"])

    page = INDEX.read_text()
    start, end = "<!-- versions:start -->", "<!-- versions:end -->"
    if start not in page or end not in page:
        raise SystemExit(f"{INDEX} is missing the {start} / {end} markers")
    before, rest = page.split(start, 1)
    _, after = rest.split(end, 1)
    page = f"{before}{start}\n{block}\n{end}{after}"

    if args.dry_run:
        print(json.dumps(feed["latest"], indent=2))
        print(f"{len(feed['releases'])}/{feed['totalReleases']} releases kept in the feed "
              f"(limit {feed['releaseLimit']}), {len(everything)} in the page history")
        return 0

    FEED.write_text(json.dumps(feed, indent=2) + "\n")
    INDEX.write_text(page)
    print(f"wrote {FEED.relative_to(ROOT)} and refreshed {INDEX.relative_to(ROOT)} "
          f"({len(feed['releases'])}/{feed['totalReleases']} releases in the feed, "
          f"latest {feed['latest']['version']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
