// uc.account.register-via-invitation (web) — a brand-new invitee accepts an owner invite. All key
// material is born here; one POST creates the account, mints the tenant scope as a self grant, and
// returns a session. The server only ever receives ciphertext + public keys (+ an auth verifier).
import * as v1 from '../../../shared/crypto/v1'

// Must match the params used to wrap the master key (see setupServer.ts / login.ts).
const ARGON = { t: 3, m: 8192, p: 1, dkLen: 32 }

function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export type TenantSummary = { id: string; name: string; displayName: string; domain?: string; status: string }
export type InviteMetadata = {
  inviteId: string
  tenant: TenantSummary
  role: string
  status: string
}
export type RegisterResult = {
  sessionToken: string
  user: { id: string; email: string; displayName: string; serverRole: string }
  tenant: TenantSummary
  emergencyKit: EmergencyKit
}
export type EmergencyKit = { secretKey: string }

/** Public invite metadata — the bootstrap secret (from the URL fragment) is never sent here. */
export async function getInviteMetadata(apiBase: string, inviteId: string): Promise<InviteMetadata> {
  const res = await fetch(`${apiBase}/api/tenant-invites/${inviteId}`)
  if (!res.ok) throw new Error(`invite metadata failed: ${res.status} ${await res.text()}`)
  return res.json()
}

/**
 * Register a new account from an owner invite and claim the (empty) tenant. The invitee generates a
 * fresh identity, wraps it under a master key (password + Secret Key), mints the tenant scope keypair
 * and wraps its private key to their own identity public key (a self scope grant) — the empty-tenant
 * counterpart to the existing-user claim path.
 */
export async function registerViaInvitation(
  apiBase: string,
  inviteId: string,
  bootstrapSecret: string,
  email: string,
  displayName: string,
  password: string,
): Promise<RegisterResult> {
  const id = v1.generateIdentity()
  const masterKey = v1.randomBytes(32)

  // wrap the identity private bundle under the master key
  const wrappedPriv = v1.symWrap(masterKey, v1.serializeIdentityPrivate(id), 'identity-priv', v1.randomBytes(12))

  // password + Secret Key -> KEK -> wrap the master key
  const secretKey = v1.randomBytes(32)
  const salt = v1.randomBytes(16)
  const kek = v1.kekPasswordSecret(secretKey, password, salt, ARGON)
  const masterWrap = v1.symWrap(kek, masterKey, 'master-key', v1.randomBytes(12))

  // server-side auth verifier: argon2id(password, authSalt); store authSalt || output
  const authSalt = v1.randomBytes(16)
  const verifier = v1.kekRecovery(v1.utf8(password), authSalt, ARGON)

  // mint the tenant scope keypair and grant it to self (wrapped to our own identity public key)
  const scope = v1.generateIdentity() // reuse the hybrid-KEM bundle for the scope (sign field unused)
  const scopePublic = v1.concat(scope.pub.kemX, scope.pub.kemM)
  const grant = v1.asymWrap(
    { x: id.pub.kemX, m: id.pub.kemM },
    v1.serializeIdentityPrivate(scope),
    'scope-grant',
    { nonce: v1.randomBytes(12) },
  )

  const request = {
    bootstrapSecret,
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
    scopePublicKey: { suiteId: 'v1', kemPublicKey: b64url(scopePublic) },
    wrappedScopePrivateKey: { suiteId: 'v1', bytes: b64url(grant) },
  }

  const res = await fetch(`${apiBase}/api/tenant-invites/${inviteId}/register`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(request),
  })
  if (!res.ok) throw new Error(`register failed: ${res.status} ${await res.text()}`)
  const body = await res.json()
  return { ...body, emergencyKit: { secretKey: b64url(secretKey) } }
}
