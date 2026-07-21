#!/usr/bin/env python3
"""Pin the documented gaps of the other guards, so the trust-chain skill's gap-map can't silently rot.

The other `validate_*.py` assert that a reference *resolves*, never that what it points at carries weight —
the `trust-chain` skill maps exactly where each stops. That map used to live as prose derived by reading guard
code (`SKILL.md`), which is a *remembered* claim wearing a command's clothes: tighten a validator to close a
gap and the prose goes silently false. That is the repo's recurring "Trap 5" (a remembered claim drifting from
reality), one level up — and it bought this guard.

So each documented gap becomes an **executable** assertion instead of a sentence. For every case below the
guard: copies the working tree into a throwaway dir (heavy dirs excluded), confirms the target validator is
**green on the un-forged copy** (else an unrelated red would masquerade as "the gap holds"), injects one
forgery, runs the real validator as a subprocess, and asserts its exit code is the documented one. The real
tree is never touched — every guard resolves `ROOT` as `__file__.parent.parent`, so running the *copied*
`scripts/<guard>.py` binds it to the copy; restoration is "delete the tempdir" (an abort leaves only an
orphaned tempdir, never a dirty tree — the automated evolution of `changes/README.md` § bite-proof).

The coupling is the point: if someone tightens a validator to close a gap, the documented pass/fail flips and
**this guard goes red** — forcing the closure to be recorded in `SKILL.md`, not landed silently. When that
happens the fix is *not* to relax this guard blindly: confirm the gap really closed, then move the skill's map
and this table together.

Scope — six file-based gaps (the skill's bullets it now owns); two documented gaps stay out on purpose:
  1  a web coverage ref at a *non-test* file passes (validate_coverage: web = path-exists only)
  2  a backend ref at an *empty `fun` body* passes (validate_coverage: symbol name resolved, body never read)
  4a a coverage `status: done` is *rejected* (the one working-check pinned here, so a loosened enum bites)
  4b a lying `status: planned` on an implemented use-case passes (the asymmetry with 4a is real)
  5  a CR marked `status: done` with no implementation passes (validate_changes: enum + refs, nothing more)
  6  a link to a non-existent `#fragment` anchor in an existing file passes (validate_doc_links strips it)
Deferred (see cr-0180 / inbox): the mutation **total-vs-per-glob** asymmetry (needs a report fixture + git, so
it belongs to a mutation-job-local test, not this buildless glob), and CR-number reuse **against history** (a
principled edge — a guard never sees history — whose remedy is a monotonic next-number helper, inbox #107).

A `validate_*.py`, so the trust-chain glob runs it on every push with no build. Runnable locally:

    python3 scripts/validate_guard_gaps.py
"""
from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile
import textwrap

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Heavy or irrelevant to the pure-file validators; skipping them turns a multi-GB copy into ~10 MB / <1 s.
# `.git` matters twice: it is huge, and its absence is fine — none of the six target guards reads history.
_IGNORE = shutil.ignore_patterns(
    ".git", "node_modules", "build", ".gradle", ".venv", "dist", "target", "__pycache__",
)

# Anchors the forgeries edit. If one drifts (a coverage file relocated, a test class renamed), the forgery
# fails to apply and the case reports red with a "could not forge" message — deliberately loud, because a
# forgery that silently no-ops would make the assertion vacuously pass.
COV_APPEARANCE = "docs/coverage/features/settings/use-cases/uc.settings.appearance-language.yaml"
COV_SHARE_NOTE = "docs/coverage/features/sharing/use-cases/uc.sharing.share-note.yaml"
WEB_TEST_REF = "src/shared/state/useThemeState.test.ts"
BACKEND_TEST_CLASS = "ShareNoteSliceTest"
BACKEND_TEST_REF = f"{BACKEND_TEST_CLASS}.createWithNoteThenAnonymousResolveReturnsIt"


def _sub(text: str, old: str, new: str) -> str:
    """Replace exactly once, asserting `old` was present — a drifted anchor becomes a loud ForgeError."""
    if old not in text:
        raise ForgeError(f"anchor not found (drifted?): {old!r}")
    return text.replace(old, new, 1)


class ForgeError(Exception):
    """A forgery could not be applied — its anchor drifted. Update this guard's anchors."""


# ---- forgeries: each mutates the copied tree in place so the target guard sees exactly one planted gap ----

def forge_web_ref_nontest(root: pathlib.Path) -> str:
    f = root / COV_APPEARANCE
    f.write_text(_sub(f.read_text(), f"[{WEB_TEST_REF}]", "[package.json]"))
    return "web coverage ref → package.json (a real, non-test file)"


