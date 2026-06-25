#!/usr/bin/env python3
"""Validate the feature/use-case -> coverage trust chain.

Checks that every use-case doc has a coverage file, that each coverage file is well-formed (every
acceptance criterion links at least one test; uncovered surfaces are explicit gaps), and that
referenced backend test methods and .http files actually exist (dead references fail early).
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

errors: list[str] = []


def backend_test_methods() -> set[str]:
    methods: set[str] = set()
    if BACKEND_TESTS.exists():
        for kt in BACKEND_TESTS.rglob("*.kt"):
            methods.update(re.findall(r"fun\s+([A-Za-z0-9_]+)\s*\(", kt.read_text()))
    return methods


def rel(p: pathlib.Path) -> str:
    return str(p.relative_to(ROOT))


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
    if data.get("useCase") != stem:
        errors.append(f"{rel(cov)}: useCase '{data.get('useCase')}' must equal '{stem}'")
    if data.get("feature") != feature:
        errors.append(f"{rel(cov)}: feature '{data.get('feature')}' must equal '{feature}'")
    criteria = data.get("acceptanceCriteria") or []
    if not criteria:
        errors.append(f"{rel(cov)}: no acceptanceCriteria")
    for ac in criteria:
        acid = ac.get("id", "?")
        tests = ac.get("tests") or []
        if not tests:
            errors.append(f"{rel(cov)}: criterion '{acid}' has no tests")
        for test in tests:
            area, ref = test.get("area"), test.get("ref")
            if not area or not ref:
                errors.append(f"{rel(cov)}: criterion '{acid}' test missing area/ref: {test}")
                continue
            if area == "backend":
                method = ref.split(".")[-1]
                if method not in methods:
                    errors.append(f"{rel(cov)}: criterion '{acid}' backend test not found: {ref}")
            elif area == "http":
                if not (ROOT / ref).exists():
                    errors.append(f"{rel(cov)}: criterion '{acid}' http file not found: {ref}")
            # web/apple/android have no code yet; refs are not verified until those surfaces exist.

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
