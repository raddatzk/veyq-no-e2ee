#!/usr/bin/env python3
"""Validate the feature/use-case -> coverage trust chain.

Checks that every use-case doc has a coverage file, that each is well-formed (every acceptance
criterion is covered by at least one test, keyed by area then level; uncovered surfaces are explicit
gaps), and that referenced backend test methods and .http files actually exist (dead refs fail early).

Coverage shape (per acceptance criterion):
    coverage:
      <area>:            # backend | http | web | apple | android
        <level>:         # unit | integration | e2e
          - <ref>        # backend: "ClassName.method"; http: a file path
    gaps:
      <area>: <reason>   # surfaces intentionally not (yet) covered
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
FEATURES = ROOT / "docs" / "features"
COVERAGE = ROOT / "docs" / "coverage" / "features"
BACKEND_TESTS = ROOT / "backend" / "src" / "test"

KNOWN_AREAS = {"backend", "http", "web", "apple", "android"}
KNOWN_LEVELS = {"unit", "integration", "e2e"}

errors: list[str] = []


def backend_test_methods() -> set[str]:
    methods: set[str] = set()
    if BACKEND_TESTS.exists():
        for kt in BACKEND_TESTS.rglob("*.kt"):
            methods.update(re.findall(r"fun\s+([A-Za-z0-9_]+)\s*\(", kt.read_text()))
    return methods


def rel(p: pathlib.Path) -> str:
    return str(p.relative_to(ROOT))


def check_ref(area: str, ref: str, where: str) -> None:
    if area == "backend":
        if ref.split(".")[-1] not in methods:
            errors.append(f"{where}: backend test not found: {ref}")
    elif area == "http":
        if not (ROOT / ref).exists():
            errors.append(f"{where}: http file not found: {ref}")
    # web/apple/android have no code yet; refs are not verified until those surfaces exist.


methods = backend_test_methods()
usecases = sorted(FEATURES.glob("*/use-cases/uc.*.md"))

for uc in usecases:
    feature = uc.parent.parent.name
    stem = uc.stem
    cov = COVERAGE / feature / "use-cases" / f"{stem}.yaml"
    if not cov.exists():
        errors.append(f"{rel(uc)}: missing coverage file {rel(cov)}")
        continue
    try:
        data = yaml.safe_load(cov.read_text()) or {}
    except yaml.YAMLError as exc:
        errors.append(f"{rel(cov)}: invalid YAML: {exc}")
        continue
    where0 = rel(cov)
    if data.get("useCase") != stem:
        errors.append(f"{where0}: useCase '{data.get('useCase')}' must equal '{stem}'")
    if data.get("feature") != feature:
        errors.append(f"{where0}: feature '{data.get('feature')}' must equal '{feature}'")

    for ac in data.get("acceptanceCriteria") or []:
        acid = ac.get("id", "?")
        where = f"{where0}: criterion '{acid}'"
        coverage = ac.get("coverage") or {}
        ref_count = 0
        for area, levels in coverage.items():
            if area not in KNOWN_AREAS:
                errors.append(f"{where}: unknown area '{area}'")
            if not isinstance(levels, dict):
                errors.append(f"{where}: area '{area}' must map level -> [refs]")
                continue
            for level, refs in levels.items():
                if level not in KNOWN_LEVELS:
                    errors.append(f"{where}: unknown level '{level}' (area '{area}')")
                if not isinstance(refs, list) or not refs:
                    errors.append(f"{where}: area '{area}' level '{level}' has no refs")
                    continue
                for ref in refs:
                    ref_count += 1
                    check_ref(area, ref, where)
        if ref_count == 0:
            errors.append(f"{where}: no tests (every criterion needs at least one)")

    gaps = data.get("gaps") or {}
    for area, reason in gaps.items():
        if area not in KNOWN_AREAS:
            errors.append(f"{where0}: gap for unknown area '{area}'")
        if not reason:
            errors.append(f"{where0}: gap for area '{area}' needs a reason")

# Orphan coverage files (coverage without a matching use-case).
for cov in COVERAGE.glob("*/use-cases/uc.*.yaml"):
    feature = cov.parent.parent.name
    uc = FEATURES / feature / "use-cases" / f"{cov.stem}.md"
    if not uc.exists():
        errors.append(f"{rel(cov)}: orphan coverage (no use-case {rel(uc)})")

if errors:
    print("COVERAGE VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"coverage OK: {len(usecases)} use-case(s) mapped, all acceptance criteria covered")
