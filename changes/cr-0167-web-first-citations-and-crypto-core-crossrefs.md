---
id: cr-0167
title: Point web-first at its own ADR; cross-reference the shared-crypto-core decisions (ADR 0043/0041/0010/0009)
status: done
satisfies: []            # doc-hygiene over ADRs + crosscutting; no uc./en./cc. target (cf. cr-0038)
adrs: ["0009", "0010", "0041", "0043"]
invariants: []           # no code, no contract, no behaviour — citation + cross-reference hygiene
---

## Goal

The **web-first** rule governs how every feature CR is scoped, and until
[ADR 0043](../docs/decisions/0043-web-first-v1-native-parked.md) it had no decision record — only 88
assertions across 41 files pointing at three authorities that cannot carry it. Repoint them at 0043, correct
the two documents that state the *opposite* (ADR 0010, `CLAUDE.md`), and close the second half of the same
gap: **ADR 0009, 0010 and 0041 all decide the shared crypto core and none of them reference each other.**

Findings #3 + #4 of [the 2026-07-14 doc-vs-code review](review-2026-07-14-doc-drift-findings.md). Bundled
because they are one defect seen twice: 0041's trigger *is* web-first, so #4 is unfixable until #3 has a home.
**Depends on ADR 0043 existing** (it does — refined ahead of this CR, per `docs/ai/README.md`).

## Context & constraints

- **Finding #3's inventory was wrong by 4.6×, and the correction is the point.** It reported "19 citations"
  from `grep -rl 'web-first.*ADR 0010\|Web first, then native parity' docs/` → 19. That pattern catches **one**
  phrasing. The honest count — `grep -rn -iE 'web.first|web first' docs/`, excluding `inbox.md` and ADR 0043 —
  is **88 lines / 41 files**. A CR planned against 19 would have missed 69 lines and closed as done. This is
  the review doc's own meta-lesson biting the review doc: *a checkable claim is only as good as the command
  that produced it, and the first command was too narrow.*
- **The 88 split cleanly by what they cite** (the complement is exact; the buckets do not overlap):

  | Cites | Lines | Verdict |
  |---|---|---|
  | **ADR 0010** | **38** | **defect** — 0010 never says web-first and its per-CR clause says the opposite |
  | **nothing** | 44 | *out of scope* — a bare "sharing is web-first" in a `gaps:` reason is under-cited, not misleading |
  | **`platform-parity.md`** | 5 | defect — points at a *state* doc stating a **weaker** rule than the one in force |
  | **`CLAUDE.md`** | 1 | defect — ADR 0041:17, which derives its whole trigger from a rule CLAUDE.md lacks |

  `grep -rn -iE 'web.first|web first' docs/ | grep -vE '^docs/inbox.md|^docs/decisions/0043-' | grep -c 0010`
  → 38; the same pipeline with `grep -cvE '0010|platform-parity|CLAUDE.md'` → 44.
- **The 44 bare assertions stay bare — deliberately.** Tier 1 is "contradictions that actively mislead". An
  uncited reason misleads nobody; threading a link into 44 coverage `gaps:` strings is churn that makes the
  diff unreviewable and buys no reader anything. Named here so the gap is a decision, not an oversight.
