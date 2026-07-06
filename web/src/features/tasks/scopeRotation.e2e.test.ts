// cr-0092 live e2e (VEYQ_E2E=1): the async group-key-rotation backstop, end-to-end against a REAL backend +
// MariaDB with REAL crypto — no mocks. Three actors on one instance (serialized: each `become()` swaps the
// session token + unlocks that actor's vault):
//   A = tenant OWNER (server owner + tenant owner) — the remover; NOT a key-holder of the group.
//   B = group key-holder (owns group G, holds K_SC).
//   C = co-sharer of G (a key-holder after grant-delivery) — the removed member.
//
// Flow: A removes C from the tenant → the cascade cuts C's co-shared G membership and originates a
// SCOPE_ROTATION job (A can't rotate G — A holds no key there). A/removed-C cannot claim it; B (the remaining
// key-holder) claims it via runScopeRotationWorker, mints E_1 + re-grants the survivors on-device, completes.
// Asserts the rotation genuinely happened on the live backend: epoch 0 → 1, a real chain-link at epoch 1, B
// re-granted at epoch 1, C gone, and the job cleared. The pure ex-member lockout crypto (E_0 can't derive E_1)
// is unit-tested in scopes.test.ts / epochs.test.ts; this proves the whole mechanism is wired live.
//
// The tenant domain is `localhost` so the same-origin fetch client (Host: localhost:8080 → port-stripped
// `localhost`) resolves it — no manual Host juggling. Run against a FRESH instance:
//   docker compose -f infra/docker-compose.yml down -v && up -d  → restart backend  →
//   VEYQ_E2E=1 npx vitest run scopeRotation.e2e
import { describe, it, expect } from 'vitest'
import * as v1 from '../../shared/crypto/v1'
import { fromB64std } from '../../shared/base64'
import { session } from '../../app/session'
import { setUnlocked } from '../../app/vault'
import { login } from '../account/login/login'
import { buildAccount, type BuiltAccount } from '../account/register/accountBirth'
import { setupApi, tasksApi } from '../../api/client'
import { createTenant, acceptInvite, registerAndAccept, scopeGrant } from '../admin/createTenant/tenants'
import { inviteMember, addGroupMember, listScopeMembers, removeTenantMember } from '../spaces/members'
import { createGroup, listMyScopes, listScopeEpochs, openScopeById } from '../spaces/scopes'
import { runGrantDeliveryWorker } from './grantDeliveryWorker'
import { runScopeRotationWorker } from './scopeRotationWorker'
import { SCOPE_ROTATION } from './capabilityTags'

// `veyq.localhost` resolves to loopback (RFC 6761) and is a VALID dotted domain, so it serves as both the
// tenant domain AND the fetch Host — the same-origin client's Host header then resolves the tenant with no DB
// remap and no invalid-domain rejection in the invite mailer.
const HOST = process.env.VEYQ_HOST ?? 'veyq.localhost'
const BASE = process.env.VEYQ_BASE ?? `http://${HOST}:8080`
const stamp = process.env.VEYQ_STAMP ?? 'e2e' // vary emails/usernames per run so re-runs on a non-fresh DB don't collide

type Actor = { built: BuiltAccount; email: string; password: string; username: string; userId?: string }

function mkActor(local: string): Actor {
  return { built: null as unknown as BuiltAccount, email: `${local}-${stamp}@e2e.test`, password: `Pw-${local}-${stamp}-9xQ!`, username: `${local}${stamp}` }
}

/** Swap the process-wide session + vault to this actor (serialized single-actor execution). Re-derives the
 *  Master Key from the account's PASSWORD wrap (the identity private key is the one buildAccount already holds). */
async function become(a: Actor): Promise<void> {
  await login(a.email, a.password)
  const wrap = a.built.material.masterKeyWraps.find((w) => w.method === 'PASSWORD')!
  const { params, salt } = v1.decodeKdfParams(fromB64std(wrap.kdfParams))
  const kek = v1.kekPasswordSecret(fromB64std(a.built.emergencyKit.secretKey), a.password, salt, params)
  const masterKey = v1.symUnwrap(kek, fromB64std(wrap.wrappedKey), 'master-key')
  setUnlocked(masterKey, a.built.identity.priv)
}

async function claimRotation() {
  return (await tasksApi().claimTask({ claimRequest: { capabilityTags: [SCOPE_ROTATION] } })) ?? null
}