def forge_backend_empty_fun(root: pathlib.Path) -> str:
    tests = [t for t in (root / "backend/src/test").rglob("*.kt") if t.stem == BACKEND_TEST_CLASS]
    if not tests:
        raise ForgeError(f"backend test class {BACKEND_TEST_CLASS} not found (renamed?)")
    tf = tests[0]
    src = tf.read_text()
    cut = src.rstrip().rfind("}")  # inject before the class's final brace
    if cut == -1:
        raise ForgeError(f"no closing brace in {tf.name}")
    tf.write_text(src[:cut] + "\n    fun guardGapEmptyProof() {}\n" + src[cut:])
    cov = root / COV_SHARE_NOTE
    cov.write_text(_sub(cov.read_text(), BACKEND_TEST_REF, f"{BACKEND_TEST_CLASS}.guardGapEmptyProof"))
    return "backend ref → empty fun body (symbol resolves, body never inspected)"


def forge_coverage_status_done(root: pathlib.Path) -> str:
    f = root / COV_APPEARANCE
    f.write_text(_sub(f.read_text(), "useCase: uc.settings.appearance-language",
                      "useCase: uc.settings.appearance-language\nstatus: done"))
    return "coverage status: done (must be REJECTED — the one working-check pinned here)"


def forge_lying_planned(root: pathlib.Path) -> str:
    f = root / COV_APPEARANCE
    t = _sub(f.read_text(), "useCase: uc.settings.appearance-language",
             "useCase: uc.settings.appearance-language\nstatus: planned")
    t = t.rstrip() + '\ngaps:\n  backend: "not built yet (a lie: web is fully implemented)"\n'
    f.write_text(t)
    return "lying status: planned on an implemented use-case (+ a gap on the untargeted surface)"


def forge_cr_done_no_impl(root: pathlib.Path) -> str:
    # A CR number the guard will accept as unique in the copy; content is a valid-but-empty 'done' CR.
    (root / "changes/cr-0999-guard-gap-phantom.md").write_text(textwrap.dedent("""\
        ---
        id: cr-0999
        title: phantom done CR (guard-gap probe)
        status: done
        satisfies: []
        adrs: []
        invariants: []
        ---
        ## Goal
        A CR marked done whose implementation never existed. validate_changes cannot tell.
        """))
    return "CR status: done with no implementation (enum + refs checked, never the code)"


def forge_doc_dead_fragment(root: pathlib.Path) -> str:
    (root / "docs/guard-gap-probe.md").write_text(
        "# Probe\n\nSee [the readme](../changes/README.md#this-anchor-does-not-exist-zzz).\n")
    return "doc link to a non-existent #fragment anchor (fragment stripped, never verified)"


# id, skill-bullet, target guard, forgery, expect_pass (True = documented to stay green under the forgery)
CASES = [
    ("1",  "validate_coverage.py",  forge_web_ref_nontest,      True),
    ("2",  "validate_coverage.py",  forge_backend_empty_fun,    True),
    ("4a", "validate_coverage.py",  forge_coverage_status_done, False),
    ("4b", "validate_coverage.py",  forge_lying_planned,        True),
    ("5",  "validate_changes.py",   forge_cr_done_no_impl,      True),
    ("6",  "validate_doc_links.py", forge_doc_dead_fragment,    True),
]


def run_guard(root: pathlib.Path, script: str) -> int:
    return subprocess.run(
        [sys.executable, str(root / "scripts" / script)],
        capture_output=True, text=True,
    ).returncode


def copy_tree() -> pathlib.Path:
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="guardgap_"))
    shutil.copytree(ROOT, tmp / "repo", ignore=_IGNORE, symlinks=True)
    return tmp


def check_case(case_id: str, script: str, forge, expect_pass: bool) -> str | None:
    """Return an error string if the documented pass/fail no longer holds, else None."""
    tmp = copy_tree()
    try:
        root = tmp / "repo"
        if run_guard(root, script) != 0:
            return (f"case {case_id}: {script} is already RED on the un-forged copy — cannot trust the "
                    f"forgery result (fix the underlying red first)")
        try:
            planted = forge(root)
        except ForgeError as e:
            return f"case {case_id}: could not apply forgery — {e}. Update this guard's anchors."
        passed = run_guard(root, script) == 0
        if passed != expect_pass:
            want = "still pass (gap open)" if expect_pass else "reject it"
            now = "passed" if passed else "was rejected"
            return (f"case {case_id}: {script} should {want} for [{planted}], but it {now}. "
                    f"If a validator was deliberately tightened, move the SKILL.md gap-map and this "
                    f"guard's CASES table together — do not just relax this check.")
        return None
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    errors = [e for c in CASES if (e := check_case(*c))]
    if errors:
        print("GUARD-GAP VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1
    print(f"guard gaps OK: {len(CASES)} documented gap(s) still hold as the trust-chain skill claims")
    return 0


if __name__ == "__main__":
    sys.exit(main())
