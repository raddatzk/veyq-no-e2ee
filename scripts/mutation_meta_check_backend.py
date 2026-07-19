#!/usr/bin/env python3
"""Per-glob non-empty meta-check for the backend mutation gate (ADR 0045 §4).

PIT's `failWhenNoMutations=true` already fails the build when the *whole* scope mutates nothing. This guards the
one drift that leaves behind: **every `targetClasses` glob in `pitest.scope.json` must match > 0 mutations in
PIT's report.** A policy renamed while its glob is left stale (or a typo'd glob) then matches *nothing* — PIT
mutates it away silently, `failWhenNoMutations` stays green because the sibling policies carry the run, and the
renamed class loses its mutation coverage unnoticed (ai/README.md rule 3). This asserts each declared scope
entry is individually non-empty, which is the exact delta over `failWhenNoMutations` (the global-empty case is
already its job, so this does NOT re-check that).

Deliberately diff-free — a stale glob points at nothing regardless of what a given PR changed, so the invariant
holds on every run, not only when the diff intersects the scope. It reads the SAME manifest the `pitest{}` build
block consumes (backend/pitest.scope.json), so the mutator and this guard never disagree on WHAT is in scope.

Not a `validate_*.py`: the trust-chain job runs those on every push with no build, but this needs PIT's
`mutations.xml`, which only the mutation CI job produces — so it is invoked from that job, not the glob. It is a
permanent guard all the same, standing alongside the build's `mutationThreshold`. Runnable locally after a
`./gradlew pitest`:

    python3 scripts/mutation_meta_check_backend.py
"""
import fnmatch
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
BACKEND = ROOT / "backend"
MANIFEST = BACKEND / "pitest.scope.json"
REPORT = BACKEND / "build" / "reports" / "pitest" / "mutations.xml"


def scope_globs() -> list[str]:
    """The PIT class-name globs the build mutates — the single source of truth for WHAT is in scope."""
    return json.loads(MANIFEST.read_text())["targetClasses"]


def mutation_count(glob: str, root: ET.Element) -> int:
    return sum(
        1 for m in root.findall("mutation")
        if fnmatch.fnmatchcase((m.findtext("mutatedClass") or ""), glob)
    )


def main() -> int:
    if not REPORT.exists():
        print(f"::error::meta-check: no PIT report at {REPORT} — run `./gradlew pitest` first (the gate ran nothing).")
        return 1

    root = ET.parse(REPORT).getroot()
    failed = False
    for glob in scope_globs():
        count = mutation_count(glob, root)
        if count == 0:
            failed = True
            print(f"::error::meta-check: scope glob '{glob}' matched 0 mutations — it points at nothing (a renamed "
                  f"class, or a typo) while its siblings carry the run, so failWhenNoMutations stayed green. Fix "
                  f"backend/pitest.scope.json or the class name (ADR 0045 §4).")
        else:
            print(f"meta-check: scope glob '{glob}' has {count} mutations (> 0). OK.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
