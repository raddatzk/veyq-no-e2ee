# ADR 0043 — Web-first V1; apple/android parked until an explicit un-park

- Status: Accepted
- Date: 2026-07-14

## Context

veyq commits to four client surfaces kept at parity, guaranteed by shared contracts rather than shared code
([ADR 0010](0010-native-clients-shared-contracts-not-code.md)). ADR 0010 goes one step further and fixes a
*schedule* alongside that architecture: every feature CR "carries phases for **every** surface … plus a
**parity ledger** — so no surface silently lags".

**That schedule is not what we do, and has not been for some time.** The working rule is *web V1 first, native
parked*. It is asserted **88 times across 41 files** in `docs/` — and it has no **decision record**. What it has
instead is three claimed authorities, none of which can carry it:

- **ADR 0010 itself** — cited by 38 of those lines, and it never says web-first. Its per-CR clause says the
  opposite. A reader who follows the citation is confirmed in the wrong belief about their own project.
- **`CLAUDE.md`** — cited by [ADR 0041](0041-shared-crypto-core-deferred.md), which derives its *entire*
  trigger ("adopt the shared crypto core at the native un-park moment") from "web-first V1, native deferred
  (CLAUDE.md; project priority)". `CLAUDE.md` does not state it; it says the four surfaces are "kept at
  **parity**".
- **[`platform-parity.md`](../crosscutting/platform-parity.md)** — the only doc that states anything like the
  rule ("the **web** vertical leads; apple/android parity is a **deferred program** — caught up later as
  dedicated native CRs"). Two problems: it is a **state** doc, and a decision restated in state is precisely
  what the workflow forbids (state records *what veyq is*, decisions live in ADRs); and its rule is **weaker
  than the one in force** — it promises native catch-up CRs, while native is in fact parked with none planned.

So the rule governs real work — 88 assertions, another ADR's trigger, and the shape of every feature CR — while
being unfindable, uncheckable and unrevisable. This ADR is its decision record.

## Decision

**Ship a stable web V1 first. `apple/` and `android/` are parked. Native work resumes on an explicit
decision — deliberately *not* on an automatic trigger.**

- **The live surfaces are `backend` + `web`.** A feature CR's phase order is **contracts → backend → web**.
  Parked surfaces get no phases, and their absence is not a gap in the CR.
- **Parked means parked**, not slow: no apple/android phases, no native CI job ([inbox #80](../inbox.md)
  stays parked with it), no native entry in a CR's plan.
- **The parity *commitment* is untouched — only its *schedule*.** ADR 0010 remains the governing rule for
  *how* the surfaces relate (platform-idiomatic stacks, shared contracts, never shared code) and *which*
  platforms we commit to. This ADR governs only *in which order* they are built. Where ADR 0010's per-CR-scope
  clause mandates all-surface phases plus a parity ledger, **this ADR is the current schedule** and 0010's
  clause defers to it.
- **The un-park is an explicit call, with no measurable trigger — on purpose.** Naming a coverage threshold or
  a "V1 feature-complete" line would fix the surface count of a decision whose inputs (product reach, native
  effort, whether the shared crypto core lands first) are not knowable now. An honest "we decide when we
  decide" beats a threshold that would be quietly renegotiated the first time it bound. **The cost is
  acknowledged and accepted:** this ADR states the *state* and its consequences, not a date, so "are we there
  yet?" has no mechanical answer and stays a judgement call.
- **Parity's insurance stays paid, at zero present cost.** The `contracts/` OpenAPI specs and the crypto
  conformance vectors ([ADR 0009](0009-native-crypto-per-platform-vectors.md)) remain authoritative and
  asserted on every *live* stack. They are what make the park reversible: native re-enters against a contract
  that never stopped being maintained, not against a reconstruction.
- **This ADR is the referent for [ADR 0041](0041-shared-crypto-core-deferred.md)'s trigger.** "The native
  un-park moment" means the explicit decision above — which is precisely the moment 0041 must be revisited,
  *before* apple/android crypto is re-hand-written in Swift/Kotlin.

## Consequences

- **`docs/` cites this ADR for web-first, not ADR 0010.** Every citation-bearing assertion is repointed here
  (cr-0167); `CLAUDE.md`'s "kept at parity" is corrected to name the commitment *and* the schedule.
- **[`platform-parity.md`](../crosscutting/platform-parity.md) stops stating the rule and starts citing it.**
  Its "native parity is a deferred catch-up" section is replaced by a pointer here — the state doc records
  *which surface has what*, this ADR records *why the native columns are empty*. Its weaker promise ("caught up
  later as dedicated native CRs") goes with it: there are no native CRs while parked.
- **Coverage stays honest.** apple/android remain explicit `gaps:` entries with a reason in
  `docs/coverage/` — unchanged mechanically ([ADR 0040](0040-coverage-gaps-and-http-not-a-surface.md)); this
  ADR only gives those reasons a citation that resolves.
- **ADR 0010's parity ledger stands down while parked.** A ledger whose rows are permanently "pending" for two
  of four surfaces measures nothing; it returns with the surfaces.
- **The park is load-bearing for ADR 0041 and must not drift silently.** If native un-parks without 0041 being
  revisited first, the shared-crypto-core decision is made by default — the one outcome 0041 exists to prevent.
- **Rejected — a coverage-threshold trigger** (a named use-case set green on web un-parks native): mechanically
  checkable, which is genuinely attractive against a judgement call. Rejected because fixing the "web V1" UC set
  today is itself an unmade decision, and a threshold nobody is ready to honor is worse than no threshold — it
  reads as settled while being renegotiated.
- **Rejected — "web V1 feature-complete" as a prose trigger:** names a direction but is no more checkable than
  an explicit call, while *sounding* like a commitment. Strictly worse than saying "we decide".
- **Rejected — web-first as a clause inside ADR 0010:** 38 stale citations already point at 0010, so adopting
  it there would make them retroactively correct for free — a real and non-trivial saving, since repointing
  them is the bulk of cr-0167. Rejected anyway, because it fuses two different axes — *how surfaces relate*
  (0010) and *in which order they are built* (here) — into one record. That fusion is not a hypothetical cost:
  it is *how the schedule became invisible in the first place*. Buying 38 free links with the same mistake that
  produced them is the wrong trade; the citations are repointed instead, and a link validator proves it.
- **Rejected — web-first as a clause inside ADR 0041:** it is where the rule is *de facto* written today, but
  0041 is about the Rust crypto core; a project-wide priority hidden inside it stays unfindable for the 41
  documents that need it — and it would make 0041 cite itself for its own trigger.
- **Rejected — leaving the rule in `platform-parity.md`:** it is the only doc that states it, so this is the
  cheapest option on paper. Rejected because a decision in a state doc has no Context, no rejected
  alternatives, and no status — it cannot be revisited, only discovered, and this one had already drifted
  weaker than the rule it recorded. That doc is **not** neglected — 17 commits, last touched five days before
  this ADR — and its matrix still covers **7 of 39** use-cases while listing the `http` surface that ADR 0040
  demoted. Active maintenance did not save it, because a hand-kept matrix cannot track 39 use-cases; that is an
  argument for generating state and *deciding* in ADRs, not for hiding one inside the other.
