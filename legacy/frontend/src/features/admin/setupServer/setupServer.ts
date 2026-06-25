// uc.admin.setup-server (web) — cold E2EE registration of the first Server Owner.
// All key material is generated here; the server only ever receives ciphertext + public keys.
import { apiError } from '../../../shared/apiError'
import * as v1 from '../../../shared/crypto/v1'
import { b64url } from '../../../shared/base64'

// Argon2id params (t=3, m=32 MiB) — must match the server's auth verifier; carried in kdfParams for agility.
const ARGON = { t: 3, m: 32768, p: 1, dkLen: 32 }

export type CreateServerOwnerRequest = {
  email: string
  displayName: string
  identityPublicKey: { suiteId: string; kemPublicKey: string; signPublicKey: string }
  wrappedIdentityPrivateKey: { suiteId: string; bytes: string }
  masterKeyWraps: Array<{ unlockMethod: string; suiteId: string; wrappedKey: string; kdfParams?: string }>
  password: string
  recoveryMasterKeyTag?: string
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

  // RECOVERY wrap: the master key under the Secret Key ALONE (argon2id(secretKey)). Never returned in the
  // login key bag — released only via the email-token-gated password reset, so recovery needs Secret Key
  // AND email access (the password's protective role for login is preserved; email is the reset 2nd factor).
  const recSalt = v1.randomBytes(16)
  const recWrap = v1.symWrap(v1.kekRecovery(secretKey, recSalt, ARGON), masterKey, 'master-key', v1.randomBytes(12))

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
      { unlockMethod: 'PASSWORD', suiteId: 'v1', wrappedKey: b64url(masterWrap), kdfParams: b64url(v1.encodeKdfParams(ARGON, salt)) },
      { unlockMethod: 'RECOVERY', suiteId: 'v1', wrappedKey: b64url(recWrap), kdfParams: b64url(v1.encodeKdfParams(ARGON, recSalt)) },
    ],
    password,
    // commitment to the master key — lets password reset prove Secret-Key possession (the RECOVERY wrap above)
    recoveryMasterKeyTag: b64url(v1.recoveryAuthTag(masterKey)),
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
  if (!res.ok) throw await apiError(res, 'setup/owner')
  return { user: await res.json(), emergencyKit }
}
