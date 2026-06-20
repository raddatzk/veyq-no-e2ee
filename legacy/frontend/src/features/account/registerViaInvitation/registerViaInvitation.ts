// uc.account.register-via-invitation (web) — a brand-new invitee accepts an owner invite. All key
// material is born here; one POST creates the account, mints the tenant scope as a self grant, and
// returns a session. The server only ever receives ciphertext + public keys (+ an auth verifier).
import { apiError } from '../../../shared/apiError'
import * as v1 from '../../../shared/crypto/v1'
import { b64url } from '../../../shared/base64'

// Must match the params used to wrap the master key (see setupServer.ts / login.ts).
const ARGON = { t: 3, m: 32768, p: 1, dkLen: 32 }

export type TenantSummary = { id: string; name: string; displayName: string; domain?: string; logoUrl?: string; status: string }
export type Blob = { suiteId: string; bytes: string }
export type KemPublicKey = { suiteId: string; kemPublicKey: string }
export type InviteMetadata = {
  inviteId: string
  tenant: TenantSummary
  role: string
  status: string
  kind: string
  scopePublicKey?: KemPublicKey
}
/** Temp-wrapped onboarding material, released only on proof of the bootstrap secret (defense in depth). */
export type InviteMaterial = {
  tempPublicKey: KemPublicKey
  tempWrappedScopePrivateKey: Blob
}
/** Builds the tenant scope grant for the new account, given the invitee's identity public key. */
export type TenantScopeProvider = (idPub: { x: Uint8Array; m: Uint8Array }) => {
  scopePublicKey: string
  wrappedScopePrivateKey: string
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
  if (!res.ok) throw await apiError(res, 'invite metadata')
  return res.json()
}

/** Fetch the temp-wrapped onboarding material by proving the bootstrap secret (not in public metadata). */
export async function getInviteMaterial(apiBase: string, inviteId: string, bootstrapSecret: string): Promise<InviteMaterial> {
  const res = await fetch(`${apiBase}/api/tenant-invites/${inviteId}/material`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ bootstrapSecret }),
  })
  if (!res.ok) throw await apiError(res, 'invite material')
  return res.json()
}

/**
 * Core registration assembly shared by the owner-claim and temp-wrap member flows. The invitee
 * generates a fresh identity + master-key unlock wrap + auth verifier + personal scope; the tenant
 * scope grant is supplied by `tenantScope` (mint a new scope for an owner claim, or re-wrap a
 * temp-unwrapped scope for a member invite).
 */
export async function registerWithScope(
  apiBase: string,
  inviteId: string,
  bootstrapSecret: string,
  email: string,
  displayName: string,
  password: string,
  tenantScope: TenantScopeProvider,
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

  // RECOVERY wrap: master key under the Secret Key ALONE — released only via the email-token-gated reset
  // (recovery = Secret Key AND email access). Never in the login key bag.
  const recSalt = v1.randomBytes(16)
  const recWrap = v1.symWrap(v1.kekRecovery(secretKey, recSalt, ARGON), masterKey, 'master-key', v1.randomBytes(12))

  // server-side auth verifier: argon2id(password, authSalt); store authSalt || output
  const authSalt = v1.randomBytes(16)
  const verifier = v1.kekRecovery(v1.utf8(password), authSalt, ARGON)

  // create the unshared personal scope; only this user receives a grant
  const personal = v1.generateIdentity() // reuse the hybrid-KEM bundle for the scope (sign field unused)
  const personalPublic = v1.concat(personal.pub.kemX, personal.pub.kemM)
  const personalGrant = v1.asymWrap(
    { x: id.pub.kemX, m: id.pub.kemM },
    v1.serializeIdentityPrivate(personal),
    'personal-scope-grant',
    { nonce: v1.randomBytes(12) },
  )

  // the tenant scope grant — minted (owner) or temp-unwrapped + re-wrapped (member)
  const scope = tenantScope({ x: id.pub.kemX, m: id.pub.kemM })

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
      { unlockMethod: 'PASSWORD', suiteId: 'v1', wrappedKey: b64url(masterWrap), kdfParams: b64url(v1.encodeKdfParams(ARGON, salt)) },
      { unlockMethod: 'RECOVERY', suiteId: 'v1', wrappedKey: b64url(recWrap), kdfParams: b64url(v1.encodeKdfParams(ARGON, recSalt)) },
    ],
    authVerifier: { kind: 'ARGON2_PROOF', suiteId: 'v1', verifier: b64url(v1.concat(authSalt, verifier)) },
    // commitment to the master key — lets password reset prove Secret-Key possession (the RECOVERY wrap above)
    recoveryAuthTag: b64url(v1.recoveryAuthTag(masterKey)),
    personalScopePublicKey: { suiteId: 'v1', kemPublicKey: b64url(personalPublic) },
    wrappedPersonalScopePrivateKey: { suiteId: 'v1', bytes: b64url(personalGrant) },
    scopePublicKey: { suiteId: 'v1', kemPublicKey: scope.scopePublicKey },
    wrappedScopePrivateKey: { suiteId: 'v1', bytes: scope.wrappedScopePrivateKey },
  }

  const res = await fetch(`${apiBase}/api/tenant-invites/${inviteId}/register`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(request),
  })
  if (!res.ok) throw await apiError(res, 'register')
  const body = await res.json()
  return { ...body, emergencyKit: { secretKey: b64url(secretKey) } }
}

/** Register from an owner invite — the empty tenant's scope keypair is minted here (self grant). */
export function registerViaInvitation(
  apiBase: string,
  inviteId: string,
  bootstrapSecret: string,
  email: string,
  displayName: string,
  password: string,
): Promise<RegisterResult> {
  return registerWithScope(apiBase, inviteId, bootstrapSecret, email, displayName, password, (idPub) => {
    const scope = v1.generateIdentity() // reuse the hybrid-KEM bundle for the scope (sign field unused)
    return {
      scopePublicKey: b64url(v1.concat(scope.pub.kemX, scope.pub.kemM)),
      wrappedScopePrivateKey: b64url(
        v1.asymWrap(idPub, v1.serializeIdentityPrivate(scope), 'scope-grant', { nonce: v1.randomBytes(12) }),
      ),
    }
  })
}
