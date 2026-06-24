// uc.account.login (web) — authenticate, fetch the wrapped key bag, and unlock the master key locally.
// The server returns ciphertext only; the master/identity keys are recovered here (auth != unlock).
import { apiError } from '../../../shared/apiError'
import * as v1 from '../../../shared/crypto/v1'
import { b64url, fromB64url } from '../../../shared/base64'

type Blob = { suiteId: string; bytes: string }
type MasterKeyWrap = { unlockMethod: string; suiteId: string; wrappedKey: string; kdfParams?: string }
export type KeyBag = {
  identityPublicKey: { suiteId: string; kemPublicKey: string; signPublicKey: string }
  wrappedIdentityPrivateKey: Blob
  masterKeyWraps: MasterKeyWrap[]
}
export type LoginResponse = {
  sessionToken: string
  deviceRef: string
  user: { id: string; email: string; displayName: string; serverRole: string; username: string; avatarUrl?: string }
  keyBag: KeyBag
}
export type RefreshSessionResponse = {
  sessionToken: string
}
export type UserSummary = LoginResponse['user']
export type PersonalScopeGrant = {
  scopeId: string
  scopePublicKey: { suiteId: string; kemPublicKey: string }
  wrappedScopePrivateKey: Blob
}

export type UnlockedSession = {
  sessionToken: string
  user: UserSummary
  identity: v1.Identity['priv']
  identityPublicKey: KeyBag['identityPublicKey']
  masterKey: Uint8Array
  deviceRef: string
}

const LOGIN_DEVICE_ID_PREFIX = 'veyq.loginDevice.'

/** Authenticate with the password path (password + Secret Key from the Emergency Kit). */
export async function login(
  apiBase: string,
  email: string,
  password: string,
  secretKeyB64: string,
): Promise<UnlockedSession> {
  const knownDeviceRef = readLoginDeviceId(email)
  const res = await fetch(`${apiBase}/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ email, password, deviceRef: knownDeviceRef }),
  })
  if (!res.ok) throw await apiError(res, 'login')
  const body: LoginResponse = await res.json()

  const wrap = body.keyBag.masterKeyWraps.find((w) => w.unlockMethod === 'PASSWORD')
  if (!wrap?.kdfParams) throw new Error('no PASSWORD unlock wrap in key bag')

  // the unlock cost params travel with the wrap, so a future cost bump never strands this account
  const { params, salt } = v1.decodeKdfParams(fromB64url(wrap.kdfParams))
  const kek = v1.kekPasswordSecret(fromB64url(secretKeyB64), password, salt, params)
  const masterKey = v1.symUnwrap(kek, fromB64url(wrap.wrappedKey), 'master-key')
  const privBytes = v1.symUnwrap(masterKey, fromB64url(body.keyBag.wrappedIdentityPrivateKey.bytes), 'identity-priv')
  const identity = v1.deserializeIdentityPrivate(privBytes)

  saveLoginDeviceId(email, body.deviceRef)
  return { sessionToken: body.sessionToken, user: body.user, identity, identityPublicKey: body.keyBag.identityPublicKey, masterKey, deviceRef: body.deviceRef }
}

function readLoginDeviceId(loginIdentity: string): string | undefined {
  const normalized = loginIdentity.trim().toLowerCase()
  return localStorage.getItem(LOGIN_DEVICE_ID_PREFIX + normalized) ?? undefined
}

function saveLoginDeviceId(loginIdentity: string, deviceRef: string): void {
  const normalized = loginIdentity.trim().toLowerCase()
  localStorage.setItem(LOGIN_DEVICE_ID_PREFIX + normalized, deviceRef)
}

/** Logout: invalidate the current session token server-side. Idempotent for an already-dead token. */
export async function logout(apiBase: string, sessionToken: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/me/session`, {
    method: 'DELETE',
    credentials: 'include',
    headers: { authorization: `Bearer ${sessionToken}` },
  })
  if (!res.ok) throw await apiError(res, 'logout')
}

