// uc.memory.add-map-tracks (web) — a Memory's GPX tracks are {name, points} routes sealed under the scope content key
// (nonce || AEAD), the same envelope as node names and the journal. The server stores ciphertext only; we
// seal before POST and open after GET. Route location data is sensitive, so it never leaves the client clear.
import { apiError } from '../../../shared/apiError'
import { authFetch } from '../../../shared/api/sessionFetch'
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'
import type { GpxTrack } from './gpx'
import { b64url, fromB64url } from '../../../shared/base64'

const EMPTY = new Uint8Array(0)

export type Track = { id: string; name: string; points: { lat: number; lng: number; ele?: number; time?: string }[] }

/** List + decrypt a memory's tracks. */
export async function getTracks(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<Track[]> {
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/tracks`)
  if (!res.ok) throw await apiError(res, 'list tracks')
  const { tracks } = await res.json()
  return tracks.map((t: { id: string; sealedContent: { bytes: string } }) => {
    const sealed = fromB64url(t.sealedContent.bytes)
    const route: GpxTrack = JSON.parse(new TextDecoder().decode(v1.aeadOpen(active.contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY)))
    return { id: t.id, name: route.name, points: route.points }
  })
}

/** Seal a parsed GPX route and store it as a new track. */
export async function createTrack(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, route: GpxTrack): Promise<void> {
  const nonce = v1.randomBytes(12)
  const sealed = v1.concat(nonce, v1.aeadSeal(active.contentKey, nonce, v1.utf8(JSON.stringify(route)), EMPTY))
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/tracks`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ sealedContent: { suiteId: 'v1', bytes: b64url(sealed) } }),
  })
  if (!res.ok) throw await apiError(res, 'create track')
}

/** Remove a track. */
/** Rename (re-seal) a track: the client re-seals {name, points} and PATCHes it; the track id is preserved. */
export async function updateTrack(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, trackId: string, route: { name: string; points: Track['points'] }): Promise<void> {
  const nonce = v1.randomBytes(12)
  const sealed = v1.concat(nonce, v1.aeadSeal(active.contentKey, nonce, v1.utf8(JSON.stringify(route)), EMPTY))
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/tracks/${trackId}`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ sealedContent: { suiteId: 'v1', bytes: b64url(sealed) } }),
  })
  if (!res.ok) throw await apiError(res, 'rename track')
}

export async function deleteTrack(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, trackId: string): Promise<void> {
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/tracks/${trackId}`, { method: 'DELETE' })
  if (!res.ok) throw await apiError(res, 'delete track')
}
