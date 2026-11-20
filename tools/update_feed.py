#!/usr/bin/env python3
"""Generate the update feed and every versioned part of the landing page.

Nothing on the page is typed by hand any more: the version, dates, file name, sizes, SHA-256 and the
structured data are all read from the GitHub releases (plus the tagged `app/build.gradle.kts` for
`versionCode`), so the page cannot drift from what is actually published.

Inputs : GitHub releases + release assets (via `gh api`)
Outputs: docs/update.json   - what the Android app polls (last MAX_FEED_RELEASES releases)
         docs/index.html    - blocks between <!-- name:start --> / <!-- name:end --> markers
         docs/sitemap.xml   - regenerated with today's lastmod

Release-body convention (everything else in the body is ignored):

    ## Headline
    A single sentence a viewer would repeat to a friend.

    ## Released
    19 Nov 2026

    ## Changes
    - NEW: something that did not exist before
    - IMPROVED: something that works better now
    - FIXED: a bug that used to bite

Sanity check without touching anything:  python3 tools/update_feed.py --selftest
Preview:                                 python3 tools/update_feed.py --dry-run
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import html
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INDEX = ROOT / "docs" / "index.html"
FEED = ROOT / "docs" / "update.json"
SITEMAP = ROOT / "docs" / "sitemap.xml"
DOWNLOAD_DIR = ROOT / "docs" / "download"

SITE = "https://1hp2die.github.io/Dr-Animo/"
REPO_URL = "https://github.com/{repo}"

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


def mb(n: int) -> str:
    return f"{n / (1024 * 1024):.1f} MB"


# ----------------------------------------------------------------------------- release parsing
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
        for chunk in FENCE.sub("", body).split("\n\n"):
            line = " ".join(chunk.split())
            if line and not line.startswith(("#", "-", "*", "|", "`")):
                headline = line
                break

    if code is None:
        print(f"warning: no versionCode at {tag}, skipping", file=sys.stderr)
        return None

    apk_url, size, asset_name, digest = "", 0, "", ""
    for asset in rel.get("assets") or []:
        if asset.get("name", "").endswith(".apk"):
            apk_url = asset["browser_download_url"]
            size = asset.get("size", 0)
            asset_name = asset.get("name", "")
            digest = asset.get("digest") or ""
            if digest.startswith("sha256:"):
                digest = digest.removeprefix("sha256:")
            break
    else:
        asset_name = f"Animo-{version}.apk"

    if not apk_url:
        apk_url = f"https://github.com/{repo}/releases/download/{tag}/{asset_name}"

    stated = " ".join(section(body, "Released").split())
    published = rel.get("published_at") or rel.get("created_at") or ""
    if stated:
        date = stated  # the changelog's own date wins over the publish timestamp
    elif published:
        date = dt.date.fromisoformat(published[:10]).strftime("%d %b %Y")
    else:
        date = ""

    return {
        "version": version,
        "versionCode": code,
        "tag": tag,
        "date": date,
        "publishedAt": published,
        "headline": headline,
        "apkUrl": apk_url,
        "apkName": asset_name,
        "sizeBytes": size,
        "sha256": digest,
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
    everything = list(releases)
    releases, total = trim_feed(releases)

    feed = {
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
            "apkUrlGithub": latest["apkUrl"],
            "apkName": latest["apkName"],
            "sizeBytes": latest["sizeBytes"],
            "sha256": latest["sha256"],
            "publishedAt": latest.get("publishedAt", ""),
            "releaseUrl": latest["releaseUrl"],
        },
        "releases": releases,
    }
    return feed, everything


# ----------------------------------------------------------------------------- asset download / mirror
def probe_asset(latest: dict) -> tuple[Path | None, int]:
    """
    Download the latest APK once and return (local path, installed footprint).

    Why download at all:
      * the installed size (sum of the entries inside the APK) is not in the API, and hard-coding
        "26.9 MB" is exactly the drift this script exists to remove;
      * the file is then mirrored onto GitHub Pages, because a release asset goes through a signed
        redirect to `release-assets.githubusercontent.com` and that hop is slow on mobile networks
        in this region. Same bytes, one hop, CDN-cached.
    The sha256 from the release API is verified before the mirror is trusted.
    """
    try:
        tmp = Path(tempfile.mkdtemp()) / "probe.apk"
        urllib.request.urlretrieve(latest["apkUrl"], tmp)

        expected = (latest.get("sha256") or "").lower()
        if expected:
            digest = hashlib.sha256(tmp.read_bytes()).hexdigest()
            if digest != expected:
                print(f"warning: sha256 mismatch on the release asset ({digest} != {expected}); "
                      f"mirror skipped", file=sys.stderr)
                return None, 0

        with zipfile.ZipFile(tmp) as zf:
            installed = sum(info.file_size for info in zf.infolist())
        return tmp, installed
    except Exception as exc:  # a network hiccup must not fail the release
        print(f"warning: could not fetch the APK: {exc}", file=sys.stderr)
        return None, 0


def mirror(apk: Path | None, latest: dict) -> str:
    """Copy the APK into docs/download so it is served by GitHub Pages, and return its URL."""
    if apk is None:
        return ""
    try:
        DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
        for stale in DOWNLOAD_DIR.glob("*.apk"):
            stale.unlink()  # only the newest build is mirrored; history stays in Releases
        target = DOWNLOAD_DIR / latest["apkName"]
        shutil.copyfile(apk, target)
        return f"{SITE}download/{latest['apkName']}"
    except Exception as exc:
        print(f"warning: mirror failed: {exc}", file=sys.stderr)
        return ""


# ----------------------------------------------------------------------------- html blocks
def replace_block(doc: str, name: str, body: str) -> str:
    start, end = f"<!-- {name}:start -->", f"<!-- {name}:end -->"
    if start not in doc or end not in doc:
        raise SystemExit(f"{INDEX} is missing the {start} / {end} markers")
    before, rest = doc.split(start, 1)
    _, after = rest.split(end, 1)
    return f"{before}{start}\n{body}\n{end}{after}"


def esc(text: str) -> str:
    return html.escape(text, quote=False)


def render_meta(latest: dict, installed: int) -> str:
    size = f" {mb(installed)} installed," if installed else ""
    return (
        f'<meta name="description" content="Download Dr Animo APK {esc(latest["version"])} for '
        f'Android — a native anime client with an offline-first catalogue and native HLS playback on '
        f'Media3/ExoPlayer.{size} No WebView, no ads, no tracking. Free, open source (MIT), '
        f'Android 7.0+. Released {esc(latest["date"])}.">'
    )


def render_meta_og(latest: dict) -> str:
    return (
        f'<meta property="og:description" content="Dr Animo APK {esc(latest["version"])}: '
        f'offline-first catalogue + native HLS playback (Media3/ExoPlayer). No WebView, no ads. '
        f'Open source, free download.">'
    )


def render_meta_tw(latest: dict) -> str:
    return (
        f'<meta name="twitter:description" content="Offline-first catalogue, native HLS playback, '
        f'no WebView. Dr Animo APK {esc(latest["version"])}, free and open source.">'
    )


def render_version_meta(latest: dict, installed: int) -> str:
    bits = [f'v{esc(latest["version"])}']
    if installed:
        bits.append(mb(installed))
    bits += ["Android 7.0+", "free", "MIT licensed", "no ads, no tracking"]
    return f'    <p class="meta">{" · ".join(bits)}</p>'


def render_cta(latest: dict, repo: str) -> str:
    sub = f'{esc(latest["apkName"])} · {mb(latest["sizeBytes"])} · tap to install'
    primary = latest.get("apkUrl") or latest["apkUrlGithub"]
    github = latest.get("apkUrlGithub") or primary
    note = ["      <!-- primary is the GitHub Pages mirror; the release asset is the fallback -->"]
    lines = note + [
        f'      <a class="btn primary" href="{esc(primary)}">',
        f'        Download Dr Animo APK {esc(latest["version"])}<small>{sub}</small>',
        "      </a>",
        f'      <a class="btn" href="{REPO_URL.format(repo=repo)}/releases">Releases page</a>',
    ]
    if primary == github:
        lines = lines[1:]  # no mirror in play, no need to explain it
    return "\n".join(lines)


def render_sha(latest: dict) -> str:
    sha = latest.get("sha256") or ""
    if not sha:
        return '    <p class="hash">Download the APK from the release page to read its SHA-256.</p>'
    return (
        f'    <p class="hash">SHA-256 <code>{esc(sha)}</code></p>'
    )


def render_download_table(latest: dict, installed: int) -> str:
    rows = [
        ("Version", f'{esc(latest["version"])} (versionCode {latest["versionCode"]}) · '
                    f'{esc(latest["date"])}'),
        ("File", f'<a href="{esc(latest.get("apkUrl") or latest["apkUrlGithub"])}">'
                 f'{esc(latest["apkName"])}</a> '
                 f'({mb(latest["sizeBytes"])} download'
                 + (f', {mb(installed)} installed' if installed else "") + ")"),
        ("Mirror", f'<a href="{esc(latest["apkUrlGithub"])}">GitHub Releases</a>'),
        ("Package", "<code>com.animo.app</code>"),
        ("Requires", "Android 7.0 (API 24) or newer"),
        ("License", "MIT"),
    ]
    if latest.get("sha256"):
        rows.append(("SHA-256", f'<code>{esc(latest["sha256"])}</code>'))
    body = "\n".join(f"        <tr><th>{k}</th><td>{v}</td></tr>" for k, v in rows)
    return f"      <table>\n{body}\n      </table>"


def render_install_cmds(latest: dict) -> str:
    name = esc(latest["apkName"])
    return f"      <pre>sha256sum {name}</pre>"


def render_ld_app(latest: dict, installed: int, repo: str) -> str:
    app = {
        "@type": "SoftwareApplication",
        "@id": f"{SITE}#app",
        "name": "Dr Animo",
        "alternateName": ["Dr Animo APK", "Animo", "Dr Animo Android"],
        "applicationCategory": "MultimediaApplication",
        "applicationSubCategory": "Anime streaming client",
        "operatingSystem": "Android 7.0 and newer",
        "softwareVersion": latest["version"],
        "softwareRequirements": "Android 7.0 (API 24)+",
        "downloadUrl": latest["apkUrl"],
        "installUrl": f"{REPO_URL.format(repo=repo)}/releases/latest",
        "codeRepository": REPO_URL.format(repo=repo),
        "license": "https://opensource.org/licenses/MIT",
        "isAccessibleForFree": True,
        "datePublished": latest.get("publishedAt") or "",
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD",
                   "availability": "https://schema.org/InStock"},
        "featureList": [
            "Native HLS playback with Media3/ExoPlayer",
            "Offline-first catalogue in Room",
            "No WebView renderer",
            "Local full-text search",
            "Material 3 Compose UI",
        ],
        "author": {"@type": "Organization", "name": "1HP2DIE",
                   "url": "https://github.com/1HP2DIE"},
        "screenshot": f"{SITE}img/og.jpg",
    }
    if latest.get("sizeBytes"):
        app["fileSize"] = f'{latest["sizeBytes"]}'
    if installed:
        app["installSize"] = f"{installed}"
    document = {"@context": "https://schema.org", "@graph": [app]}
    return (
        '<script type="application/ld+json">\n'
        + json.dumps(document, indent=2, ensure_ascii=False)
        + "\n</script>"
    )


def html_versions(releases: list[dict], latest: dict) -> str:
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


def render_page(page: str, feed: dict, everything: list[dict], installed: int) -> str:
    latest = feed["latest"]
    blocks = {
        "meta": render_meta(latest, installed),
        "meta-og": render_meta_og(latest),
        "meta-tw": render_meta_tw(latest),
        "ld:app": render_ld_app(latest, installed, feed["repo"]),
        "version-meta": render_version_meta(latest, installed),
        "cta": render_cta(latest, feed["repo"]),
        "sha": render_sha(latest),
        "download-table": render_download_table(latest, installed),
        "install-cmds": render_install_cmds(latest),
        "versions": html_versions(everything, latest),
    }
    for name, body in blocks.items():
        page = replace_block(page, name, body)
    return page


def render_sitemap() -> str:
    today = dt.datetime.now(dt.timezone.utc).date().isoformat()
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
        "  <url>\n"
        f"    <loc>{SITE}</loc>\n"
        f"    <lastmod>{today}</lastmod>\n"
        "    <changefreq>weekly</changefreq>\n"
        "    <priority>1.0</priority>\n"
        "  </url>\n"
        "</urlset>\n"
    )


# ----------------------------------------------------------------------------- main
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
    apk, installed = probe_asset(feed["latest"])
    fast_url = mirror(apk, feed["latest"])
    if fast_url:
        # The app and the buttons both use the mirrored URL; GitHub stays as the fallback.
        feed["latest"]["apkUrlGithub"] = feed["latest"]["apkUrl"]
        feed["latest"]["apkUrl"] = fast_url
    page = render_page(INDEX.read_text(), feed, everything, installed)

    if args.dry_run:
        print(json.dumps(feed["latest"], indent=2))
        print(f"installed size: {installed} bytes ({mb(installed)})")
        print(f"mirror: {fast_url or '(none — GitHub only)'}")
        print(f"{len(feed['releases'])}/{feed['totalReleases']} releases kept in the feed "
              f"(limit {feed['releaseLimit']}), {len(everything)} in the page history")
        return 0

    FEED.write_text(json.dumps(feed, indent=2) + "\n")
    INDEX.write_text(page)
    SITEMAP.write_text(render_sitemap())
    print(
        f"wrote update.json ({len(feed['releases'])}/{feed['totalReleases']} releases), "
        f"index.html ({len(everything)} in the history, v{feed['latest']['version']}) and sitemap.xml"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
