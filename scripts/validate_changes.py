#!/usr/bin/env python3
"""Validate change-request (CR) metadata + the CR↔doc links.

Catches the drift classes cr-0038 cleaned by hand: a duplicate CR number, a filename that disagrees with
its `id:`, a `satisfies:`/`adrs:` pointing at a doc that doesn't exist, and — the subtle one — ADR ids
written unquoted. In YAML a bare `0013` is parsed as **octal 11** and `0009` as a string, so unquoted ADR
ids silently mean the wrong (or no) ADR; they must be quoted 4-digit strings (`adrs: ["0013"]`).

Checks per `changes/cr-NNNN-slug.md`:
  - the NNNN in the filename is unique across all CRs
  - frontmatter `id` equals `cr-NNNN` (matches the filename)
  - `status` is one of planned | in-progress | done
  - every `satisfies:` id resolves to a use-case (docs/features/*/use-cases/uc.<id>.md), an
    enabler (docs/features/*/enablers/en.<id>.md), or a cross-cutting concern
    (docs/crosscutting/cc.<id>.md)
  - every `adrs:` id is a quoted 4-digit string AND resolves to docs/decisions/NNNN-*.md
"""
import pathlib
import re
import sys

try:
    import yaml
except ImportError:
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHANGES = ROOT / "changes"
FEATURES = ROOT / "docs" / "features"
DECISIONS = ROOT / "docs" / "decisions"
CROSSCUTTING = ROOT / "docs" / "crosscutting"

# `superseded` = the work landed and was then undone, or its premise fell away (cr-0169: the guard it built was
# deleted the same day, obsoleted by a format change). Without it the only honest options are to lie (`done`)
# or to erase the record — and the record of a wrong turn is the part worth keeping.
KNOWN_STATUS = {"planned", "in-progress", "done", "superseded"}

errors: list[str] = []


def frontmatter(text: str) -> dict:
    if not text.startswith("---"):
        return {}
    end = text.find("\n---", 3)
    if end == -1:
        return {}
    return yaml.safe_load(text[3:end]) or {}


# a CR satisfies a use-case (uc.*), an enabler (en.*), or a cross-cutting concern (cc.*) — all three are
# first-class trust-chain targets a CR can close (a cc.* concern has no end-user goal but is still tracked).
satisfiable = ({p.stem for p in FEATURES.glob("*/use-cases/uc.*.md")}
               | {p.stem for p in FEATURES.glob("*/enablers/en.*.md")}
               | {p.stem for p in CROSSCUTTING.glob("cc.*.md")})
adr_ids = {m.group(1) for p in DECISIONS.glob("*.md") if (m := re.match(r"(\d{4})-", p.name))}

seen: dict[str, str] = {}
for cr in sorted(CHANGES.glob("cr-*.md")):
    m = re.match(r"cr-(\d{4})-", cr.name)
    if not m:
        errors.append(f"{cr.name}: filename must match cr-NNNN-slug.md")
        continue
    num = m.group(1)
    where = cr.name
    if num in seen:
        errors.append(f"{where}: CR number {num} collides with {seen[num]}")
    seen[num] = cr.name

    data = frontmatter(cr.read_text())
    if not data:
        errors.append(f"{where}: missing or malformed YAML frontmatter")
        continue

    if data.get("id") != f"cr-{num}":
        errors.append(f"{where}: id '{data.get('id')}' must equal 'cr-{num}' (the filename)")

    status = data.get("status")
    if status not in KNOWN_STATUS:
        errors.append(f"{where}: status '{status}' must be one of {sorted(KNOWN_STATUS)}")

    for sat in data.get("satisfies") or []:
        if sat not in satisfiable:
            errors.append(f"{where}: satisfies '{sat}' resolves to no use-case, enabler or concern doc")

    for adr in data.get("adrs") or []:
        if not isinstance(adr, str):
            errors.append(f"{where}: adr {adr!r} must be a quoted 4-digit string — bare YAML mangles it "
                          f"(0013→octal 11, 0009→str). Write adrs: [\"0013\"].")
            continue
        if not re.fullmatch(r"\d{4}", adr):
            errors.append(f"{where}: adr '{adr}' must be a 4-digit id")
        elif adr not in adr_ids:
            errors.append(f"{where}: adrs '{adr}' resolves to no ADR in docs/decisions/")

if errors:
    print("CHANGE-REQUEST VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"changes OK: {len(seen)} CR(s), all numbers unique, ids/satisfies/adrs resolve")
