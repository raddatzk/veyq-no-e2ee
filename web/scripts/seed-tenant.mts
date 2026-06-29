// Local dev seeder (cr-0042): mint a tenant for a given Host domain + a fresh tenant-owner account with a
// KNOWN password + Secret Key, so the native/web clients can reach the Memory shell against a local instance
// (the personal scope is tenant-scoped by Host — see web/CLAUDE.md). Closes the seed-tenant gap flagged in
// cr-0039's retrospective. Operator-blind: only ciphertext + public keys are POSTed; no password leaves here.
//
// Flow (Server-Owner must already exist — run seed-owner.mts first):
//   1. log in as the Server Owner (challenge-response, Model B auth) to get an admin session,
//   2. POST /api/tenants → a PENDING tenant for `domain`, inviting `ownerEmail` (the dev mailer LOGS the
//      bootstrap-secret invite link — we read it back from the backend log),
//   3. cold-birth the tenant-owner account + seal the first scope grant to its own identity, and
//      POST /api/tenants/accept/register → activates the tenant (ACTIVE) with the OWNER membership.
//
//   cd web && VEYQ_LOG=/tmp/veyq-cr0042.log npx tsx scripts/seed-tenant.mts
//
// Env: VEYQ_BASE (http://localhost:8080), VEYQ_OWNER_EMAIL/PASSWORD (the Server Owner, default the
// seed-owner values), VEYQ_DOMAIN (127.0.0.1 — must be a dotted hostname; `localhost` is rejected by the
// backend's mailer hostname guard), VEYQ_TENANT_OWNER_EMAIL/PASSWORD/NAME (the new tenant owner), VEYQ_LOG
// (the backend log file carrying the invite link).
import { readFileSync } from 'node:fs'
import { fromB64std, b64std } from '../src/shared/base64'
import { buildAccount } from '../src/features/account/register/accountBirth'
import * as v1 from '../src/shared/crypto/v1'

const BASE = process.env.VEYQ_BASE ?? 'http://localhost:8080'
const ownerEmail = process.env.VEYQ_OWNER_EMAIL ?? 'kevin@veyq.test'
const ownerPassword = process.env.VEYQ_OWNER_PASSWORD ?? 'correct horse battery staple'
const domain = process.env.VEYQ_DOMAIN ?? '127.0.0.1'
const tenantOwnerEmail = process.env.VEYQ_TENANT_OWNER_EMAIL ?? 'owner@veyq.test'
const tenantOwnerPassword = process.env.VEYQ_TENANT_OWNER_PASSWORD ?? 'correct horse battery staple'
const tenantOwnerName = process.env.VEYQ_TENANT_OWNER_NAME ?? 'Tenant Owner'
const tenantName = process.env.VEYQ_TENANT_NAME ?? 'local'
const logFile = process.env.VEYQ_LOG ?? '/tmp/veyq-cr0042.log'

const json = (r: Response) => r.json() as Promise<any>
const post = (path: string, body: unknown, token?: string) =>
  fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  })

// 1 — log in as the Server Owner (sync argon — no Web Worker needed off the main app).
const challenge = await json(await post('/api/login/challenge', { email: ownerEmail }))
const instanceSalt = fromB64std(challenge.instanceSalt)
const auth = v1.deriveAuthKeypair(ownerPassword, v1.deriveAuthSalt(instanceSalt, ownerEmail), v1.ARGON)
const signature = v1.authSign(auth.privateKey, fromB64std(challenge.nonce))
const verify = await post('/api/login/verify', { email: ownerEmail, signature: b64std(signature) })
if (!verify.ok) { console.error('server-owner login failed', verify.status, await verify.text()); process.exit(1) }
const token = (await json(verify)).accessToken as string

// 2 — create a PENDING tenant for the Host domain, inviting the new owner.
const created = await post('/api/tenants', { tenantName, domain, ownerEmail: tenantOwnerEmail }, token)
if (!created.ok) { console.error('createTenant failed', created.status, await created.text()); process.exit(1) }
const { tenantId } = await json(created)

// Read the bootstrap secret back from the dev mailer's log line (the invite link's URL fragment). Strip
// ANSI colour codes first — quarkusDev colourises the console even under --console=plain.
// eslint-disable-next-line no-control-regex
const log = readFileSync(logFile, 'utf8').replace(/\x1b\[[0-9;]*m/g, '')
const matches = [...log.matchAll(/Tenant invite for [^\s]+ \(expires [^)]+\): https?:\/\/[^/]+\/tenant\/accept#(\S+)/g)]
const bootstrapSecret = matches.at(-1)?.[1]
if (!bootstrapSecret) { console.error(`no invite link found in ${logFile} — is the backend logging to it?`); process.exit(1) }

// 3 — cold-birth the tenant owner + seal the first scope grant to its own identity, then accept (public).
const built = buildAccount(tenantOwnerEmail, tenantOwnerPassword, instanceSalt)
const scopeRootKey = v1.randomBytes(32)
const idPub = { x: built.identity.pub.kemX, m: built.identity.pub.kemM }
const grant = b64std(v1.asymWrap(idPub, scopeRootKey, 'scope-grant', { nonce: v1.randomBytes(12) }))
const accepted = await post('/api/tenants/accept/register', {
  bootstrapSecret,
  account: {
    displayName: tenantOwnerName,
    suiteId: built.material.suiteId,
    identityPublicKey: built.material.identityPublicKey,
    wrappedIdentityPrivateKey: built.material.wrappedIdentityPrivateKey,
    authPublicKey: built.material.authPublicKey,
    masterKeyWraps: built.material.masterKeyWraps,
    recoveryMasterKeyTag: built.material.recoveryMasterKeyTag,
  },
  grant,
})
if (!accepted.ok) { console.error('accept/register failed', accepted.status, await accepted.text()); process.exit(1) }

console.log(JSON.stringify({
  ok: true, tenantId, domain, base: BASE,
  email: tenantOwnerEmail, password: tenantOwnerPassword, secretKey: built.emergencyKit.secretKey,
}, null, 2))
