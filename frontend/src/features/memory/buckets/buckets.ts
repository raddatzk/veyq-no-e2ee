// uc.contributions (web, ADR 0023) — per-contributor media buckets. Bucket names are sealed under the scope
// content key (operator-blind), like node names. A member's primary bucket is synthesized server-side (no
// sealed name — the client labels it with the member's own display name); sub-buckets + guest primaries are
// persisted with sealed names. Create/rename/delete + media moves are own-bucket only (enforced server-side).
import { apiError } from '../../../shared/apiError'
import * as v1 from '../../../shared/crypto/v1'
import { b64url, fromB64url } from '../../../shared/base64'
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'

const EMPTY = new Uint8Array(0)
const auth = (token: string) => ({ authorization: `Bearer ${token}` })

export type Bucket = {
  id: string
  ownerKey: string
  parentBucketId?: string
  sealedName?: { suiteId: string; bytes: string }
  primary: boolean
  mediaCount: number
}

function sealName(contentKey: Uint8Array, name: string): { suiteId: string; bytes: string } {
  const nonce = v1.randomBytes(12)
  return { suiteId: 'v1', bytes: b64url(v1.concat(nonce, v1.aeadSeal(contentKey, nonce, v1.utf8(name), EMPTY))) }
}

/** Decrypt a bucket's sealed name, or '' when absent (synthesized member primary) / undecryptable. */
export function bucketName(contentKey: Uint8Array, bucket: Bucket): string {
  if (!bucket.sealedName) return ''
  try {
    const sealed = fromB64url(bucket.sealedName.bytes)
    return new TextDecoder().decode(v1.aeadOpen(contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY))
  } catch {
    return ''
  }
}

export async function listBuckets(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<Bucket[]> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/buckets`, { headers: auth(session.sessionToken) })
  if (!res.ok) throw await apiError(res, 'list buckets')
  return (await res.json()).buckets
}

/** Create a sub-bucket under the caller's own primary (sealed name). */
export async function createBucket(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, name: string): Promise<Bucket> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/buckets`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session.sessionToken) },
    body: JSON.stringify({ sealedName: sealName(active.contentKey, name) }),
  })
  if (!res.ok) throw await apiError(res, 'create bucket')
  return res.json()
}

export async function renameBucket(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, bucketId: string, name: string): Promise<Bucket> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/buckets/${bucketId}`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json', ...auth(session.sessionToken) },
    body: JSON.stringify({ sealedName: sealName(active.contentKey, name) }),
  })
  if (!res.ok) throw await apiError(res, 'rename bucket')
  return res.json()
}

export async function deleteBucket(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, bucketId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/buckets/${bucketId}`, {
    method: 'DELETE',
    headers: auth(session.sessionToken),
  })
  if (!res.ok) throw await apiError(res, 'delete bucket')
}
