---
id: cr-0112
title: German inclusive language — neutral-first sweep of the DE i18n catalog + wiki
status: done
satisfies: [cc.inclusive-language]
invariants: [operator-blind-e2ee]
---

## Goal

Make veyq's **German** user-facing text inclusive: no generic masculine (nor generic feminine) for a person who
could be any gender. Establish the convention as durable state and sweep the two DE surfaces that exist today
(web-first per the V1 scope) — the `de` i18n catalog and the published DE wiki. **English strings and code are
untouched** — this is a German-voice language pass only.

## Context & constraints

- **Convention = Neutral-first** (the user's decision): prefer a **geschlechtsneutrale Umformulierung** („die
  Person", „wer …", „alle, die …", plural/Partizip, rephrase away from a gendered pronoun); use the **Gender-
  Doppelpunkt `:innen`/`:r`** only where a neutral rewrite is genuinely awkward (already established in the app,
  e.g. `jede:r im Browser`). Captured in
  [cc.inclusive-language](../docs/crosscutting/cc.inclusive-language.md) and pointed to from the
  [ai/README Language rule](../docs/ai/README.md).
- **Two project decisions (the user's rulings):** „**Owner**" is **avoided entirely** in German (→ „wer … besitzt"
  / „die besitzende Person" / „Besitz-…" / „Mitbesitz"); „**Benutzername**" is **kept** (a fixed credential label,
  not a gendered person-reference).
- **Language-only, no logic.** No i18n key renames, no message-format changes — vue-i18n named interpolation
  `{name}` and pluralization `|` are left intact (none of the changed strings carried either). The rewrites are
  behind the existing keys, so components + tests reference them unchanged.
- **Scope = web-first (V1).** `web/src/i18n/index.ts` (`de`) + `wiki/de/*.md`. Native DE strings (`android`
  `strings.xml`, Apple once externalized) **nachziehen** under `cc.inclusive-language` when those surfaces are
  worked — noted, not built here.
- **Grounded by an audit.** A subagent audited the whole `de` catalog + the DE wiki for generic masculine/feminine
  and non-inclusive person-references; the sweep applies its findings (7 hard i18n strings incl. the already-
  inclusive-but-Owner ones now de-Ownered, a handful of pronoun polishes, + ~8 wiki phrases).
- **Verify:** `cd web && npm run check` (the DE change must not break typecheck/tests/build; any test asserting a
  changed DE string is updated in step); the three trust-chain guards green; a quick DE-render smoke.

## Phases

- [x] 1 convention — write [cc.inclusive-language](../docs/crosscutting/cc.inclusive-language.md) (neutral-first
  rule + examples + the Owner/Benutzername decisions + scope) and point to it from the ai/README Language rule.
- [x] 2 web i18n — sweep the `de` block of [web/src/i18n/index.ts](../web/src/i18n/index.ts): the generic-masculine
  role/person strings (`members.notOwner`, `tenant.notAuthorized`, `tenant.join.expired`/`wrongOwner`, `setup.sub`,
  the `tenant.*`/`groups.*` Owner compounds → Besitz/besitzende Person, `groups.promote`/`demote`/`lastOwner`/
  `deleteSub`, `groups.addMemberIntro`/`identityChanged`, `members.removeConfirm`, `verify.reviewIntro`) and the
  two generic-feminine `share.uploader*` strings. · Done when: `npm run check` green.
- [x] 3 wiki — sweep [wiki/de/quantencomputer-und-deine-fotos.md](../wiki/de/quantencomputer-und-deine-fotos.md)
  (the „geduldiger Angreifer" line) and [wiki/de/sind-meine-daten-sicher.md](../wiki/de/sind-meine-daten-sicher.md)
  (the „niemand/jeden/jeder", the „Freund"/„Betreiber" cluster, the self-hosting note). The third DE wiki page was
  already clean. · Done when: the two pages read neutral-first; `validate_doc_links.py` green.
- [x] 4 verify — `npm run check` (typecheck + vitest + build) + the three trust-chain guards green, which
  validates the `de` catalog parses as valid TS and renders through the existing i18n machinery (same keys, no
  `{…}`/`|` touched). A full DE-locale click-through smoke was **not run** — proportionate to a language-only
  change with a green typecheck/build, and the dev backend is down; the DE reading is verifiable at the next DE
  session. The rewrites were authored per the audit against the neutral-first convention.

## Definition of done

The German app + DE wiki avoid the generic masculine/feminine for a generic person; the neutral-first convention
is written as `cc.inclusive-language` and referenced from the ai/README Language rule; „Owner" is gone from the
German voice and „Benutzername" is kept. English + code untouched; no key/format changes. `npm run check` + the
three trust-chain guards green (which validates the DE catalog parses + renders unchanged); a full DE click-through
smoke was skipped as disproportionate for a language-only change (verifiable at the next DE session). Native DE
strings nachziehen under the same concern when those surfaces are worked.

## Retrospective

A clean, well-scoped editorial pass — the subagent audit meant the sweep was targeted (a couple dozen strings out
of ~230, plus ~8 wiki phrases) rather than a blind read of the whole catalog, and the message-format check up front
confirmed no `{…}`/`|` string was at risk.

What to watch:
- **The convention now needs enforcing, not just documenting.** Nothing stops the next DE string from shipping a
  generic masculine — a lint (a small script flagging „der Nutzer/Besitzer/Betreiber", „jeder, der" in the `de`
  catalog + `wiki/de/`) is the natural follow-up when the pain repeats (rule-of-three), and would also cover the
  native surfaces once they gain DE strings.
- **„Owner ganz vermeiden" was the more invasive of the two options** and coined „Besitz-Einladung"/„Mitbesitz";
  worth a glance in the live smoke that these read naturally in context (a button „Mitbesitz geben" vs. the
  English „Make co-owner").
- **Native parity is a real open thread**, not a non-goal — `android/.../strings.xml` has DE strings that still use
  the old wording; flagged under `cc.inclusive-language` for when Android is next touched, so it doesn't silently
  drift from the web voice.

Process: waited for the explicit "los"; the convention decision (neutral-first) + the two rulings (Owner/
Benutzername) were made with the user before the sweep; German product voice, English docs/code.
