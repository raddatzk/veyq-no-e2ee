// An operator-blind Yjs provider over the journal relay websocket. It speaks the standard y-websocket
// sync + awareness protocol, but EVERY message is AEAD-sealed under the scope content key before it leaves
// the client and opened on arrival — so the relay (and anyone on the wire) only ever sees ciphertext. The
// CRDT merges peer-to-peer; the server holds no document state.
import * as Y from 'yjs'
import * as syncProtocol from 'y-protocols/sync'
import * as awarenessProtocol from 'y-protocols/awareness'
import * as encoding from 'lib0/encoding'
import * as decoding from 'lib0/decoding'
import * as v1 from '../../../shared/crypto/v1'

const messageSync = 0
const messageAwareness = 1

export class EncryptedRelayProvider {
  ws: WebSocket | null = null
  synced = false
  readonly awareness: awarenessProtocol.Awareness
  onSynced: (() => void) | null = null
  onStatus: ((connected: boolean) => void) | null = null
  private destroyed = false

  constructor(private url: string, readonly doc: Y.Doc, private contentKey: Uint8Array) {
    this.awareness = new awarenessProtocol.Awareness(doc)
    this._docUpdate = this._docUpdate.bind(this)
    this._awarenessUpdate = this._awarenessUpdate.bind(this)
    doc.on('update', this._docUpdate)
    this.awareness.on('update', this._awarenessUpdate)
    this.connect()
  }

  private seal(msg: Uint8Array): Uint8Array {
    return v1.journalSeal(this.contentKey, msg)
  }
  private send(msg: Uint8Array) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(this.seal(msg).slice().buffer)
  }

  private connect() {
    const ws = new WebSocket(this.url)
    ws.binaryType = 'arraybuffer'
    this.ws = ws
    ws.onopen = () => {
      this.onStatus?.(true)
      // sync step 1 (our state vector) so peers send us what we're missing
      const enc = encoding.createEncoder()
      encoding.writeVarUint(enc, messageSync)
      syncProtocol.writeSyncStep1(enc, this.doc)
      this.send(encoding.toUint8Array(enc))
      // announce our presence (cursor/name) to peers
      if (this.awareness.getLocalState() !== null) {
        const aenc = encoding.createEncoder()
        encoding.writeVarUint(aenc, messageAwareness)
        encoding.writeVarUint8Array(aenc, awarenessProtocol.encodeAwarenessUpdate(this.awareness, [this.doc.clientID]))
        this.send(encoding.toUint8Array(aenc))
      }
    }
    ws.onmessage = (ev) => {
      let plain: Uint8Array
      try {
        plain = v1.journalOpen(this.contentKey, new Uint8Array(ev.data as ArrayBuffer))
      } catch {
        return // wrong key / not a message we can read — ignore (operator-blind: we can't, the server can't either)
      }
      const dec = decoding.createDecoder(plain)
      const type = decoding.readVarUint(dec)
      if (type === messageSync) {
        const enc = encoding.createEncoder()
        encoding.writeVarUint(enc, messageSync)
        const syncType = syncProtocol.readSyncMessage(dec, enc, this.doc, this)
        if (syncType === syncProtocol.messageYjsSyncStep2 && !this.synced) { this.synced = true; this.onSynced?.() }
        if (encoding.length(enc) > 1) this.send(encoding.toUint8Array(enc))
      } else if (type === messageAwareness) {
        awarenessProtocol.applyAwarenessUpdate(this.awareness, decoding.readVarUint8Array(dec), this)
      }
    }
    ws.onclose = () => { this.onStatus?.(false); if (!this.destroyed) setTimeout(() => this.connect(), 1500) }
    ws.onerror = () => ws.close()
  }

  private _docUpdate(update: Uint8Array, origin: unknown) {
    if (origin === this) return // a remote update we just applied — don't echo it back
    const enc = encoding.createEncoder()
    encoding.writeVarUint(enc, messageSync)
    syncProtocol.writeUpdate(enc, update)
    this.send(encoding.toUint8Array(enc))
  }
  private _awarenessUpdate({ added, updated, removed }: { added: number[]; updated: number[]; removed: number[] }) {
    const changed = added.concat(updated, removed)
    const enc = encoding.createEncoder()
    encoding.writeVarUint(enc, messageAwareness)
    encoding.writeVarUint8Array(enc, awarenessProtocol.encodeAwarenessUpdate(this.awareness, changed))
    this.send(encoding.toUint8Array(enc))
  }

  destroy() {
    this.destroyed = true
    awarenessProtocol.removeAwarenessStates(this.awareness, [this.doc.clientID], 'destroy')
    this.doc.off('update', this._docUpdate)
    this.awareness.off('update', this._awarenessUpdate)
    this.awareness.destroy()
    this.ws?.close()
  }
}
