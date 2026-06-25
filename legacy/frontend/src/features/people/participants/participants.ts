// uc.people.manage-participants (web, ADR 0032) — Memory participants are sealed People metadata. The
// server stores an opaque payload; linked-user vs anonymous semantics are decrypted locally.
import { apiError } from '../../../shared/apiError'
import { authFetch } from '../../../shared/api/sessionFetch'
import * as v1 from '../../../shared/crypto/v1'
import { b64url, fromB64url } from '../../../shared/base64'
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'
import { listNodesCached } from '../../home/nodeGraph'
import { decodeNode, type MemoryNode } from '../../memory/createTree/memoryTree'
import { mapPool } from '../../../shared/pool'

const EMPTY = new Uint8Array(0)

export type Participant = { id: string; displayName: string; linkedUserId?: string }
export type ParticipantInput = { displayName: string; linkedUserId?: string }
export type ParticipantNodeHit = {
  id: string
  name: string
  slug: string
  type: MemoryNode['type']
  node: MemoryNode
  participant: Participant
}
type ParticipantPayload = ParticipantInput & { presentation?: Record<string, unknown> }
type ApiParticipant = { id: string; sealedPayload: { bytes: string } }

function seal(contentKey: Uint8Array, payload: ParticipantPayload): { suiteId: string; bytes: string } {
  const nonce = v1.randomBytes(12)
  return { suiteId: 'v1', bytes: b64url(v1.concat(nonce, v1.aeadSeal(contentKey, nonce, v1.utf8(JSON.stringify(payload)), EMPTY))) }
}

function open(contentKey: Uint8Array, bytes: string): ParticipantPayload {
  const sealed = fromB64url(bytes)
  return JSON.parse(new TextDecoder().decode(v1.aeadOpen(contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY)))
}

function toParticipant(contentKey: Uint8Array, p: ApiParticipant): Participant {
  const payload = open(contentKey, p.sealedPayload.bytes)
  return { id: p.id, displayName: payload.displayName, linkedUserId: payload.linkedUserId }
}

export function participantSearchText(participants: Participant[]): string {
  return participants.map((p) => p.displayName).join(' ')
}

export async function listParticipants(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<Participant[]> {
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/participants`)
  if (!res.ok) throw await apiError(res, 'list participants')
  return (await res.json()).participants.map((p: ApiParticipant) => toParticipant(active.contentKey, p))
}

export async function createParticipant(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, input: ParticipantInput): Promise<void> {
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/participants`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ sealedPayload: seal(active.contentKey, { displayName: input.displayName, linkedUserId: input.linkedUserId }) }),
  })
  if (!res.ok) throw await apiError(res, 'create participant')
}

export async function deleteParticipant(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, participantId: string): Promise<void> {
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/participants/${participantId}`, { method: 'DELETE' })
  if (!res.ok) throw await apiError(res, 'delete participant')
}

export async function getShareParticipants(ctx: { apiBase: string; shareId: string; contentKey: Uint8Array }, nodeId: string): Promise<Participant[]> {
  const res = await fetch(`${ctx.apiBase}/api/public/shares/${ctx.shareId}/nodes/${nodeId}/participants`)
  if (!res.ok) throw await apiError(res, 'share participants')
  return (await res.json()).participants.map((p: ApiParticipant) => toParticipant(ctx.contentKey, p))
}

export async function listParticipantNodeHits(apiBase: string, session: UnlockedSession, active: ActiveSpace, linkedUserId: string): Promise<ParticipantNodeHit[]> {
  const nodes = (await listNodesCached(apiBase, session, active.scope.scopeId)).filter((n) => n.type !== 'FOLDER')
  const hits = await mapPool(nodes, 6, async (node): Promise<ParticipantNodeHit | null> => {
    try {
      const participant = (await listParticipants(apiBase, session, active, node.id)).find((p) => p.linkedUserId === linkedUserId)
      if (!participant) return null
      const decoded = decodeNode(active.contentKey, node)
      return { id: node.id, name: decoded.name, slug: decoded.slug, type: node.type, node, participant }
    } catch {
      return null
    }
  })
  return hits.filter((hit): hit is ParticipantNodeHit => !!hit).sort((a, b) => a.name.localeCompare(b.name))
}
