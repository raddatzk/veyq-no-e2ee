// Async front door to the heavy Argon2id derivations. Runs them in a Web Worker (argon.worker.ts) so the
// main thread doesn't freeze during login/unlock, and falls back to the synchronous conformance-pinned
// suite when no Worker is available (vitest/node, SSR, or worker construction failure). The crypto itself
// (v1.ts) is untouched and still runs synchronously under the conformance test — this is execution
// placement only, not a behaviour change.
import * as v1 from './v1'
import type { ArgonParams, AuthKeypair } from './v1'

type Pending = { resolve: (value: unknown) => void; reject: (reason: unknown) => void }

let worker: Worker | null = null
let workerUnavailable = false
let seq = 0
const pending = new Map<number, Pending>()

/** The shared worker, or null when Workers aren't available (→ caller uses the sync fallback). */
function getWorker(): Worker | null {
  if (workerUnavailable) return null
  if (worker) return worker
  if (typeof Worker === 'undefined') {
    workerUnavailable = true
    return null
  }
  try {
    worker = new Worker(new URL('./argon.worker.ts', import.meta.url), { type: 'module' })
    worker.addEventListener('message', (event: MessageEvent) => {
      const { id, ok, result, error } = event.data as { id: number; ok: boolean; result?: unknown; error?: string }
      const p = pending.get(id)
      if (!p) return
      pending.delete(id)
      if (ok) p.resolve(result)
      else p.reject(new Error(error ?? 'argon_worker_error'))
    })
    worker.addEventListener('error', () => {
      // A worker-level failure can't be tied to one request — reject everything outstanding so callers
      // surface their generic failure rather than hanging.
      for (const p of pending.values()) p.reject(new Error('argon_worker_error'))
      pending.clear()
    })
    return worker
  } catch {
    workerUnavailable = true
    return null
  }
}

function dispatch<T>(w: Worker, message: Record<string, unknown>): Promise<T> {
  const id = (seq += 1)
  return new Promise<T>((resolve, reject) => {
    pending.set(id, { resolve: resolve as (v: unknown) => void, reject })
    w.postMessage({ id, ...message })
  })
}

/** Derive the Ed25519 auth keypair (ADR 0003) — off the main thread when possible. */
export async function deriveAuthKeypairAsync(
  password: string,
  authSalt: Uint8Array,
  params?: ArgonParams,
): Promise<AuthKeypair> {
  const w = getWorker()
  if (!w) return v1.deriveAuthKeypair(password, authSalt, params)
  return dispatch<AuthKeypair>(w, { op: 'authKeypair', password, salt: authSalt, params })
}

/** Derive the PASSWORD-wrap Unlock KEK = Argon2id(secretKey + password) — off the main thread when possible. */
export async function unlockKekAsync(
  secretKey: Uint8Array,
  password: string,
  salt: Uint8Array,
  params?: ArgonParams,
): Promise<Uint8Array> {
  const w = getWorker()
  if (!w) return v1.kekPasswordSecret(secretKey, password, salt, params)
  return dispatch<Uint8Array>(w, { op: 'unlockKek', secretKey, password, salt, params })
}
