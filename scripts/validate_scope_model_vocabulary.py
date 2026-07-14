#!/usr/bin/env python3
"""Guard the scope model's vocabulary — docs name the model the code actually implements (cr-0166).

Two phantoms had drifted into `docs/`: an `ADMIN` role and an asymmetric scope keypair. Both were worse than a
gap — a reader who *checked* was confirmed in the wrong belief. cr-0166 corrected the docs; this guard is why
neither can silently return.

Unlike cr-0162/0163 — where the defect was a *condition* inside a query, and only a probe test could hold it —
this invariant **is** vocabulary: certain nouns must not appear in `docs/` because the things they name do not
exist. A grep expresses it exactly, so a grep is the guard.

Three invariants:

1. **No scope keypair.** `K_SC` is a forward chain of random 32-byte content-key epochs (ADR 0024); a grant is
   `asymWrap(memberIdentityPub, E_latest, "scope-grant")` — asymmetric *transport* of a **symmetric** payload,
   pinned at 32 bytes by `contracts/crypto/vectors/v1.json` (`asym_scope_grant.plaintext`). So a "scope
   private/public key", a "scope keypair", and a `wrappedScopePrivateKey` field are all phantoms. The phrases
   have **no** legitimate use — the *identity* keypair and the *invite-wrapping* keypair are both real and both
   named differently — so this is a flat zero-hit check with no allowlist.

2. **No `ADMIN` role.** The vocabulary is `OWNER` / `MEMBER` at every level (Server, Tenant, Group — ADR 0007,
   ADR 0014). The one legitimate use is the **negative** form ("no `ADMIN`"), which is precisely the signpost a
   future reader greps for — so that, and only that, is allowed. Rewording those ADRs to make this grep simpler
   would destroy the signpost and is not an acceptable fix for a red here.

3. **The role constants match the contract.** `MembershipEntity`'s role constants equal the role enum in
   `contracts/api/scopes.openapi.yaml`. This is what makes cr-0166's deletion of the dead `const val ADMIN`
   stick: it fails the day a third constant returns, and it needs no allowlist either.

Scope note: the vocabulary checks read `docs/` **state** only and skip `docs/inbox.md`, which is *flow* — raw
topics that may quote the old wording while pending (docs/ai/README.md, state vs. flow). Every cr-0166
confirm-command excluded it too.
"""
from __future__ import annotations

import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
DOCS_SKIP = {DOCS / "inbox.md"}

ENTITY = ROOT / "backend" / "src" / "main" / "kotlin" / "com" / "veyq" / "app" / "shared" / "membership" / "MembershipEntity.kt"
CONTRACT = ROOT / "contracts" / "api" / "scopes.openapi.yaml"
ROLE_SCHEMA = "SetMemberRoleRequest"

# The cr-0166 inventory grep, promoted verbatim to a permanent check.
SCOPE_ASYM = re.compile(
    r"scope keypair|scope key pair|scope-keypair|scope private key|scope public key|wrappedScopePrivateKey",
    re.IGNORECASE)

ADMIN_MENTION = re.compile(r"`ADMIN`")
ADMIN_NEGATED = re.compile(r"no\s+`ADMIN`")

# The role constants in the entity's companion. The file is the (user, scope, role, grant) entity and holds no
# other `const val` — if that ever changes, this reds loudly rather than silently reading the wrong set.
KOTLIN_CONST = re.compile(r'const val (\w+)\s*=\s*"([^"]*)"')

errors: list[str] = []


def docs_files() -> list[pathlib.Path]:
    return [p for p in sorted(DOCS.rglob("*.md")) if p not in DOCS_SKIP]


def check_vocabulary(paths: list[pathlib.Path]) -> None:
    for path in paths:
        rel = path.relative_to(ROOT)
        for lineno, line in enumerate(path.read_text().splitlines(), start=1):
            hit = SCOPE_ASYM.search(line)
            if hit:
                errors.append(
                    f'{rel}:{lineno} says "{hit.group(0)}" — the scope key is **symmetric** (a 32-byte '
                    f"content-key epoch, ADR 0024) and a grant is asymmetric transport of it. Name `K_SC` / the "
                    f"grant / `wrappedGrant`; if you mean a real keypair, it is the *identity* or the "
                    f"*invite-wrapping* one")
            if ADMIN_MENTION.search(line) and not ADMIN_NEGATED.search(line):
                errors.append(
                    f"{rel}:{lineno} names an `ADMIN` role — the vocabulary is `OWNER` / `MEMBER` at every "
                    f'level (ADR 0007). Only the negative form ("no `ADMIN`") is legitimate')


def contract_roles() -> set[str] | None:
    schema = yaml.safe_load(CONTRACT.read_text()).get("components", {}).get("schemas", {}).get(ROLE_SCHEMA, {})
    enum = schema.get("properties", {}).get("role", {}).get("enum")
    if not enum:
        errors.append(
            f"{CONTRACT.relative_to(ROOT)} has no {ROLE_SCHEMA}.properties.role.enum — this guard's contract "
            f"anchor is gone; the role enum moved or broke")
        return None
    return set(enum)


def entity_roles() -> set[str] | None:
    consts = KOTLIN_CONST.findall(ENTITY.read_text())
    if not consts:
        errors.append(
            f"{ENTITY.relative_to(ROOT)} declares no `const val` role constants — this guard's code anchor is "
            f"gone; the role vocabulary moved")
        return None
    return {value for _name, value in consts}


def main() -> int:
    for anchor in (DOCS, ENTITY, CONTRACT):
        if not anchor.exists():
            print(f"SCOPE MODEL VOCABULARY VALIDATION FAILED:\n  - missing {anchor.relative_to(ROOT)}")
            return 1

    paths = docs_files()
    check_vocabulary(paths)

    contract, entity = contract_roles(), entity_roles()
    if contract is not None and entity is not None and contract != entity:
        errors.append(
            f"role vocabulary drift: {ENTITY.relative_to(ROOT)} has {sorted(entity)} but "
            f"{CONTRACT.relative_to(ROOT)}'s {ROLE_SCHEMA}.role enum has {sorted(contract)} — the entity and the "
            f"contract must name exactly the same roles (ADR 0007)")

    if errors:
        print("SCOPE MODEL VOCABULARY VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1

    print(
        f"scope model vocabulary OK: {len(paths)} docs carry no scope-keypair or `ADMIN`-role phantom; "
        f"MembershipEntity roles == the contract's {ROLE_SCHEMA}.role enum ({sorted(contract)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
