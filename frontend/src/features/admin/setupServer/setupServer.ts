// uc.admin.setup-server (web) — cold E2EE registration of the first Server Owner.
// All key material is generated here; the server only ever receives ciphertext + public keys.
import * as v1 from '../../../shared/crypto/v1'

// Light Argon2 params for the slice; production raises memory (carried in kdfParams for agility).
const ARGON = { t: 3, m: 8192, p: 1, dkLen: 32 }

function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export type CreateServerOwnerRequest = {
  email: string
  displayName: string
  identityPublicKey: { suiteId: string; kemPublicKey: string; signPublicKey: string }
  wrappedIdentityPrivateKey: { suiteId: string; bytes: string }
  masterKeyWraps: Array<{ unlockMethod: string; suiteId: string; wrappedKey: string; kdfParams?: string }>
  authVerifier: { kind: string; suiteId: string; verifier: string }
  personalScopePublicKey: { suiteId: string; kemPublicKey: string }
  wrappedPersonalScopePrivateKey: { suiteId: string; bytes: string }
}

export type EmergencyKit = { secretKey: string }

/** Build the request (keys born here). Returns the request + the Emergency Kit to show the user. */
export function buildCreateServerOwnerRequest(
  email: string,
  displayName: string,
  password: string,
): { request: CreateServerOwnerRequest; emergencyKit: EmergencyKit } {
  const id = v1.generateIdentity()
  const masterKey = v1.randomBytes(32)

  // wrap the identity private bundle under the master key
  const wrappedPriv = v1.symWrap(masterKey, v1.serializeIdentityPrivate(id), 'identity-priv', v1.randomBytes(12))

  // password + secret key -> KEK -> wrap the master key
  const secretKey = v1.randomBytes(32)
  const salt = v1.randomBytes(16)
  const kek = v1.kekPasswordSecret(secretKey, password, salt, ARGON)
  const masterWrap = v1.symWrap(kek, masterKey, 'master-key', v1.randomBytes(12))

  // server-side auth verifier: argon2id(password, authSalt); store authSalt || output
  const authSalt = v1.randomBytes(16)
  const verifier = v1.kekRecovery(v1.utf8(password), authSalt, ARGON)

  // personal scope is self-owned and unshared; grant it only to this identity
  const personal = v1.generateIdentity() // reuse the hybrid-KEM bundle for the scope (sign field unused)
  const personalPublic = v1.concat(personal.pub.kemX, personal.pub.kemM)
  const personalGrant = v1.asymWrap(
    { x: id.pub.kemX, m: id.pub.kemM },
    v1.serializeIdentityPrivate(personal),
    'personal-scope-grant',
    { nonce: v1.randomBytes(12) },
  )

  const request: CreateServerOwnerRequest = {
    email,
    displayName,
    identityPublicKey: {
      suiteId: 'v1',
      kemPublicKey: b64url(v1.concat(id.pub.kemX, id.pub.kemM)),
      signPublicKey: b64url(id.pub.sign),
    },
    wrappedIdentityPrivateKey: { suiteId: 'v1', bytes: b64url(wrappedPriv) },
    masterKeyWraps: [
      { unlockMethod: 'PASSWORD', suiteId: 'v1', wrappedKey: b64url(masterWrap), kdfParams: b64url(salt) },
    ],
    authVerifier: { kind: 'ARGON2_PROOF', suiteId: 'v1', verifier: b64url(v1.concat(authSalt, verifier)) },
    personalScopePublicKey: { suiteId: 'v1', kemPublicKey: b64url(personalPublic) },
    wrappedPersonalScopePrivateKey: { suiteId: 'v1', bytes: b64url(personalGrant) },
  }
  return { request, emergencyKit: { secretKey: b64url(secretKey) } }
}

export async function registerServerOwner(
  apiBase: string,
  email: string,
  displayName: string,
  password: string,
): Promise<{ user: { id: string; email: string; serverRole: string }; emergencyKit: EmergencyKit }> {
  const { request, emergencyKit } = buildCreateServerOwnerRequest(email, displayName, password)
  const res = await fetch(`${apiBase}/api/setup/owner`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(request),
  })
  if (!res.ok) throw new Error(`setup/owner failed: ${res.status} ${await res.text()}`)
  return { user: await res.json(), emergencyKit }
}