- **`CLAUDE.md:21` is a hard-invariant document and says the wrong thing** — "four client surfaces `http/ web/
  apple/ android/`, kept at **parity**". It is the first file every session reads. (`http/` in that list is a
  separate drift — ADR 0040 demoted it; see out-of-scope.)
- **No code, no contract, no behaviour.** Every check here is `validate_doc_links.py` plus a new grep guard.

## Decisions

1. **Every citation-bearing web-first assertion cites [ADR 0043](../docs/decisions/0043-web-first-v1-native-parked.md).**
   All 44 (38 + 5 + 1). The 44 bare ones are untouched.

2. **ADR 0010's per-CR-scope clause is corrected, not deleted.** `0010:51-54` currently mandates that "every
   feature CR carries phases for **every** surface … plus a **parity ledger**". That is false in practice and
   has been for months. It is rewritten to state the parity *commitment* (which is 0010's actual subject and
   remains true) and defer the *schedule* to ADR 0043. The ledger returns when the surfaces do.
   *Rejected — delete the clause.* It would leave 0010 silent on per-CR scope, which is how the schedule went
   unwritten the first time.

3. **`platform-parity.md` cites the rule instead of stating it.** Its "Native parity is a deferred catch-up"
   section (`:17-25`) is replaced by a pointer to ADR 0043. A decision restated in a state doc is what
   `docs/ai/README.md` forbids ("State is only updated — never a dumping ground"), and this restatement had
   already drifted **weaker** than the real rule: it promises native catch-up CRs, and there are none while
   parked.

4. **`CLAUDE.md:21` names the commitment *and* the schedule** — the four surfaces are kept at parity *by
   shared contracts* (ADR 0010), and are built *web-first with native parked* (ADR 0043). Both, or the next
   reader inherits exactly this defect.

5. **#4 — the three crypto-core ADRs reference each other; none supersedes.** The finding says "0041 adopts a
   shared Rust core" — **it does not**; it says "Adopt … but **not now**" and defers against a trigger. The
   real gap is wider than reported: **0010 also rejects the shared core** (`0010:73`), so it is three ADRs, not
   two, and `grep -c '0041' docs/decisions/0009-*.md docs/decisions/0010-*.md` → **0, 0**.
   - `0009:38-41` and `0010:73` each gain a pointer to 0041 at their rejection clause.
   - `0041` gains a pointer to both, and states plainly that it is the **live proposal against those
     rejections**, with a trigger (native un-park → ADR 0043) that is *broader* than 0009's own revisit clause
     ("only if the hybrid-KEM combiner proves too error-prone"). 0009's clause is not deleted — it is a second,
     narrower trigger that still holds.
   - **Nothing is marked `Superseded`.** 0041 is `Proposed`; a proposal cannot supersede an accepted decision,
     and pretending otherwise would be a worse drift than the one we are fixing.

6. **Not decided here: whether ADR 0041 moves `Proposed` → `Accepted`.** That is a real decision (adopt the
   Rust core, deferred) and belongs in a refinement with the user, not smuggled into a citation CR. Flagged so
   the status is a choice rather than an accident of this CR touching the file.

7. **Explicitly out of scope:**
   - **`platform-parity.md`'s matrix** — 82% empty, `en.memory.tree` marked not-built though it is live, and it
     still lists the **`http`** surface that ADR 0040/cr-0159 demoted. That is finding #9, with its own answer
     (regenerate from coverage, or drop the matrix). This CR touches only its prose section (decision 3).
   - **`CLAUDE.md`'s `http/`-as-a-surface** in the same sentence we edit — ADR 0040 territory. Correcting it
     here is a one-word temptation; it is a different decision's drift and gets its own confirm-command.
   - `developer-styleguide.md` §11, which `platform-parity.md:3` cites for parity — finding #11.

## Inventory

| Site | Today | After |
|---|---|---|
| 38 lines / `docs/` (mostly `uc.memory.*`, `docs/coverage/**`) | cite ADR 0010 for web-first | cite ADR 0043 |
| 5 lines incl. `0033:138`, `uc.memory.live-updates.yaml` | cite `platform-parity.md` | cite ADR 0043 |
| `docs/decisions/0041-shared-crypto-core-deferred.md:17` | cites `CLAUDE.md` for the rule | cites ADR 0043 |
| `docs/decisions/0010-...:51-54` | "every feature CR spans all surfaces" + parity ledger | commitment kept; schedule → ADR 0043 |
| `docs/crosscutting/platform-parity.md:17-25` | states a weaker version of the rule | cites ADR 0043 |
| `CLAUDE.md:21` | "kept at **parity**" | parity *by contracts* + web-first *schedule*, both cited |
| `docs/decisions/0009-...:38-41`, `0010-...:73` | reject the shared core, no pointer to 0041 | pointer to 0041 as the live proposal |
| `docs/decisions/0041-...` | no pointer to 0009 or 0010 | pointers to both; trigger → ADR 0043 |
| 44 bare web-first lines | uncited prose | **unchanged** (decision 1) |

Line numbers are cited only where the target is a specific clause; the 44 repoints are found by the grep
above, not enumerated here — they drift, and the command re-finds them for free.

## Plan

Single phase — docs only, no seam to walk.

1. **The ADRs** — 0010's per-CR clause (decision 2) + the three-way cross-reference (decision 5).
2. **The two roots** — `CLAUDE.md:21` (decision 4), `platform-parity.md:17-25` (decision 3).
3. **The repoints** — the 44 citation-bearing lines (decision 1).
4. **The guard** — below. Done when: proven to bite.

Done when: `python3 scripts/validate_doc_links.py` + `validate_changes.py` + `validate_coverage.py` green and
the guard reds on a reverted citation.

## Corrections found while implementing

Two of this CR's own claims did not survive contact — both in the same direction as the finding it corrects (a
grep read as if it were the invariant):

1. **One of the 38 was a *mixed* citation, not a defect.**
   `en.account.identity-pinning.md:105` bundled two claims in one parenthetical — "(web-first; native shares
   contracts, not code, [ADR 0010])". The second is **ADR 0010's own title**. The bucket table above treats all
   38 as "cites 0010 for web-first"; a wholesale repoint would have *destroyed a correct citation* to fix a
   wrong one. Found by `… | grep 0010 | grep -iE 'contracts, not code|shared contracts|not shared code'` → **1
   hit, that line**. It now states each claim in its own sentence with its own citation — the same shape as
   decision 4's `CLAUDE.md` fix, and the thing that keeps the guard's line-scope exact. So: **37 mechanical
   repoints + 1 by hand**, not 38.

2. **The read-back command was invalidated by this CR's own fix; the *prediction* was right.**
   "the total count stays **88**" is **correct** — verified at 88 → 88 (prose assertions in `docs/`, excluding
   `inbox.md` + ADR 0043). But `grep -rn -iE 'web.first|web first' docs/` can no longer produce that number:
   ADR 0043's **filename contains "web-first"**, so every `[…](0043-web-first-v1-native-parked.md)` link the fix
   adds matches the pattern. The naive command now reports **94** — six citations counted as assertions. The
   honest count strips markdown link *targets* first and counts prose only; the guard does exactly that and
   prints it (`89` = 88 in `docs/` + 1 in `CLAUDE.md`). A grep for a phrase cannot survive that phrase entering
   a filename — and the CR planned the fix and the check without noticing they collide.

Also corrected, one layer past the citation: **repointing the authority does not fix a drifted *assertion*.**
`uc.memory.live-updates.yaml:63-64` said "native parity is a **deferred catch-up**" — the exact weaker wording
ADR 0043 retires ("there are no native CRs while parked"). Mechanically repointed, it would have cited 0043 for
a claim 0043 denies: the same Tier-1 defect, inverted. Those two lines now say "parked until an explicit
un-park". `grep -rn 'deferred catch-up' docs/` → **1**, ADR 0043 itself, quoting the section it retires.

## Verify

- `python3 scripts/validate_doc_links.py` — green. This is the load-bearing check: it proves all 44 new links
  **resolve**, which is exactly what the old ones did not do semantically.
- `validate_changes.py`, `validate_coverage.py` — green (the coverage YAML comments/`gaps:` reasons this CR
  edits must not break the trust chain).
- **Enforcement guard, proven to bite** — the invariant is lexical, like cr-0166's: *no document cites ADR 0010
  as the authority for web-first*. A grep expresses it exactly; add it to the `scripts/` validator set.
  Snapshot-and-restore per `changes/README.md` (never `git checkout`): re-introduce a `web-first … ADR 0010`
  citation into a use-case → the guard must red; restore from the snapshot and confirm by grepping the marker.
  **Known wrinkle for implementation:** ADR 0043 legitimately names both "web-first" and "ADR 0010" on one line
  (it is the doc that *corrects* the citation), so the guard needs 0043 excluded — the one allowlist entry, and
  it is principled: the ADR that owns a rule may name the ADR it takes it from.
- Read-back — **not** the command planned above (it counts ADR 0043's filename; see *Corrections*). Strip the
  `path:lineno:` prefix so a file cannot cite itself, and read content only:
  `grep -rn -iE 'web.first|web first' docs/ | grep -vE '^docs/inbox.md|^docs/decisions/0043-' | sed 's/^[^:]*:[0-9]*://' | grep -c 'ADR 0010\|0010-native-clients'`
  → **0**. Same pipeline for `platform-parity` → **0**, `CLAUDE\.md` → **0**. The assertion total is the guard's
  own line (link targets stripped): **88** in `docs/`, unchanged, + **1** new in `CLAUDE.md` = **89**.

## Definition of done

- No document cites ADR 0010, `CLAUDE.md` or `platform-parity.md` as the authority for web-first; the 44
  citation-bearing assertions resolve to ADR 0043 and `validate_doc_links.py` proves it.
- ADR 0010 no longer mandates all-surface CR phases; it states the parity commitment and defers the schedule.
- `CLAUDE.md` states both the commitment and the schedule.
- `platform-parity.md` cites the rule rather than restating a weaker one.
- ADR 0009, 0010 and 0041 reference each other at their rejection/proposal clauses; none is marked
  `Superseded`; 0041's status is untouched and flagged as an open question (decision 6).
- The guard has been **watched to red**.

## Outcome

`scripts/validate_webfirst_authority.py` — one invariant, all three false authorities: *a line that asserts
web-first must not cite ADR 0010, `platform-parity.md` or `CLAUDE.md`*. Allowlist = ADR 0043 alone, exactly as
planned. It scans `docs/` + **`CLAUDE.md`** (added: the file was part of the defect and is the first thing every
session reads). Wired into `.github/workflows/ci.yml`'s trust-chain step — a guard that isn't in CI isn't a
guard. Two design points it earned:

- **Link targets are stripped before a line counts as an assertion** — otherwise every citation of ADR 0043
  matches, and the fix looks like the defect (correction 2).
- **Line-scope is a proxy for clause-scope**, and the guard says so in its own docstring rather than pretending
  otherwise. A doc that legitimately needs both ADRs states each claim separately (`CLAUDE.md:21`,
  `identity-pinning`), which keeps the proxy exact today — verified — and is better prose regardless. A re-wrap
  that fuses them would false-red; the error message names that case and says to split the claim, not to weaken
  the check.

**Bite-proof (snapshot-and-restore, per `changes/README.md` — no `git checkout`).** All three branches injected
at once: the ADR 0010 *link* form into `uc.memory.gallery.md:47`, the `platform-parity` *prose* form into
`uc.memory.date.yaml:51`, and the original `CLAUDE.md` defect back into `0041:17`. Guard red on all three with
the file, line and the reason. Restored via `command cp -f`; confirmed by grepping all three fix markers (1 each)
**and** all three injection residues (0 each); guard green again at 89.

Full set green: all **9** `scripts/` validators.

## Retrospective

- **Planning: the CR made the same mistake it was written to fix — twice.** Its thesis is "a checkable claim is
  only as good as the command that produced it", and then (a) its bucket table asserted all 38 lines cite 0010
  *for web-first*, which a one-line grep disproved — one cites 0010 for 0010's own title, and repointing it
  would have broken a correct citation to fix a wrong one; (b) it specified a read-back grep that its own fix
  makes unrunnable, because the ADR filename contains the phrase being counted. Root cause is the same both
  times: the inventory grep was treated as if it were the invariant. A grep finds *candidates*; the invariant
  ("cites X **as the authority for** Y") is a claim about binding that no grep can see. **The tell was in the
  CR and I read past it:** the table's own complement is "44 bare + 44 cited = 88, buckets do not overlap" —
  exact set arithmetic, which reads as rigour and quietly assumes each line has exactly one reason. It doesn't.
- **Execution: nothing was mechanically wrong, but the near-miss was real.** The repoint script was written to
  touch only web-first lines, which was good instinct and *still* wouldn't have saved
  `identity-pinning.md:105` — that line asserts web-first. It was caught by deliberately grepping for
  counter-evidence (does any of the 38 also claim 0010's *own* subject?) before applying, not by the safety rail.
  Same for `live-updates.yaml`: repointing the citation while keeping "deferred catch-up" prose would have
  produced a link that contradicts its own sentence — a defect only visible by reading the result, not by
  running the plan.
- **Process: good.** The signal convention held ("weiter mit cr167" → implement, no round-trip). Language rule
  honored. `changes/README.md`'s snapshot-restore protocol followed literally, including confirming the
  *residues* were gone and not just the markers. The CR's own "Explicitly out of scope" section did its job —
  `http/` sat in the middle of the `CLAUDE.md` line being edited and stayed untouched.
- **Next time:** when a CR plans to repoint N citations of X, run one grep for *counter-evidence* — "which of
  the N cite X for X's **own** subject?" — and write its output into the CR **before** planning the repoint. The
  bucket table proves each line names an authority; it never proved that authority was named *only* for the rule
  being fixed. One command would have moved that from a lucky catch to a planned one.
