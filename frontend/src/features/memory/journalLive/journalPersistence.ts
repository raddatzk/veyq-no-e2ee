// uc.memory.journal-live persistence (web) — the operator-blind CRDT store behind the live editor. The
// journal is a Yjs document; the server keeps a sealed Yjs *state snapshot* plus an append-only log of sealed
// Yjs *updates* (it never decrypts either). A client reconstructs the document by applying the snapshot then
// the updates, appends a sealed update per local edit, and on close compacts (a fresh snapshot + the Markdown
// reader projection) which prunes the folded-in updates. Cross-session edits converge without a plaintext
// server document.
import * as Y from 'yjs'
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'

const EMPTY = new Uint8Array(0)

/** Origin tag for doc updates we applied from the store, so the editor doesn't re-persist them. */
export const PERSISTENCE_ORIGIN = 'journal-persistence'

function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromB64url(s: string): Uint8Array {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/'))
  const out = new Uint8Array(b.length)
  for (let i = 0; i < b.length; i++) out[i] = b.charCodeAt(i)
  return out
}

/** nonce(12) || AEAD(contentKey, bytes) — the same envelope as every other sealed blob. */
function seal(contentKey: Uint8Array, bytes: Uint8Array): Uint8Array {
  const nonce = v1.randomBytes(12)
  return v1.concat(nonce, v1.aeadSeal(contentKey, nonce, bytes, EMPTY))
}
function open(contentKey: Uint8Array, blob: Uint8Array): Uint8Array {
  return v1.aeadOpen(contentKey, blob.slice(0, 12), blob.slice(12), EMPTY)
}

function auth(session: UnlockedSession) {
  return { authorization: `Bearer ${session.sessionToken}` }
}

/** Reconstruct the journal into `doc` from the server's snapshot + update log. Returns the max seq applied. */
export async function loadJournalDoc(
  apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, contentKey: Uint8Array, doc: Y.Doc,
): Promise<number> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/journal/live`, { headers: auth(session) })
  if (!res.ok) throw new Error(`load journal failed: ${res.status} ${await res.text()}`)
  const body = await res.json()
  let maxSeq = Number(body.baseSeq ?? 0)
  Y.transact(doc, () => {
    if (body.snapshot) Y.applyUpdate(doc, open(contentKey, fromB64url(body.snapshot.bytes)), PERSISTENCE_ORIGIN)
    for (const u of body.updates ?? []) {
      Y.applyUpdate(doc, open(contentKey, fromB64url(u.sealedUpdate.bytes)), PERSISTENCE_ORIGIN)
      maxSeq = Math.max(maxSeq, Number(u.seq))
    }
  }, PERSISTENCE_ORIGIN)
  return maxSeq
}

/** Persist one local Yjs update; returns its server-assigned monotonic seq. */
export async function appendJournalUpdate(
  apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, contentKey: Uint8Array, update: Uint8Array,
): Promise<number> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/journal/updates`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session) },
    body: JSON.stringify({ sealedUpdate: { suiteId: 'v1', bytes: b64url(seal(contentKey, update)) } }),
  })
  if (!res.ok) throw new Error(`append journal update failed: ${res.status} ${await res.text()}`)
  return Number((await res.json()).seq)
}

/** Whole-document save (the plain, non-collaborative editor): replace the text on the current CRDT and
 *  compact. Loads the live state first so a concurrent live edit isn't clobbered by a stale base. */
export async function saveJournalMarkdown(
  apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, contentKey: Uint8Array, markdown: string,
): Promise<void> {
  const doc = new Y.Doc()
  try {
    const maxSeq = await loadJournalDoc(apiBase, session, scopeId, nodeId, contentKey, doc)
    const ytext = doc.getText('journal')
    doc.transact(() => { ytext.delete(0, ytext.length); ytext.insert(0, markdown) })
    await compactJournal(apiBase, session, scopeId, nodeId, contentKey, doc, markdown, maxSeq)
  } finally {
    doc.destroy()
  }
}

/** Compact: write a fresh sealed snapshot + the Markdown reader projection, pruning updates <= coveredSeq. */
export async function compactJournal(
  apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, contentKey: Uint8Array,
  doc: Y.Doc, markdown: string, coveredSeq: number,
): Promise<void> {
  const snapshot = seal(contentKey, Y.encodeStateAsUpdate(doc))
  const md = seal(contentKey, v1.utf8(markdown))
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/journal/snapshot`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', ...auth(session) },
    body: JSON.stringify({
      snapshot: { suiteId: 'v1', bytes: b64url(snapshot) },
      markdown: { suiteId: 'v1', bytes: b64url(md) },
      coveredSeq,
    }),
  })
  if (!res.ok) throw new Error(`compact journal failed: ${res.status} ${await res.text()}`)
}