describe.skipIf(!process.env.VEYQ_E2E)('cr-0092 async rotation backstop (live, real crypto)', () => {
  it('a tenant-member removal queues a rotation a remaining key-holder completes; A/ex-member cannot', async () => {
    session.apiBase = BASE
    const setup = await setupApi().getSetupStatus()
    expect(setup.setupRequired, 'run against a FRESH instance (setupRequired=true)').toBe(true)
    const salt = fromB64std(setup.instanceSalt)

    const A = mkActor('a')
    const B = mkActor('b')
    const C = mkActor('c')
    for (const x of [A, B, C]) x.built = buildAccount(x.email, x.password, salt)

    // --- A: cold-birth the server owner, then create + accept the tenant (domain `localhost`) as its OWNER ---
    const m = A.built.material
    await setupApi().createServerOwner({
      createServerOwnerRequest: {
        email: A.email, suiteId: m.suiteId, identityPublicKey: m.identityPublicKey,
        wrappedIdentityPrivateKey: m.wrappedIdentityPrivateKey, authPublicKey: m.authPublicKey,
        masterKeyWraps: m.masterKeyWraps, recoveryMasterKeyTag: m.recoveryMasterKeyTag,
      },
    })
    await become(A)
    const tenant = await createTenant('E2E Rotation', HOST, A.email)
    await acceptInvite(tenant.bootstrapSecret, A.username, scopeGrant(v1.hybridPublic(A.built.identity.priv)))

    // --- B, C: onboard as tenant MEMBERS (grant-less cold-birth accept via an owner-minted invite) ---
    for (const x of [B, C]) {
      const invite = await inviteMember(tenant.tenantId, x.email) // owner-gated; returns the copyable secret
      const accepted = await registerAndAccept(invite.bootstrapSecret, x.username, x.built.material)
      x.userId = accepted.userId
    }

    // --- B: create group G, add C, deliver K_SC to C (real foreign wrap) so C is a genuine co-holder ---
    await become(B)
    const gG = await createGroup('Shared G')
    await addGroupMember(gG, C.username)
    const openedB = await openScopeById(gG)
    expect(openedB, 'B opens its own group').toBeTruthy()
    await runGrantDeliveryWorker({ scopeId: gG, key: openedB!.key }) // wraps K_SC (E_0) to C's identity
    const before = await listScopeMembers(gG)
    expect(before.find((x) => x.userId === C.userId)?.hasGrant, 'C is a co-holder before removal').toBe(true)
    expect((await listMyScopes()).find((s) => s.id === gG)?.currentEpoch, 'G starts at epoch 0').toBe(0)

    // --- A: remove C from the tenant → cascade cuts C's co-shared G membership + queues SCOPE_ROTATION for G ---
    await become(A)
    await removeTenantMember(tenant.tenantId, C.userId!)

    // A holds no grant in G (A was never in it) → the removing owner cannot rotate: nothing claimable.
    expect(await claimRotation(), 'the removing owner is not a key-holder → no rotation claimable').toBeNull()

    // The removed member no longer holds G's grant → cannot claim either.
    await become(C)
    expect(await claimRotation(), 'the removed member cannot claim the rotation').toBeNull()

    // --- B: the remaining key-holder drains the queued rotation on-device (claim → rotate → complete) ---
    await become(B)
    const rotated = await runScopeRotationWorker()
    expect(rotated, 'B completed exactly one queued rotation').toBe(1)

    // The rotation genuinely advanced the epoch on the live backend, re-granted the survivor, and left a chain-link.
    const gAfter = (await listMyScopes()).find((s) => s.id === gG)
    expect(gAfter?.currentEpoch, 'G advanced to epoch 1').toBe(1)
    expect(gAfter?.grantedEpoch, 'B was re-granted at the new epoch').toBe(1)
    const links = await listScopeEpochs(gG)
    expect(links.some((l) => l.epoch === 1 && l.wrappedPrev.length > 0), 'a real E_1←E_0 chain-link exists').toBe(true)
    const after = await listScopeMembers(gG)
    expect(after.some((x) => x.userId === C.userId), 'the ex-member is gone from G').toBe(false)

    // The job is DONE — idempotent + coalescing: nothing more is claimable.
    expect(await runScopeRotationWorker(), 'the rotation job is cleared').toBe(0)
  }, 180_000)
})
