// uc.memory.journal-live — the SHARE guest's read-only live view (web). A read-capable share guest holds the
// scope content key (link fragment), so it can reconstruct the journal from the operator-blind store
// (`public/shares/.../journal/live`: sealed snapshot + update log) and then subscribe to the relay with a
// share token to live-read owner edits. It never writes (the relay marks share connections receive-only).
import * as Y from 'yjs'
import * as v1 from '../../../shared/crypto/v1'
import { EncryptedRelayProvider } from './encryptedProvider'

function fromB64url(s: string): Uint8Array {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/'))
  const out = new Uint8Array(b.length)
  for (let i = 0; i < b.length; i++) out[i] = b.charCodeAt(i)
  return out
}
const open = v1.journalOpen

/** Reconstruct the shared journal into `doc` from the public snapshot + update log (no auth). */
export async function loadShareJournalDoc(apiBase: string, shareId: string, nodeId: string, contentKey: Uint8Array, doc: Y.Doc): Promise<void> {
  const res = await fetch(`${apiBase}/api/public/shares/${shareId}/nodes/${nodeId}/journal/live`)
  if (!res.ok) throw new Error(`load share journal failed: ${res.status} ${await res.text()}`)
  const body = await res.json()
  Y.transact(doc, () => {
    if (body.snapshot) Y.applyUpdate(doc, open(contentKey, fromB64url(body.snapshot.bytes)), 'share-load')
    for (const u of body.updates ?? []) Y.applyUpdate(doc, open(contentKey, fromB64url(u.sealedUpdate.bytes)), 'share-load')
  }, 'share-load')
}

/** Subscribe to the relay as a read-only share guest (`?share=<id>`) to live-read owner edits into `doc`. */
export function subscribeShareJournal(apiBase: string, scopeId: string, nodeId: string, shareId: string, contentKey: Uint8Array, doc: Y.Doc): EncryptedRelayProvider {
  const wsBase = apiBase.replace(/^http/, 'ws').replace(/\/$/, '')
  const url = `${wsBase}/ws/journal/${scopeId}/${nodeId}?share=${encodeURIComponent(shareId)}`
  return new EncryptedRelayProvider(url, doc, contentKey)
}