/** Rotate the HttpOnly refresh cookie and receive a fresh access session token. */
export async function refreshSession(apiBase: string): Promise<RefreshSessionResponse> {
  const res = await fetch(`${apiBase}/api/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
  })
  if (!res.ok) throw await apiError(res, 'refresh session')
  return res.json()
}

/** Proves the session resolves on the server. */
export async function fetchCurrentUser(apiBase: string, sessionToken: string): Promise<UserSummary> {
  const res = await fetch(`${apiBase}/api/me`, { headers: { authorization: `Bearer ${sessionToken}` } })
  if (!res.ok) throw new Error(`me failed: ${res.status}`)
  return res.json()
}

/** Update low-sensitivity server-visible profile fields. Content and key material stay untouched. */
export async function updateProfile(apiBase: string, sessionToken: string, displayName: string, username: string): Promise<UserSummary> {
  const res = await fetch(`${apiBase}/api/me`, {
    method: 'PATCH',
    headers: { authorization: `Bearer ${sessionToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({ displayName, username }),
  })
  if (!res.ok) throw await apiError(res, 'profile')
  return res.json()
}

export async function updateAvatar(apiBase: string, sessionToken: string, file: File): Promise<UserSummary> {
  const start = await fetch(`${apiBase}/api/me/avatar/upload`, {
    method: 'POST',
    headers: { authorization: `Bearer ${sessionToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({ contentType: file.type, byteSize: file.size }),
  })
  if (!start.ok) throw await apiError(start, 'avatar upload')
  const ticket: { uploadRef: string; uploadUrl: string; expiresInSeconds: number } = await start.json()

  const uploaded = await fetch(ticket.uploadUrl, {
    method: 'PUT',
    headers: { 'content-type': file.type },
    body: file,
  })
  if (!uploaded.ok) throw new Error(`avatar object upload failed: ${uploaded.status}`)

  const complete = await fetch(`${apiBase}/api/me/avatar/upload/complete`, {
    method: 'POST',
    headers: { authorization: `Bearer ${sessionToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({ uploadRef: ticket.uploadRef, contentType: file.type }),
  })
  if (!complete.ok) throw await apiError(complete, 'avatar complete')
  return complete.json()
}

export async function deleteAvatar(apiBase: string, sessionToken: string): Promise<UserSummary> {
  const res = await fetch(`${apiBase}/api/me/avatar`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${sessionToken}` },
  })
  if (!res.ok) throw await apiError(res, 'avatar delete')
  return res.json()
}

export async function changePassword(
  apiBase: string,
  unlocked: UnlockedSession,
  currentPassword: string,
  secretKeyB64: string,
  newPassword: string,
): Promise<void> {
  const secretKey = fromB64url(secretKeyB64)
  const newSalt = v1.randomBytes(16)
  const newPwWrap = v1.symWrap(
    v1.kekPasswordSecret(secretKey, newPassword, newSalt, v1.ARGON),
    unlocked.masterKey,
    'master-key',
    v1.randomBytes(12),
  )
  const res = await fetch(`${apiBase}/api/me/password`, {
    method: 'POST',
    headers: { authorization: `Bearer ${unlocked.sessionToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      currentPassword,
      newPassword,
      masterKeyWrap: {
        unlockMethod: 'PASSWORD',
        suiteId: 'v1',
        wrappedKey: b64url(newPwWrap),
        kdfParams: b64url(v1.encodeKdfParams(v1.ARGON, newSalt)),
      },
    }),
  })
  if (!res.ok) throw await apiError(res, 'password change')
}

export async function deleteAccount(apiBase: string, sessionToken: string, password: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/me`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${sessionToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({ password }),
  })
  if (!res.ok) throw await apiError(res, 'account delete')
}

/** Fetches the authenticated user's unshared personal scope grant. */
export async function fetchPersonalScope(apiBase: string, sessionToken: string): Promise<PersonalScopeGrant> {
  const spaces = await fetch(`${apiBase}/api/spaces`, { headers: { authorization: `Bearer ${sessionToken}` } })
  if (!spaces.ok) throw await apiError(spaces, 'list spaces')
  const personal = (await spaces.json()).spaces.find((space: { kind: string }) => space.kind === 'PERSONAL')
  if (!personal) throw new Error('personal space not found')
  const res = await fetch(`${apiBase}/api/scopes/${personal.scopeId}/grants/me`, { headers: { authorization: `Bearer ${sessionToken}` } })
  if (!res.ok) throw await apiError(res, 'personal scope')
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
