#!/usr/bin/env python3
"""Validate the feature/use-case -> coverage trust chain.

Checks that every use-case doc has a coverage file, that each is well-formed (every acceptance
criterion is covered by at least one test, keyed by area then level; uncovered surfaces are explicit
gaps), and that **every** referenced test actually exists — across all surfaces — so dead refs fail early:
    backend            -> "ClassName.method", resolved against *that* class (test file whose stem is
                          ClassName must exist AND contain method)
    http / web         -> a file path (http rel. to repo root; web rel. to web/)
    apple / android    -> a file path (contains '/', rel. to repo root) OR "ClassName.method"
                          (resolved against that class, as backend)

Class-scoped resolution: a "ClassName.method" ref is checked against the test file whose stem is
ClassName, not a flat set of every method name on the surface — so a ref pointing at the wrong class
fails even if some other class happens to have a method of that (generic) name.

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


def _stem_methods(surface: str, files, pattern: str) -> dict[str, set[str]]:
    """Map each test file's stem -> the {method names} declared in it.

    Class-scoped ref resolution keys on the file stem, so it assumes stems are unique within a
    surface; two files sharing a stem would silently merge their methods and re-open the very
    cross-class hole this keying closes. That invariant holds today but isn't otherwise enforced,
    so collisions are reported as errors rather than assumed away.

    Per-file helper/stub/companion classes lump under the file stem; harmless because they are
    never ref targets, and stem-keying ignores them for free.
    """
    by_stem: dict[str, set[str]] = {}
    sources: dict[str, list[str]] = {}
    for f in files:
        by_stem.setdefault(f.stem, set()).update(re.findall(pattern, f.read_text()))
        sources.setdefault(f.stem, []).append(rel(f))
    for stem, paths in sources.items():
        if len(paths) > 1:
            errors.append(f"{surface} test stem '{stem}' is not unique (breaks class-scoped "
                          f"resolution): {', '.join(sorted(paths))}")
    return by_stem


KOTLIN_FUN = r"fun\s+`?([A-Za-z0-9_]+)`?\s*\("
SWIFT_FUNC = r"func\s+([A-Za-z0-9_]+)\s*\("


def backend_test_methods() -> dict[str, set[str]]:
    return _stem_methods("backend", BACKEND_TESTS.rglob("*.kt"), KOTLIN_FUN) if BACKEND_TESTS.exists() else {}


def apple_test_methods() -> dict[str, set[str]]:
    swifts = [sw for d in APPLE_TEST_DIRS if d.exists() for sw in d.rglob("*.swift")]
    return _stem_methods("apple", swifts, SWIFT_FUNC)


def android_test_methods() -> dict[str, set[str]]:
    if not ANDROID.exists():
        return {}
    # only real sources: skip other modules' non-test code, and never compiled/generated output
    # under */build/* (e.g. client/build/generated/openapi/**/src/test/**), which would otherwise
    # make the map depend on local build state.
    kts = [kt for kt in ANDROID.rglob("*.kt")
           if ("/src/test/" in str(kt) or "/src/androidTest/" in str(kt)) and "/build/" not in str(kt)]
    return _stem_methods("android", kts, KOTLIN_FUN)


def rel(p: pathlib.Path) -> str:
    return str(p.relative_to(ROOT))


def _check_class_method(area: str, ref: str, stem_map: dict[str, set[str]], where: str) -> None:
    """Resolve a "ClassName.method" ref against that class's own methods (not a flat surface set)."""
    cls, sep, method = ref.rpartition(".")
    if not sep:  # no '.', no '/' -> malformed for these surfaces
        errors.append(f"{where}: {area} ref malformed (expected 'ClassName.method'): {ref}")
    elif cls not in stem_map:
        errors.append(f"{where}: {area} test class not found: {cls}")
    elif method not in stem_map[cls]:
        errors.append(f"{where}: {area} test method not found: {ref}")


def check_ref(area: str, ref: str, where: str) -> None:
    if area == "backend":
        _check_class_method(area, ref, backend_methods, where)
    elif area == "http":
        if not (ROOT / ref).exists():
            errors.append(f"{where}: http file not found: {ref}")
    elif area == "web":
        # web refs are file paths relative to web/
        if not (WEB / ref).exists():
            errors.append(f"{where}: web test file not found: {ref}")
    elif area in ("apple", "android"):
        if "/" in ref:  # a file path, relative to repo root
            if not (ROOT / ref).exists():
                errors.append(f"{where}: {area} test file not found: {ref}")
        else:  # "ClassName.method"
            _check_class_method(area, ref, apple_methods if area == "apple" else android_methods, where)


backend_methods = backend_test_methods()
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
