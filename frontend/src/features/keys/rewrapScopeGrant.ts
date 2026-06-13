// uc.keys.rewrap-scope-grant (web) — replace only the caller's existing wrapped scope grant.
// The scope private key is opened and re-wrapped client-side; the server stores ciphertext only.

export type WrappedScopePrivateKey = { suiteId: string; bytes: string }
export type ScopeGrantResponse = {
  scopeId: string
  scopePublicKey: { suiteId: string; kemPublicKey: string }
  wrappedScopePrivateKey: WrappedScopePrivateKey
}
export type MasterKeyWrap = {
  unlockMethod: 'PASSWORD' | 'PASSKEY' | 'RECOVERY' | 'DEVICE'
  suiteId: string
  wrappedKey: string
  kdfParams?: string
  credentialRef?: string
}

export async function upsertMyMasterKeyWrap(
  apiBase: string,
  sessionToken: string,
  masterKeyWrap: MasterKeyWrap,
): Promise<MasterKeyWrap> {
  const res = await fetch(`${apiBase}/api/me/key-wraps/${encodeURIComponent(masterKeyWrap.unlockMethod)}`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${sessionToken}` },
    body: JSON.stringify({ masterKeyWrap }),
  })
  if (!res.ok) throw new Error(`master key wrap upsert failed: ${res.status} ${await res.text()}`)
  return res.json()
}

export async function rewrapMyScopeGrant(
  apiBase: string,
  sessionToken: string,
  scopeId: string,
  wrappedScopePrivateKey: WrappedScopePrivateKey,
): Promise<ScopeGrantResponse> {
  const res = await fetch(`${apiBase}/api/scopes/${encodeURIComponent(scopeId)}/grants/me`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${sessionToken}` },
    body: JSON.stringify({ wrappedScopePrivateKey }),
  })
  if (!res.ok) throw new Error(`scope grant rewrap failed: ${res.status} ${await res.text()}`)
  return res.json()
}
