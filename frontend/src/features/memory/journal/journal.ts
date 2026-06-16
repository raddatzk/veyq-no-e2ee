// uc.memory.journal (web) — the Markdown READ projection of a Memory's journal: a Markdown body sealed under
// the scope content key (nonce || AEAD), the same envelope used for node names. The server stores ciphertext
// only. Editing goes through the operator-blind CRDT store (journalLive/journalPersistence); this module is
// the read path (detail view) that opens the compacted Markdown projection.
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'
import { fromB64url } from '../../../shared/base64'

const EMPTY = new Uint8Array(0)

export type Journal = { markdown: string; updatedAt: string | null }

/** Load and decrypt a memory's journal Markdown ('' when none has been written yet). */
export async function getJournal(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<Journal> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/journal`, {
    headers: { authorization: `Bearer ${session.sessionToken}` },
  })
  if (!res.ok) throw new Error(`get journal failed: ${res.status} ${await res.text()}`)
  const body = await res.json()
  if (!body.sealedContent) return { markdown: '', updatedAt: null }
  const sealed = fromB64url(body.sealedContent.bytes)
  const markdown = new TextDecoder().decode(v1.aeadOpen(active.contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY))
  return { markdown, updatedAt: body.updatedAt ?? null }
}
