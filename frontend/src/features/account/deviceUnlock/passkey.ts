// Web device key source: a WebAuthn passkey with the PRF extension. The passkey is created purely for its
// PRF output (a stable per-credential secret the authenticator derives from a fixed salt) — it's never
// registered with the server. The PRF output → kekPasskey → a 32-byte device key that unlocks the vault.
// Biometrics/userVerification gate every PRF evaluation, so opening the vault requires the user's gesture.
import * as v1 from '../../../shared/crypto/v1'

const PRF_SALT = new TextEncoder().encode('veyq/device-unlock/prf')

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
const prfResult = (cred: PublicKeyCredential): ArrayBuffer | undefined =>
  (cred.getClientExtensionResults() as { prf?: { results?: { first?: ArrayBuffer } } }).prf?.results?.first

/** True when the browser can create passkeys (the PRF extension itself is feature-detected at register time). */
export function passkeySupported(): boolean {
  return typeof window !== 'undefined' && !!window.PublicKeyCredential
}

/** Create a passkey (PRF-enabled) and return its id + the derived device key. */
export async function registerPasskey(label: string): Promise<{ credentialId: string; deviceKey: Uint8Array }> {
  const cred = (await navigator.credentials.create({
    publicKey: {
      rp: { id: location.hostname, name: 'veyq' },
      user: { id: v1.randomBytes(16) as BufferSource, name: label, displayName: label },
      challenge: v1.randomBytes(32) as BufferSource,
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
      authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
      extensions: { prf: { eval: { first: PRF_SALT } } } as AuthenticationExtensionsClientInputs,
    },
  })) as PublicKeyCredential | null
  if (!cred) throw new Error('passkey creation cancelled')
  const credentialId = b64url(new Uint8Array(cred.rawId))
  const prf = prfResult(cred)
  // Many authenticators only surface the PRF on an assertion, not at creation — fall back to a get().
  const deviceKey = prf ? v1.kekPasskey(new Uint8Array(prf)) : await getPasskeyDeviceKey(credentialId)
  return { credentialId, deviceKey }
}

/** Assert an existing passkey and return its device key (gated by the user's biometric/gesture). */
export async function getPasskeyDeviceKey(credentialId: string): Promise<Uint8Array> {
  const assertion = (await navigator.credentials.get({
    publicKey: {
      rpId: location.hostname,
      challenge: v1.randomBytes(32) as BufferSource,
      allowCredentials: [{ type: 'public-key', id: fromB64url(credentialId) as BufferSource }],
      userVerification: 'required',
      extensions: { prf: { eval: { first: PRF_SALT } } } as AuthenticationExtensionsClientInputs,
    },
  })) as PublicKeyCredential | null
  const prf = assertion && prfResult(assertion)
  if (!prf) throw new Error('passkey PRF not available')
  return v1.kekPasskey(new Uint8Array(prf))
}
