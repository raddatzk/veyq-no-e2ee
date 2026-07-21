#!/usr/bin/env python3
"""Pin the mutation meta-checks' documented total-vs-per-glob asymmetry (inbox #109, ADR 0045 §4).

Two sibling guards, deliberately different:
  - `mutation_meta_check.py` (crypto/web): asserts the report's **TOTAL** mutant count > 0, and only when the
    PR diff intersects the mutate scope — StrykerJS OSS has no diff scoping, so a total-0 on a touched scope is
    a broken run, not a correctly-empty one.
  - `mutation_meta_check_backend.py`: asserts **each** `targetClasses` glob is individually > 0, on every run —
    PIT's `failWhenNoMutations` already covers the global-empty case; the delta is one stale glob whose
    non-empty siblings keep the build green.

That asymmetry lives only in the two docstrings: make the backend check total-count, or the web check per-glob,
and the prose goes silently false (the repo's recurring "Trap 5"). This selftest makes it executable — the same
"one member empty, a sibling full" fixture must be **rejected** by the backend check and **accepted** by the web
check (total still > 0).

Why it is not a `validate_*.py`: the web check shells out to `git diff`, but the trust-chain glob's harness
(`validate_guard_gaps.py`) copies the tree **without** `.git` (none of its targets reads history) — so a
git-dependent check cannot live there (cr-0180). This fabricates its own Stryker/PIT reports **and** a throwaway
git repo, so it needs no real mutation run; it is invoked from `mutation.yml` / `mutation-backend.yml` beside the
real checks. Runnable locally:

    python3 scripts/mutation_meta_check_selftest.py
"""
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"


def _copy_check(tree: pathlib.Path, name: str) -> None:
    (tree / "scripts").mkdir(parents=True, exist_ok=True)
    shutil.copy(SCRIPTS / name, tree / "scripts" / name)


def _run_check(tree: pathlib.Path, name: str, *args: str) -> int:
    """Run the COPIED meta-check — its `ROOT = __file__.parent.parent` binds to the temp tree, so it reads the
    fabricated manifest/report there (the same trick validate_guard_gaps uses)."""
    return subprocess.run(
        [sys.executable, str(tree / "scripts" / name), *args],
        cwd=tree, capture_output=True, text=True,
    ).returncode


def _pit_report(classes: list[str]) -> str:
    muts = "".join(f"<mutation><mutatedClass>{c}</mutatedClass></mutation>" for c in classes)
    return f'<?xml version="1.0" encoding="UTF-8"?>\n<mutations>{muts}</mutations>\n'


def backend_case(beta_mutations: int) -> int:
    """Two scope globs (Alpha, Beta); Alpha always carries 1 mutation, Beta carries `beta_mutations`. So
    beta=0 is the 'one stale glob among non-empty siblings' the per-glob check must catch."""
    with tempfile.TemporaryDirectory() as d:
        tree = pathlib.Path(d)
        _copy_check(tree, "mutation_meta_check_backend.py")
        (tree / "backend").mkdir()
        (tree / "backend" / "pitest.scope.json").write_text(
            json.dumps({"targetClasses": ["com.veyq.app.Alpha", "com.veyq.app.Beta"]}))
        rep = tree / "backend" / "build" / "reports" / "pitest"
        rep.mkdir(parents=True)
        classes = ["com.veyq.app.Alpha"] + ["com.veyq.app.Beta"] * beta_mutations
        (rep / "mutations.xml").write_text(_pit_report(classes))
        return _run_check(tree, "mutation_meta_check_backend.py")


def _git(tree: pathlib.Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=tree, check=True, capture_output=True, text=True)


def web_case(mutate: list[str], diff_file: str, total_mutants: int) -> int:
    """`mutate`: web-relative scope entries. `diff_file`: repo-relative file changed+committed (so
    `base...HEAD` carries it). `total_mutants`: mutants placed on the first scoped file in the report."""
    with tempfile.TemporaryDirectory() as d:
        tree = pathlib.Path(d)
        _copy_check(tree, "mutation_meta_check.py")
        (tree / "web").mkdir()
        (tree / "web" / "stryker.mutate.json").write_text(json.dumps({"mutate": mutate}))
        # Base tree: every scoped file + the diff target, so a diff can hit either in- or out-of-scope.
        for rel in {f"web/{m}" for m in mutate} | {diff_file}:
            p = tree / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("// base\n")
        _git(tree, "init", "-q")
        _git(tree, "config", "user.email", "t@t")
        _git(tree, "config", "user.name", "t")
        _git(tree, "add", "-A")
        _git(tree, "commit", "-qm", "base")
        base = subprocess.run(["git", "rev-parse", "HEAD"], cwd=tree,
                              capture_output=True, text=True).stdout.strip()
        # The change as a new commit, so `git diff base...HEAD` carries it.
        (tree / diff_file).write_text("// changed\n")
        _git(tree, "add", "-A")
        _git(tree, "commit", "-qm", "change")
        rep = tree / "web" / "reports" / "mutation"
        rep.mkdir(parents=True)
        files = {f"web/{mutate[0]}": {"mutants": [{}] * total_mutants}} if total_mutants else {}
        (rep / "mutation.json").write_text(json.dumps({"files": files}))
        return _run_check(tree, "mutation_meta_check.py", "--base", base)


V1 = "src/crypto/v1.ts"
V2 = "src/crypto/v2.ts"

results: list[tuple[str, bool]] = []


def expect(name: str, actual: int, wanted: int) -> None:
    ok = actual == wanted
    results.append((name, ok))
    print(f"  {'OK  ' if ok else 'FAIL'} {name} (exit {actual}, expected {wanted})")


def main() -> int:
    print("mutation meta-check selftest — pinning the total-vs-per-glob asymmetry (ADR 0045 §4):")

    # Backend = per-glob: a 0-mutation glob among non-empty siblings must fail; all-non-empty passes.
    expect("backend: a 0-mutation glob among non-empty siblings FAILS (per-glob)", backend_case(0), 1)
    expect("backend: every glob non-empty PASSES", backend_case(2), 0)

    # Web = total-count + diff-aware.
    expect("web: diff in scope + total 0 mutants FAILS (total-count)", web_case([V1], "web/" + V1, 0), 1)
    expect("web: diff in scope + total > 0 PASSES", web_case([V1], "web/" + V1, 3), 0)
    expect("web: diff NOT in scope PASSES regardless of report", web_case([V1], "web/other.ts", 0), 0)

    # The asymmetry itself, on the SAME 'one member empty, a sibling full' shape:
    expect("asymmetry — backend REJECTS one-empty-sibling", backend_case(0), 1)
    expect("asymmetry — web ACCEPTS one-empty-sibling (total > 0)",
           web_case([V1, V2], "web/" + V1, 3), 0)

    failed = [n for n, ok in results if not ok]
    if failed:
        print(f"::error::mutation meta-check selftest FAILED — the asymmetry is not as documented: {failed}")
        return 1
    print(f"selftest OK: {len(results)} cases — backend is per-glob, web is total-count + diff-aware.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
