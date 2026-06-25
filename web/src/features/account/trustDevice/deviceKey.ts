// uc.account.trust-device — platform glue: a per-account NON-EXTRACTABLE device key in IndexedDB, the
// device factor it produces, and the local trust record. The key is generated with `extractable: false`, so
// its bytes never exist in JS; only its HMAC output (the device factor) is ever read — an XSS payload can
// compute the factor while it runs but cannot carry the key off-device for an offline attack. This is the
// platform half of the DEVICE wrap; the conformance-pinned crypto (the KEK) lives in v1.ts.
const DB_NAME = 'veyq-trust'
const STORE = 'devices'
const DEVICE_BIND_INFO = 'veyq/v1/device-bind'

/** A device's trust: its non-extractable key, the wrap's KDF params (to find + open its DEVICE wrap), and
 *  the server wrap id (to untrust). `cryptoKey` is stored as a structured-cloned CryptoKey — still
 *  non-extractable after a round-trip through IndexedDB. */
export type TrustRecord = { cryptoKey: CryptoKey; kdfParams: string; wrapId: string }

export function isTrustSupported(): boolean {
  return typeof indexedDB !== 'undefined' && typeof crypto !== 'undefined' && !!crypto.subtle
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1)
    req.onupgradeneeded = () => req.result.createObjectStore(STORE)
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

function run<T>(mode: IDBTransactionMode, op: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return openDb().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const req = op(db.transaction(STORE, mode).objectStore(STORE))
        req.onsuccess = () => resolve(req.result)
        req.onerror = () => reject(req.error)
        req.transaction!.oncomplete = () => db.close()
      }),
  )
}

const accountKey = (email: string): string => email.trim().toLowerCase()

/** A fresh non-extractable HMAC key — its raw bytes never leave the WebCrypto boundary. */
export function generateDeviceKey(): Promise<CryptoKey> {
  return crypto.subtle.generateKey({ name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
}

/** The device factor = HMAC(deviceKey, "veyq/v1/device-bind"); deterministic, so it reproduces across
 *  reloads and feeds the same KEK. */
export async function deviceFactor(cryptoKey: CryptoKey): Promise<Uint8Array> {
  const sig = await crypto.subtle.sign('HMAC', cryptoKey, new TextEncoder().encode(DEVICE_BIND_INFO))
  return new Uint8Array(sig)
}

export function saveTrust(email: string, record: TrustRecord): Promise<void> {
  return run('readwrite', (s) => s.put(record, accountKey(email))).then(() => undefined)
}

export async function loadTrust(email: string): Promise<TrustRecord | null> {
  return (await run<TrustRecord | undefined>('readonly', (s) => s.get(accountKey(email)))) ?? null
}

export function clearTrust(email: string): Promise<void> {
  return run('readwrite', (s) => s.delete(accountKey(email))).then(() => undefined)
}
