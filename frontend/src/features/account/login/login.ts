// uc.account.login (web) — authenticate, fetch the wrapped key bag, and unlock the master key locally.
// The server returns ciphertext only; the master/identity keys are recovered here (auth != unlock).
import * as v1 from '../../../shared/crypto/v1'

// Must match the params the master-key wrap was made with (see setupServer.ts).
const ARGON = { t: 3, m: 8192, p: 1, dkLen: 32 }

function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4))
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

type Blob = { suiteId: string; bytes: string }
type MasterKeyWrap = { unlockMethod: string; suiteId: string; wrappedKey: string; kdfParams?: string }
export type KeyBag = {
  identityPublicKey: { suiteId: string; kemPublicKey: string; signPublicKey: string }
  wrappedIdentityPrivateKey: Blob
  masterKeyWraps: MasterKeyWrap[]
}
export type LoginResponse = {
  sessionToken: string
  user: { id: string; email: string; displayName: string; serverRole: string }
  keyBag: KeyBag
}
export type PersonalScopeGrant = {
  scopePublicKey: { suiteId: string; kemPublicKey: string }
  wrappedScopePrivateKey: Blob
}

export type UnlockedSession = {
  sessionToken: string
  user: LoginResponse['user']
  identity: v1.Identity['priv']
  identityPublicKey: KeyBag['identityPublicKey']
}

/** Authenticate with the password path (password + Secret Key from the Emergency Kit). */
export async function login(
  apiBase: string,
  email: string,
  password: string,
  secretKeyB64: string,
): Promise<UnlockedSession> {
  const res = await fetch(`${apiBase}/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!res.ok) throw new Error(`login failed: ${res.status} ${await res.text()}`)
  const body: LoginResponse = await res.json()

  const wrap = body.keyBag.masterKeyWraps.find((w) => w.unlockMethod === 'PASSWORD')
  if (!wrap?.kdfParams) throw new Error('no PASSWORD unlock wrap in key bag')

  const kek = v1.kekPasswordSecret(fromB64url(secretKeyB64), password, fromB64url(wrap.kdfParams), ARGON)
  const masterKey = v1.symUnwrap(kek, fromB64url(wrap.wrappedKey), 'master-key')
  const privBytes = v1.symUnwrap(masterKey, fromB64url(body.keyBag.wrappedIdentityPrivateKey.bytes), 'identity-priv')
  const identity = v1.deserializeIdentityPrivate(privBytes)

  return { sessionToken: body.sessionToken, user: body.user, identity, identityPublicKey: body.keyBag.identityPublicKey }
}

/** Proves the session resolves on the server. */
export async function fetchCurrentUser(apiBase: string, sessionToken: string): Promise<LoginResponse['user']> {
  const res = await fetch(`${apiBase}/api/me`, { headers: { authorization: `Bearer ${sessionToken}` } })
  if (!res.ok) throw new Error(`me failed: ${res.status}`)
  return res.json()
}

/** Fetches the authenticated user's unshared personal scope grant. */
export async function fetchPersonalScope(apiBase: string, sessionToken: string): Promise<PersonalScopeGrant> {
  const res = await fetch(`${apiBase}/api/me/personal-scope`, { headers: { authorization: `Bearer ${sessionToken}` } })
  if (!res.ok) throw new Error(`personal scope failed: ${res.status} ${await res.text()}`)
  return res.json()
}

/** Unwrap the user's personal scope grant with their identity key. */
export function openPersonalScopeGrant(
  grant: PersonalScopeGrant,
  identityPriv: v1.Identity['priv'],
  identityKemPublicKeyB64: string,
): v1.Identity['priv'] {
  const idKem = fromB64url(identityKemPublicKeyB64)
  const sk = v1.hybridSecret(identityPriv)
  const pk = { x: idKem.slice(0, 32), m: idKem.slice(32) }
  const scopePrivBytes = v1.asymUnwrap(sk, pk, fromB64url(grant.wrappedScopePrivateKey.bytes), 'personal-scope-grant')
  return v1.deserializeIdentityPrivate(scopePrivBytes)
}
