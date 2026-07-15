#!/usr/bin/env python3
"""Validate the feature/use-case -> coverage trust chain.

Checks that every use-case doc has a coverage file, that each is well-formed (every acceptance
criterion is covered by at least one test, keyed by area then level; uncovered surfaces are explicit
gaps), and that **every** referenced test actually exists — across all surfaces — so dead refs fail early:
    backend            -> "ClassName.method", resolved against *that* class (test file whose stem is
                          ClassName must exist AND contain method)
    web                -> a file path (relative to web/)
    apple / android    -> a file path (contains '/', rel. to repo root) OR "ClassName.method"
                          (resolved against that class, as backend)

`http` is NOT a coverage surface (ADR 0040): the `.http` files remain IntelliJ dev tooling but run in
no CI job, so they make no coverage claim — the same behaviour is proven by the backend slice tests.

Class-scoped resolution: a "ClassName.method" ref is checked against the test file whose stem is
ClassName, not a flat set of every method name on the surface — so a ref pointing at the wrong class
fails even if some other class happens to have a method of that (generic) name.

Coverage shape (per acceptance criterion):
    coverage:
      <area>:            # backend | web | apple | android
        <level>:         # unit | integration | e2e
          - <ref>        # see resolution rules above
    gaps:
      <area>: <reason>   # surfaces with NO coverage ref for this use-case (an area that IS covered
                         # may not appear here — overlap is a stale gap and fails, ADR 0040)
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

KNOWN_AREAS = {"backend", "web", "apple", "android"}   # what a use-case may *declare*
KNOWN_LEVELS = {"unit", "integration", "e2e"}

# A surface is **live** when a CI job actually runs its tests — the ADR 0040 principle, applied by rule rather
# than by name: the claim follows the build. `apple`/`android` hold real code and real tests, but no CI job
# builds them (ADR 0043 parks them), so they prove nothing and are owed nothing. They re-enter here when their
# CI job is green — one line, and nothing else has to change (adding a surface only ever *permits* refs).
LIVE_SURFACES = {"backend", "web"}

# Which surfaces a use-case targets **by design**. Default is parity (ADR 0010) — only deviations declare, via
# a `- **Surfaces:** a · b — why` line in the use-case doc. This is the declaration; coverage is the proof.
# Before this existed, `gaps:` did both jobs, and 54 of 63 gaps were hand-copies of one sentence from ADR 0043.
SURFACES_LINE = re.compile(r"^-\s+\*\*Surfaces:\*\*\s*(.+)$", re.M)


def declared_surfaces(uc_doc: pathlib.Path) -> set[str]:
    m = SURFACES_LINE.search(uc_doc.read_text())
    if not m:
        return set(KNOWN_AREAS)                      # no declaration = parity default (ADR 0010)
    head = re.split(r"\s+—\s+", m.group(1))[0]       # the list, before the em-dash rationale
    return {s.strip() for s in head.split("·") if s.strip()}

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

    # What this use-case owes *today*: the surfaces it targets by design, minus the ones no CI runs. A use-case
    # whose only targets are parked (e.g. the native L0–L3 chooser, ADR 0005 §14-21) owes nothing — it is not a
    # gap, it is out of scope until the un-park. Refs it already carries for a parked surface stay as they are:
    # unchecked as coverage, still checked for existence, and ready to count again the day that CI turns green.
    owed = declared_surfaces(uc) & LIVE_SURFACES

    covered_areas: set[str] = set()  # areas that carry a coverage ref anywhere in this use-case
    for ac in data.get("acceptanceCriteria") or []:
        acid = ac.get("id", "?")
        where = f"{where0}: criterion '{acid}'"
        coverage = ac.get("coverage") or {}
        ref_count = 0
        for area, levels in coverage.items():
            if area not in KNOWN_AREAS:
                errors.append(f"{where}: unknown area '{area}'")
            if area in LIVE_SURFACES:
                covered_areas.add(area)  # only a live surface can *cover* — a parked ref proves nothing
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
                    if area in LIVE_SURFACES:
                        ref_count += 1
                    check_ref(area, ref, where)
        if ref_count == 0 and not planned and owed:
            errors.append(f"{where}: no tests on a live surface (needs at least one on {sorted(owed)})")

    gaps = data.get("gaps") or {}
    for area in gaps:
        if area in KNOWN_AREAS and area not in owed:
            errors.append(
                f"{where0}: gap for '{area}', which this use-case does not owe (targets "
                f"{sorted(declared_surfaces(uc))}, live: {sorted(LIVE_SURFACES)}). A gap is a **debt on a "
                f"surface you target** — not an N/A note, and never a restatement of ADR 0043")
    if planned and not gaps:
        errors.append(f"{where0}: status 'planned' must declare at least one gap (the surfaces not yet built)")
    for area, reason in gaps.items():
        if area not in KNOWN_AREAS:
            errors.append(f"{where0}: gap for unknown area '{area}'")
        if not reason:
            errors.append(f"{where0}: gap for area '{area}' needs a reason")
        # A gap means "this surface has NO coverage ref for this use-case" (ADR 0040). An area that is
        # also covered is a stale gap — build-note or deferred-slice prose that no longer belongs here.
        if area in covered_areas:
            errors.append(f"{where0}: area '{area}' is both covered and a gap "
                          f"(a gap must be an uncovered surface; move build-notes/deferred prose out)")

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
