#!/usr/bin/env python3
"""Guard web-first's authority — the rule is cited to ADR 0043 and nothing else (cr-0167).

The **web-first** rule governs how every feature CR is scoped, yet until [ADR 0043] it had no decision record.
What it had instead was three claimed authorities, none of which could carry it — and 44 assertions across
`docs/` pointing at them:

  * **ADR 0010** (38 lines) — never says web-first; its per-CR clause said the *opposite* ("every feature CR
    carries phases for **every** surface … plus a **parity ledger**").
  * **`platform-parity.md`** (5 lines) — a *state* doc, and its restatement had drifted **weaker** than the rule
    in force (it promised native catch-up CRs; there are none while parked).
  * **`CLAUDE.md`** (1 line — ADR 0041, which derived its entire trigger from it) — does not state the rule.

That is worse than a gap: a reader who *followed the citation* was confirmed in the wrong belief about their own
project. cr-0167 repointed all 44 at ADR 0043; this guard is why they cannot silently drift back.

**The invariant is lexical**, like cr-0166's: *a line that asserts web-first must not cite ADR 0010,
`platform-parity.md` or `CLAUDE.md` as its authority.* The one allowlist entry is **ADR 0043 itself**, and it is
principled — the ADR that *owns* a rule may name the documents it takes it from, and 0043's whole Context is the
inventory of these three false authorities.

Two things this guard learned the hard way, both worth keeping:

1. **Link targets are stripped before testing for an assertion.** ADR 0043's own filename contains the string
   "web-first" (`0043-web-first-v1-native-parked.md`), so *every citation of it* matches a naive `web.first`
   grep — the fix would look like the defect. Only **prose** asserts; a link target only cites. (This is also
   why cr-0167's planned read-back "the total stays 88" was unrunnable as written: the repoint changed what the
   counting command matched.)

2. **Line-scope is a proxy for clause-scope, and the docs are shaped to keep it exact.** "Cites X *as the
   authority for* Y" is a binding between clauses, which a grep cannot see; co-occurrence on one line is the
   closest honest approximation. Where a document legitimately needs **both** ADRs — the parity *commitment*
   (0010) and the web-first *schedule* (0043) — it states each with its own claim and its own citation, which
   is better prose anyway and is what keeps this proxy exact (`CLAUDE.md`'s repo-shape line and
   `en.account.identity-pinning.md`'s native-parity bullet are the two live examples). The cost is real and
   accepted: re-wrapping one of those so a legitimate ADR 0010 citation lands on a web-first line reds this
   guard. That is a false positive, and the fix is to keep the claims apart — not to weaken the check.

Scope note: reads `docs/` **state** plus `CLAUDE.md` (which was itself part of the defect, and is the first file
every session reads). Skips `docs/inbox.md`, which is *flow* — raw topics that may quote the old wording while
pending (`docs/ai/README.md`, state vs. flow). Every cr-0167 confirm-command excluded it too.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

# The ADR that owns the rule may name the authorities it takes it from — the one allowlist entry.
OWNER = DOCS / "decisions" / "0043-web-first-v1-native-parked.md"
SKIP = {DOCS / "inbox.md", OWNER}

LINK = re.compile(r"!?\[([^\]]*)\]\(([^)]+)\)")

# cr-0167's inventory grep, promoted verbatim — but applied to prose only (see the docstring).
WEBFIRST = re.compile(r"web.first", re.IGNORECASE)

# (label, prose pattern, link-target pattern, what to cite instead)
AUTHORITIES = [
    ("ADR 0010",
     re.compile(r"\bADR 0010\b"),
     re.compile(r"0010-native-clients-shared-contracts-not-code\.md"),
     "ADR 0010 governs *how* the surfaces relate (shared contracts, never shared code) and never says "
     "web-first; its per-CR clause defers the schedule to ADR 0043"),
    ("platform-parity.md",
     re.compile(r"\bplatform-parity\b"),
     re.compile(r"platform-parity\.md"),
     "platform-parity.md is a *state* doc — it records which surface has what, and cites ADR 0043 for why the "
     "native columns are empty; a decision restated in state drifts (docs/ai/README.md)"),
    ("CLAUDE.md",
     re.compile(r"\bCLAUDE\.md\b"),
     re.compile(r"CLAUDE\.md"),
     "CLAUDE.md names the rule and cites ADR 0043 for it — it is not itself the decision record"),
]

errors: list[str] = []


def prose(line: str) -> str:
    """The line as a reader reads it: link text kept, link *targets* dropped."""
    return LINK.sub(r"\1", line)


def link_targets(line: str) -> list[str]:
    return [m.group(2) for m in LINK.finditer(line)]


def scanned() -> list[pathlib.Path]:
    paths = [p for p in sorted(DOCS.rglob("*")) if p.suffix in {".md", ".yaml"} and p not in SKIP]
    return paths + [ROOT / "CLAUDE.md"]


def check(path: pathlib.Path) -> None:
    rel = path.relative_to(ROOT)
    for lineno, line in enumerate(path.read_text().splitlines(), start=1):
        text = prose(line)
        if not WEBFIRST.search(text):
            continue
        targets = link_targets(line)
        for label, in_prose, in_target, why in AUTHORITIES:
            if in_prose.search(text) or any(in_target.search(t) for t in targets):
                errors.append(
                    f"{rel}:{lineno} asserts web-first and cites {label} for it — the authority is "
                    f"ADR 0043 (docs/decisions/0043-web-first-v1-native-parked.md). {why}. If this line needs "
                    f"{label} for its *own* subject, give that claim its own sentence and citation")


def main() -> int:
    for anchor in (DOCS, OWNER, ROOT / "CLAUDE.md"):
        if not anchor.exists():
            print(f"WEB-FIRST AUTHORITY VALIDATION FAILED:\n  - missing {anchor.relative_to(ROOT)} — this "
                  f"guard's anchor is gone; web-first's decision record moved or was deleted")
            return 1

    paths = scanned()
    for path in paths:
        check(path)

    if errors:
        print("WEB-FIRST AUTHORITY VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1

    asserting = sum(
        1 for p in paths for line in p.read_text().splitlines() if WEBFIRST.search(prose(line)))
    print(f"web-first authority OK: {asserting} assertion(s) across {len(paths)} docs, none citing ADR 0010, "
          f"platform-parity.md or CLAUDE.md — the rule's only authority is ADR 0043")
    return 0


if __name__ == "__main__":
    sys.exit(main())
