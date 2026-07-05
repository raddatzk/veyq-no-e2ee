// Bootstrap User — create the local dev principal from .run/.env and record its derived key material back into
// the same file. Reuses the real cold-account-birth crypto (buildAccount) + the existing endpoints, so it is
// operator-blind: only ciphertext + public keys are POSTed; the password never leaves this process.
//
// The human fills the 5 inputs in .run/.env (copy from .run/.env.example):
//   VEYQ_USERNAME, VEYQ_PASSWORD, VEYQ_EMAIL, VEYQ_TENANT, VEYQ_TENANT_DOMAIN
// This script creates ONE veyq_user that is both Server Owner AND the tenant owner (via the existing-user
// accept path — no second account) with a personal scope, then writes back:
//   VEYQ_SECRET_KEY, VEYQ_WRAPPED_MASTERKEY
// Run it against a FRESH backend (Bootstrap DB + Garage → Run Backend first). Re-run needs a `down -v` wipe:
// the Secret Key is random per birth, so a second run can't reproduce an existing account.
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { request as httpRequest } from 'node:http'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'
import * as v1 from '../src/shared/crypto/v1'
import { fromB64std, b64std } from '../src/shared/base64'
import { buildAccount } from '../src/features/account/register/accountBirth'
import { awaitInvite } from './devSeed.mts'

const ENV_FILE = fileURLToPath(new URL('../../.run/.env', import.meta.url))
const BASE = process.env.VEYQ_BASE ?? 'http://localhost:8080'
const DB_CONTAINER = process.env.VEYQ_DB_CONTAINER ?? 'veyq-mariadb'
const INVITE_FILE = process.env.VEYQ_INVITE_FILE ?? fileURLToPath(new URL('../../backend/build/dev-invites.jsonl', import.meta.url))

function fail(msg: string): never { console.error(`bootstrap-user: ${msg}`); process.exit(1) }

// --- .run/.env I/O (a tiny dotenv: KEY=VALUE lines) -------------------------------------------------------
function readEnv(): Record<string, string> {
  if (!existsSync(ENV_FILE)) fail(`no ${ENV_FILE} — copy .run/.env.example to .run/.env and fill it in`)
  const out: Record<string, string> = {}
  for (const line of readFileSync(ENV_FILE, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/)
    if (m && !line.trimStart().startsWith('#')) out[m[1]] = m[2].trim()
  }
  return out
}
function writeEnv(fields: Record<string, string>): void {
  const order = ['VEYQ_USERNAME', 'VEYQ_EMAIL', 'VEYQ_PASSWORD', 'VEYQ_SECRET_KEY', 'VEYQ_WRAPPED_MASTERKEY', 'VEYQ_TENANT', 'VEYQ_TENANT_DOMAIN']
  const body = order.map((k) => `${k}=${fields[k] ?? ''}`).join('\n')
  writeFileSync(ENV_FILE, `# Local dev principal (gitignored). Inputs filled by you; SECRET_KEY + WRAPPED_MASTERKEY\n# filled by \`npm run bootstrap:user\`. Throwaway dev creds for a local instance — never committed.\n${body}\n`)
}

