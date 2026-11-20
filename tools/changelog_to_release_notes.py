#!/usr/bin/env python3
"""Turn the in-app changelog (Kotlin) into a release body in the feed's convention.

`app/src/main/java/com/animo/app/ui/changelog/Changelog.kt` is the user-facing copy of record,
so the GitHub release bodies are generated from it instead of being retyped (and drifting).

    python3 tools/changelog_to_release_notes.py 1.8.0     # one version to stdout
    python3 tools/changelog_to_release_notes.py --all      # every version, separated

Output shape (see tools/update_feed.py):

    ## Headline
    ...
    ## Changes
    - NEW: ...
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/animo/app/ui/changelog/Changelog.kt"

RELEASE = re.compile(
    r'Release\(\s*version\s*=\s*"([^"]+)",\s*date\s*=\s*"([^"]+)",\s*'
    r'headline\s*=\s*"((?:[^"\\]|\\.)*)",\s*changes\s*=\s*listOf\((.*?)\n\s*\),',
    re.S,
)
CHANGE = re.compile(r'Change\(\s*ChangeKind\.(NEW|IMPROVED|FIXED)\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)', re.S)


def unescape(text: str) -> str:
    return text.replace('\\"', '"').replace("\\n", " ").strip()


def parse() -> list[dict]:
    src = KT.read_text()
    out = []
    for version, date, headline, body in RELEASE.findall(src):
        changes = [{"kind": k, "text": unescape(t)} for k, t in CHANGE.findall(body)]
        out.append({"version": version, "date": date, "headline": unescape(headline), "changes": changes})
    return out


def render(rel: dict, apk: str | None = None, size: str | None = None) -> str:
    lines = ["## Headline", rel["headline"], "", "## Released", rel["date"], "", "## Changes"]
    for change in rel["changes"]:
        lines.append(f'- {change["kind"]}: {change["text"]}')
    lines += ["", "## Install", "```bash", f"adb install -r {apk or 'Animo-' + rel['version'] + '.apk'}", "```"]
    if size:
        lines += ["", f"Package `com.animo.app` · {size} installed · Android 7.0+."]
    return "\n".join(lines)


def main() -> int:
    releases = parse()
    if not releases:
        print("could not parse Changelog.kt", file=sys.stderr)
        return 1
    if "--all" in sys.argv:
        for rel in releases:
            print(f"===== {rel['version']} =====")
            print(render(rel))
            print()
        return 0
    want = [a for a in sys.argv[1:] if not a.startswith("-")]
    if not want:
        want = [releases[0]["version"]]
    for version in want:
        match = next((r for r in releases if r["version"] == version), None)
        if not match:
            print(f"no release {version} in Changelog.kt", file=sys.stderr)
            return 2
        print(render(match))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
