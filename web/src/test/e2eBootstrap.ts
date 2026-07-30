// The ONE way a live e2e suite (`VEYQ_E2E=1`) gets a usable instance: cold-birth it over the real HTTP API.
//
// Before this existed there were two bootstrap contracts, and they were incompatible. `scopeRotation.e2e` asserted
// `setupRequired === true` and birthed everything itself; the three memory suites read `web/scripts/.seed/
// dev-seed.json`, whose producer had to reach into the dev DB with `docker exec … UPDATE veyq_tenant` because
// `POST /api/tenants` rejects a numeric domain. That DB poke is not executable from CI, so the seeded contract
// could never run under `scripts/run_web_e2e.py` — which is why every suite now takes this path instead.
//
// The block is short but pulls eight symbols from five modules, so it is extracted rather than copied three
// times. `web/scripts/seed-dev.mts` is deliberately untouched: it still serves the local IntelliJ dev flow.
//
// One tenant domain serves every suite: `veyq.localhost` is a VALID dotted domain (so `POST /api/tenants` and the
// invite mailer's hostname check both accept it) AND resolves to loopback per RFC 6761 (so it works as the fetch
// Host with no `/etc/hosts` entry). It has to be a real host in the URL, not a spoofed header: tenant resolution
// is server-side from the request `Host`, and fetch/undici strips a manually set one.
import { expect } from 'vitest'
import * as v1 from '../shared/crypto/v1'
import { fromB64std } from '../shared/base64'
import { session } from '../app/session'
import { setUnlocked } from '../app/vault'
import { login } from '../features/account/login/login'
import { buildAccount, type BuiltAccount } from '../features/account/register/accountBirth'
import { setupApi } from '../api/client'
import { createTenant, acceptInvite, registerAndAccept, scopeGrant } from '../features/admin/createTenant/tenants'
import { inviteMember } from '../features/spaces/members'

/** Tenant domain and API base — see the module header for why this exact host. */
export const HOST = process.env.VEYQ_HOST ?? 'veyq.localhost'
export const BASE = process.env.VEYQ_BASE ?? `http://${HOST}:8080`

/**
 * Varies emails/usernames per run so a re-run against a non-fresh DB does not collide — and, just as important,
 * so the per-recipient invite budget (3 per recipient / 3600 s) sees a new recipient each run instead of tripping.
 * `scripts/run_web_e2e.py` sets it per run; a hand run gets the stable default.
 */
export const stamp = process.env.VEYQ_STAMP ?? 'e2e'

export type Actor = {
  built: BuiltAccount
  email: string
  password: string
  username: string
  userId?: string
}

/** An actor's identity, derived from a short local part so a suite can name its actors `a`, `b`, `c`. */
export function mkActor(local: string): Actor {
  return {
    built: null as unknown as BuiltAccount,
    email: `${local}-${stamp}@e2e.test`,
    password: `Pw-${local}-${stamp}-9xQ!`,
    username: `${local}${stamp}`,
  }
}

/**
 * Swap the process-wide session + vault to this actor (the suites run serialized, single-actor).
 *
 * Re-derives the Master Key from the account's PASSWORD wrap rather than calling the app's `unlockMasterKey`,
 * for a reason specific to a cold-birthed actor: `unlockMasterKey` opens the vault from the SERVER's key
 * material, while the identity private key here is the one `buildAccount` already holds in memory. Going
 * through the server path would re-unwrap what we already have and couple every actor swap to the device-tier
 * logic in `openVault`, which no suite is testing.
 */
export async function become(a: Actor): Promise<void> {
  await login(a.email, a.password)
  const wrap = a.built.material.masterKeyWraps.find((w) => w.method === 'PASSWORD')!
  const { params, salt } = v1.decodeKdfParams(fromB64std(wrap.kdfParams))
  const kek = v1.kekPasswordSecret(fromB64std(a.built.emergencyKit.secretKey), a.password, salt, params)
  const masterKey = v1.symUnwrap(kek, fromB64std(wrap.wrappedKey), 'master-key')
  setUnlocked(masterKey, a.built.identity.priv)
}

export type BirthedInstance = {
  /** The server owner, who is also the tenant's owner — signed in and unlocked when this resolves. */
  owner: Actor
  /** `tenantId` for member invites; `bootstrapSecret` is already spent on the owner's own accept. */
  tenant: Awaited<ReturnType<typeof createTenant>>
  /** The instance salt every further `buildAccount` in this suite must use. */
  salt: Uint8Array
}

/**
 * Cold-birth a fresh instance: assert it IS fresh, mint the server owner, create the tenant and accept it as
 * that owner. Leaves `session`/vault unlocked as the owner.
 *
 * The freshness assertion is load-bearing, not defensive: a suite that silently ran against a warm instance
 * would be asserting against another run's state. `run_web_e2e.py` gives each file its own database, so
 * `setupRequired` is the cheap end-to-end confirmation that it actually did.
 */
export async function birthInstance(tenantName: string): Promise<BirthedInstance> {
  session.apiBase = BASE
  const setup = await setupApi().getSetupStatus()
  expect(setup.setupRequired, 'run against a FRESH instance (setupRequired=true)').toBe(true)
  const salt = fromB64std(setup.instanceSalt)

  const owner = mkActor('a')
  owner.built = buildAccount(owner.email, owner.password, salt)

  const m = owner.built.material
  await setupApi().createServerOwner({
    createServerOwnerRequest: {
      email: owner.email, suiteId: m.suiteId, identityPublicKey: m.identityPublicKey,
      wrappedIdentityPrivateKey: m.wrappedIdentityPrivateKey, authPublicKey: m.authPublicKey,
      masterKeyWraps: m.masterKeyWraps, recoveryMasterKeyTag: m.recoveryMasterKeyTag,
    },
  })
  await become(owner)
  const tenant = await createTenant(tenantName, HOST, owner.email)
  await acceptInvite(tenant.bootstrapSecret, owner.username, scopeGrant(v1.hybridPublic(owner.built.identity.priv)))
  return { owner, tenant, salt }
}

/**
 * Onboard a tenant MEMBER: an owner-minted invite, redeemed by a grant-less cold-birth accept. The caller must
 * currently `become()` the owner (the invite endpoint is owner-gated). Leaves the session as the OWNER, not the
 * new member — the caller decides who acts next.
 *
 * The `bootstrapSecret` comes off the HTTP response, which is the only channel available here: the dev-invite
 * file that `seed-dev.mts` reads is never written by a booted fast-jar (`TenantInviteMailer` early-returns in
 * `LaunchMode.NORMAL` before the append, so a production runtime cannot leak the secret to disk).
 */
export async function onboardMember(tenantId: string, actor: Actor, salt: Uint8Array): Promise<Actor> {
  actor.built = buildAccount(actor.email, actor.password, salt)
  const invite = await inviteMember(tenantId, actor.email)
  const accepted = await registerAndAccept(invite.bootstrapSecret, actor.username, actor.built.material)
  actor.userId = accepted.userId
  return actor
}