// --- HTTP: plain fetch for non-tenant calls; node:http for tenant-scoped (fetch drops a manual Host header) -
const json = (r: Response) => r.json() as Promise<any>
const post = (path: string, b: unknown, token?: string) =>
  fetch(`${BASE}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(b) })
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const backendPort = new URL(BASE).port || '80'

/** A tenant-scoped call over node:http (honours an explicit Host, which fetch/undici strips). */
function tenant(method: string, path: string, token: string, host: string, body?: unknown): Promise<{ status: number; body: any }> {
  const data = body === undefined ? undefined : JSON.stringify(body)
  return new Promise((resolve, reject) => {
    const req = httpRequest({
      host: '127.0.0.1', port: backendPort, path, method,
      headers: { host, authorization: `Bearer ${token}`, ...(data ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) } : {}) },
    }, (res) => {
      let buf = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (buf += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, body: buf ? JSON.parse(buf) : undefined }))
    })
    req.on('error', reject)
    if (data) req.write(data)
    req.end()
  })
}

async function awaitBackend(timeoutMs = 90_000): Promise<any> {
  const deadline = Date.now() + timeoutMs
  let last = ''
  while (Date.now() < deadline) {
    try { const r = await fetch(`${BASE}/api/setup`); if (r.ok) return await json(r); last = `status ${r.status}` }
    catch (e) { last = (e as Error).message }
    await sleep(750)
  }
  fail(`backend did not answer ${BASE}/api/setup within ${timeoutMs}ms (${last})`)
}

// --- run --------------------------------------------------------------------------------------------------
const env = readEnv()
const username = env.VEYQ_USERNAME || fail('VEYQ_USERNAME missing in .run/.env')
const password = env.VEYQ_PASSWORD || fail('VEYQ_PASSWORD missing in .run/.env')
const email = env.VEYQ_EMAIL || fail('VEYQ_EMAIL missing in .run/.env')
const tenantName = env.VEYQ_TENANT || fail('VEYQ_TENANT missing in .run/.env')
const domain = (env.VEYQ_TENANT_DOMAIN || fail('VEYQ_TENANT_DOMAIN missing in .run/.env')).toLowerCase()

const numeric = /^[0-9.]+$/.test(domain)
const createDomain = numeric ? (process.env.VEYQ_CREATE_DOMAIN ?? 'local.veyq.test') : domain

const setup = await awaitBackend()
if (!setup.setupRequired) fail('instance already set up — wipe it first (Bootstrap DB + Garage: `docker compose … down -v`), the Secret Key is random per birth')

// 1 — cold-birth the Server Owner
const built = buildAccount(email, password, fromB64std(setup.instanceSalt))
const m = built.material
const ownerRes = await post('/api/setup/owner', {
  email,
  suiteId: m.suiteId, identityPublicKey: m.identityPublicKey, wrappedIdentityPrivateKey: m.wrappedIdentityPrivateKey,
  authPublicKey: m.authPublicKey, masterKeyWraps: m.masterKeyWraps, recoveryMasterKeyTag: m.recoveryMasterKeyTag,
})
if (!ownerRes.ok) fail(`setup/owner failed: ${ownerRes.status} ${await ownerRes.text()}`)
console.log(`owner: created ${email}`)

// 2 — log in (account-level token)
const ch = await json(await post('/api/login/challenge', { email }))
const auth = v1.deriveAuthKeypair(password, v1.deriveAuthSalt(fromB64std(ch.instanceSalt), email), v1.ARGON)
const verify = await post('/api/login/verify', { email, signature: b64std(v1.authSign(auth.privateKey, fromB64std(ch.nonce))) })
if (!verify.ok) fail(`login failed: ${verify.status} ${await verify.text()}`)
const token = (await json(verify)).accessToken as string

// 3 — the configured-domain tenant, accepted as the existing user (TENANT grant sealed to our own identity)
const created = await post('/api/tenants', { tenantName, domain: createDomain, ownerEmail: email }, token)
if (!created.ok) fail(`createTenant failed: ${created.status} ${await created.text()}`)
const { tenantId } = await json(created)
const bootstrapSecret = await awaitInvite(INVITE_FILE, email)
if (numeric) {
  execFileSync('docker', ['exec', DB_CONTAINER, 'mariadb', '-uveyq', '-pchange-me', 'veyq',
    '-e', `UPDATE veyq_tenant SET domain='${domain}' WHERE id='${tenantId}'`], { stdio: 'inherit' })
}
const tenantGrant = b64std(v1.asymWrap(v1.hybridPublic(built.identity.priv), v1.randomBytes(32), 'scope-grant', { nonce: v1.randomBytes(12) }))
const accepted = await tenant('POST', '/api/tenants/accept', token, domain, { bootstrapSecret, username, grant: tenantGrant })
if (accepted.status >= 300) fail(`accept failed: ${accepted.status} ${JSON.stringify(accepted.body)}`)
console.log(`tenant: ${domain} created + accepted`)

// 4 — the owner's PERSONAL scope
const personalGrant = b64std(v1.asymWrap(v1.hybridPublic(built.identity.priv), v1.randomBytes(32), 'scope-grant', { nonce: v1.randomBytes(12) }))
const scopeRes = await tenant('POST', '/api/scopes', token, domain, { wrappedGrant: personalGrant })
if (scopeRes.status >= 300) fail(`createPersonalScope failed: ${scopeRes.status} ${JSON.stringify(scopeRes.body)}`)
console.log(`scope: personal scope ${scopeRes.body?.scopeId ?? ''} created`)

// 5 — record the derived material back into .run/.env
const passwordWrap = m.masterKeyWraps.find((w) => w.method === 'PASSWORD')!
writeEnv({
  VEYQ_USERNAME: username, VEYQ_EMAIL: email, VEYQ_PASSWORD: password,
  VEYQ_SECRET_KEY: built.emergencyKit.secretKey, VEYQ_WRAPPED_MASTERKEY: passwordWrap.wrappedKey,
  VEYQ_TENANT: tenantName, VEYQ_TENANT_DOMAIN: domain,
})
console.log(`\nbootstrap-user complete → ${ENV_FILE}`)
console.log(`  ${email} @ ${domain} — log in with VEYQ_EMAIL / VEYQ_PASSWORD / VEYQ_SECRET_KEY`)
