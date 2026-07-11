---
id: cr-0127
title: Point Dependabot + CI at the web app (/frontend → /web) so the web supply chain is monitored
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Dependabot's npm ecosystem and the CI "frontend" jobs point at a `/frontend` directory that does not exist —
the web app has lived in `/web` since the fresh start. Two verified consequences: the entire `web/` npm supply
chain (including the `@noble/*` crypto libraries) receives **zero** Dependabot update/vulnerability PRs, and the
CI `frontend` job runs `npm` in a non-existent dir, so "frontend checks" are effectively a no-op that still
reports green — broken or vulnerable web code is not gated. Three Dependabot **docker** entries also point at
paths with no Dockerfile (`/frontend`, `/infra/reset`, and `/` — no root Dockerfile exists). This CR retargets
npm + the CI web jobs to `/web` and removes the dead docker entries so the guardrail actually guards.

## Context & constraints

- **Verified against source (not inherited):** `.github/dependabot.yml` — npm → `/frontend` (:9-10), docker →
  `/frontend` (:41-46), docker → `/infra/reset` (:48-53), docker → `/` (:34-39); the `gradle /backend`,
  `gradle /android` entries are correct. `.github/workflows/ci.yml` — the `frontend` job uses
  `cache-dependency-path: frontend/package-lock.json` (:64) + `working-directory: frontend` (:67, :71, :130).
  `.github/workflows/container.yml` — the test-gate uses `frontend/package-lock.json` (:151) +
  `working-directory: frontend` (:153). Confirmed present: `/web/package.json` + `/web/package-lock.json`.
  Confirmed absent: root `Dockerfile`, `VERSION`, `/frontend`, `/infra/reset`.
- **In scope:** retarget npm + every CI/container `frontend` path → `web`; delete the three dead docker
  Dependabot entries (they resolve to nothing — removing them changes no real coverage and stops phantom scans).
- **Out of scope — deferred to its own future CR:** the container-image **build + publish** pipeline
  (`container.yml`'s publish job, the missing root `Dockerfile` + `VERSION`). It fails closed today (no insecure
  image ships), so standing up a real image pipeline is a standalone piece, not a path fix. Untouched here
  except the test-gate's web path. (Worth an inbox row when we pick it up.)
- **No product code, no wire/contract change.** CI/config only; nothing the trust-chain guards cover — but they
  must not regress.
- **Verify:** no `frontend/` PATH reference remains (npm `directory`, `working-directory`,
  `cache-dependency-path`, the codeql `frontend/**` filter); the job/step **names** keep "Frontend" (referenced
  as required checks, so renaming is out of scope). YAML parses. Real end-to-end proof is the next push's
  Actions run + the first Dependabot PR opened against `web/`.

## Phases

- [x] 1 ci — `.github/dependabot.yml`: npm `directory` → `/web`; removed the three dead docker entries
  (`/frontend`, `/infra/reset`, `/` — no Dockerfile yet). `ci.yml` + `container.yml`: every `frontend`
  `working-directory` + `cache-dependency-path` → `web`. `codeql.yml`: the `javascript-typescript`
  `frontend/**` path filter → `web/**`. · **Done:** no `frontend/` PATH ref remains (grep clean); dependabot +
  all three workflows parse as YAML; `web/package-lock.json` confirmed present. The container publish job
  (`VERSION` + `Dockerfile`) left untouched (deferred).

## Definition of done

The three `.github/` files reference only existing directories; no dead `/frontend`, `/infra/reset`, or
root-`Dockerfile` path remains outside the deliberately-deferred publish job; the trust-chain guards stay green.

## Retrospective

- **Planning miss:** the CR scoped three files (dependabot, ci, container) but a fourth — `codeql.yml`'s
  `javascript-typescript` `frontend/**` filter — carried the same staleness, caught only by a repo-wide grep at
  verify time, not in planning. A "find every stale `frontend` path" grep belongs in scoping, not just verify.
- **Scope held:** the container publish pipeline (missing `Dockerfile`/`VERSION`) and `pages.yml`'s
  `site/`/`docs/api` staleness were consciously left out as separate concerns, not scope-crept.
- **Next time:** for a "rename a path across config" CR, grep the whole repo for the old path up front and
  enumerate every hit in the plan.
