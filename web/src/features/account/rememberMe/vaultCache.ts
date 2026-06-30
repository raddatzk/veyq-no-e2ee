// uc.account.auto-unlock (L3 — web "remember me", cr-0052). The local vault cache: after an explicit opt-in,
// the unlocked Master Key is encrypted under a NON-EXTRACTABLE AES-GCM device key and parked in IndexedDB, so a
// browser restart re-opens the vault with no prompt (paired with the httpOnly refresh cookie that restores the
// session). The AES key's bytes never exist in JS — a live XSS payload can decrypt while it runs but cannot
// carry the key off-device. The trade-off (device possession ⇒ content) is the opted-in L3 deal (ADR 0005 §13);
// operator-blindness is untouched (nothing here is server-side). One remembered account per browser profile.
const DB_NAME = 'veyq-vault'
const STORE = 'session'
const RECORD_KEY = 'current'

/** The remembered session: the email (the cache is account-keyed; boot has no email otherwise), the
 *  non-extractable AES key, and the Master Key sealed under it. Stored as a structured clone — the CryptoKey is
 *  still non-extractable after the IndexedDB round-trip. */
type CacheRecord = { email: string; key: CryptoKey; iv: Uint8Array; ciphertext: ArrayBuffer }

export function isCacheSupported(): boolean {
  return typeof indexedDB !== 'undefined' && typeof crypto !== 'undefined' && !!crypto.subtle
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1)
    req.onupgradeneeded = () => req.result.createObjectStore(STORE)
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
    req.onblocked = () => reject(new Error('indexeddb_blocked'))
  })
}

function run<T>(mode: IDBTransactionMode, op: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return openDb().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const tx = db.transaction(STORE, mode)
        const req = op(tx.objectStore(STORE))
        let result: T
        req.onsuccess = () => {
          result = req.result
        }
        // Settle on COMMIT, not req.onsuccess: a write can still abort at commit (quota / clone failure).
        tx.oncomplete = () => {
          db.close()
          resolve(result)
        }
        tx.onerror = () => {
          db.close()
          reject(tx.error)
        }
        tx.onabort = () => {
          db.close()
          reject(tx.error)
        }
      }),
  )
}

/** Seal the unlocked Master Key under a fresh non-extractable AES-GCM key and park it (L3 opt-in). */
export async function cacheMasterKey(email: string, masterKey: Uint8Array): Promise<void> {
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, masterKey as BufferSource)
  const record: CacheRecord = { email: email.trim().toLowerCase(), key, iv, ciphertext }
  await run('readwrite', (s) => s.put(record, RECORD_KEY))
}

/** Read + decrypt the remembered Master Key (and its email), or null when absent/unusable. */
export async function loadCachedMasterKey(): Promise<{ email: string; masterKey: Uint8Array } | null> {
  const record = await run<CacheRecord | undefined>('readonly', (s) => s.get(RECORD_KEY))
  if (!record) return null
  try {
    const plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: record.iv as BufferSource },
      record.key,
      record.ciphertext,
    )
    return { email: record.email, masterKey: new Uint8Array(plain) }
  } catch {
    return null // tampered / unusable
  }
}

/** Drop the remembered vault (logout, a stale cache, or lowering off L3). */
export function clearMasterKeyCache(): Promise<void> {
  return run('readwrite', (s) => s.delete(RECORD_KEY)).then(() => undefined)
}
