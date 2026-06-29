#!/usr/bin/env python3
"""Validate that every relative link in the docs tree resolves to a real file.

The reliable, false-positive-free half of "are the docs (incl. ADRs) still correct?": an ADR or use-case
that links to a moved/renamed/deleted file is unambiguously broken (unlike fuzzy prose-drift, which a
machine can't judge). Catches the class behind cr-0038's ADR 0001 finding (a doc pointing at a path that
no longer exists).

Scans every `*.md` under `docs/` for inline markdown links + images `[text](target)` / `![alt](target)`:
  - external (http/https/mailto/tel) and pure in-page anchors (`#section`) are skipped
  - a `#fragment` and a "title" suffix are stripped before resolving
  - the remainder is resolved relative to the linking file and must exist (file or directory)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
SKIP_SCHEME = ("http://", "https://", "mailto:", "tel:")

errors: list[str] = []


def targets(text: str):
    for m in LINK.finditer(text):
        raw = m.group(1).strip()
        # drop an optional "title": [x](path "the title")
        target = raw.split()[0] if raw else raw
        target = target.strip("<>")
        if not target or target.startswith("#") or target.startswith(SKIP_SCHEME):
            continue
        yield target.split("#", 1)[0]  # strip any in-page anchor


for md in sorted(DOCS.rglob("*.md")):
    for target in targets(md.read_text()):
        if not target:
            continue
        resolved = (md.parent / target).resolve()
        if not resolved.exists():
            errors.append(f"{md.relative_to(ROOT)}: dead link → {target}")

if errors:
    print("DOC LINK VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

n = sum(1 for _ in DOCS.rglob("*.md"))
print(f"doc links OK: all relative links in {n} doc(s) resolve")
