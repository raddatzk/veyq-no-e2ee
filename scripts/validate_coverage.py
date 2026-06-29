#!/usr/bin/env python3
"""Validate the feature/use-case -> coverage trust chain.

Checks that every use-case doc has a coverage file, that each is well-formed (every acceptance
criterion is covered by at least one test, keyed by area then level; uncovered surfaces are explicit
gaps), and that **every** referenced test actually exists — across all surfaces — so dead refs fail early:
    backend            -> "ClassName.method", method resolved against backend test sources
    http / web         -> a file path (http rel. to repo root; web rel. to web/)
    apple / android    -> a file path (contains '/', rel. to repo root) OR "ClassName.method"
                          (method resolved against the surface's test sources)

Coverage shape (per acceptance criterion):
    coverage:
      <area>:            # backend | http | web | apple | android
        <level>:         # unit | integration | e2e
          - <ref>        # see resolution rules above
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
WEB = ROOT / "web"
APPLE_TEST_DIRS = [ROOT / "apple" / "Tests", ROOT / "apple" / "UITests"]
ANDROID = ROOT / "android"

KNOWN_AREAS = {"backend", "http", "web", "apple", "android"}
KNOWN_LEVELS = {"unit", "integration", "e2e"}

errors: list[str] = []


def _kotlin_methods(kt_files) -> set[str]:
    methods: set[str] = set()
    for kt in kt_files:
        methods.update(re.findall(r"fun\s+`?([A-Za-z0-9_]+)`?\s*\(", kt.read_text()))
    return methods


def backend_test_methods() -> set[str]:
    return _kotlin_methods(BACKEND_TESTS.rglob("*.kt")) if BACKEND_TESTS.exists() else set()


def apple_test_methods() -> set[str]:
    methods: set[str] = set()
    for d in APPLE_TEST_DIRS:
        if d.exists():
            for sw in d.rglob("*.swift"):
                methods.update(re.findall(r"func\s+([A-Za-z0-9_]+)\s*\(", sw.read_text()))
    return methods


def android_test_methods() -> set[str]:
    if not ANDROID.exists():
        return set()
    # only real sources, never compiled output under */build/*
    kts = [kt for kt in ANDROID.rglob("*.kt")
           if "/src/test/" in str(kt) or "/src/androidTest/" in str(kt)]
    return _kotlin_methods(kts)


def rel(p: pathlib.Path) -> str:
    return str(p.relative_to(ROOT))


def check_ref(area: str, ref: str, where: str) -> None:
    if area == "backend":
        if ref.split(".")[-1] not in methods:
            errors.append(f"{where}: backend test not found: {ref}")
    elif area == "http":
        if not (ROOT / ref).exists():
            errors.append(f"{where}: http file not found: {ref}")
    elif area == "web":
        # web refs are file paths relative to web/
        if not (WEB / ref).exists():
            errors.append(f"{where}: web test file not found: {ref}")
    elif area in ("apple", "android"):
        surface_methods = apple_methods if area == "apple" else android_methods
        if "/" in ref:  # a file path, relative to repo root
            if not (ROOT / ref).exists():
                errors.append(f"{where}: {area} test file not found: {ref}")
        elif ref.split(".")[-1] not in surface_methods:  # "ClassName.method"
            errors.append(f"{where}: {area} test not found: {ref}")


methods = backend_test_methods()
apple_methods = apple_test_methods()
android_methods = android_test_methods()
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

    # A use-case may be refined (its doc + acceptance criteria written) before any surface implements
    # it. Such a file declares `status: planned` and carries no test refs yet — every surface it will
    # ever target must instead appear under `gaps` with a reason. Once a CR lands real tests, drop the
    # status and fill `coverage`. Omit `status` (or set anything but 'planned') = implemented, tests
    # required per criterion.
    status = data.get("status")
    if status not in (None, "planned"):
        errors.append(f"{where0}: unknown status '{status}' (omit for implemented, or 'planned')")
    planned = status == "planned"

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
        if ref_count == 0 and not planned:
            errors.append(f"{where}: no tests (every criterion needs at least one)")

    gaps = data.get("gaps") or {}
    if planned and not gaps:
        errors.append(f"{where0}: status 'planned' must declare at least one gap (the surfaces not yet built)")
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
