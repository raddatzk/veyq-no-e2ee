import type { PasskeyLoginRequest, PasskeyRegistrationRequest } from '../../api/generated'

type PublicKeyOptions = Record<string, unknown>
const PASSKEY_RELYING_PARTY_NAME = 'veyq'

export function passkeysAvailable(): boolean {
  return typeof window !== 'undefined' && Boolean(window.PublicKeyCredential && navigator.credentials)
}

export async function createPasskeyCredential(
  options: PublicKeyOptions
): Promise<PasskeyRegistrationRequest> {
  if (!passkeysAvailable()) {
    throw new Error('Passkeys werden von diesem Browser nicht unterstuetzt.')
  }

  const credential = (await navigator.credentials.create({
    publicKey: creationOptions(options)
  })) as PublicKeyCredential | null

  if (!credential) {
    throw new Error('Passkey-Erstellung wurde abgebrochen.')
  }

  const response = credential.response as AuthenticatorAttestationResponse
  return {
    id: credential.id,
    rawId: encodeBase64Url(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: encodeBase64Url(response.clientDataJSON),
      attestationObject: encodeBase64Url(response.attestationObject)
    }
  }
}

export async function getPasskeyCredential(options: PublicKeyOptions): Promise<PasskeyLoginRequest> {
  if (!passkeysAvailable()) {
    throw new Error('Passkeys werden von diesem Browser nicht unterstuetzt.')
  }

  const credential = (await navigator.credentials.get({
    publicKey: requestOptions(options)
  })) as PublicKeyCredential | null

  if (!credential) {
    throw new Error('Passkey-Login wurde abgebrochen.')
  }

  const response = credential.response as AuthenticatorAssertionResponse
  return {
    id: credential.id,
    rawId: encodeBase64Url(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: encodeBase64Url(response.clientDataJSON),
      authenticatorData: encodeBase64Url(response.authenticatorData),
      signature: encodeBase64Url(response.signature),
      userHandle: response.userHandle ? encodeBase64Url(response.userHandle) : undefined
    }
  }
}

function creationOptions(options: PublicKeyOptions): PublicKeyCredentialCreationOptions {
  const rp = recordOption(options.rp)
  return {
    ...options,
    rp: {
      ...rp,
      name: relyingPartyName(rp.name)
    },
    challenge: decodeBase64Url(stringOption(options.challenge)),
    user: {
      ...(options.user as Record<string, unknown>),
      id: decodeBase64Url(stringOption((options.user as Record<string, unknown>).id))
    },
    excludeCredentials: (options.excludeCredentials as PublicKeyOptions[] | undefined)?.map(
      credentialDescriptor
    )
  } as PublicKeyCredentialCreationOptions
}

function requestOptions(options: PublicKeyOptions): PublicKeyCredentialRequestOptions {
  return {
    ...options,
    challenge: decodeBase64Url(stringOption(options.challenge)),
    allowCredentials: (options.allowCredentials as PublicKeyOptions[] | undefined)?.map(
      credentialDescriptor
    )
  } as PublicKeyCredentialRequestOptions
}

function credentialDescriptor(descriptor: PublicKeyOptions): PublicKeyCredentialDescriptor {
  return {
    ...descriptor,
    id: decodeBase64Url(stringOption(descriptor.id))
  } as PublicKeyCredentialDescriptor
}

function recordOption(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : {}
}

function relyingPartyName(value: unknown): string {
  if (typeof value !== 'string') {
    return PASSKEY_RELYING_PARTY_NAME
  }

  const name = value.trim()
  return name && !isLocalhostName(name) ? name : PASSKEY_RELYING_PARTY_NAME
}

function isLocalhostName(value: string): boolean {
  const lowerValue = value.toLowerCase()
  return (
    lowerValue === 'localhost' ||
    lowerValue.startsWith('localhost:') ||
    lowerValue === '127.0.0.1' ||
    lowerValue.startsWith('127.0.0.1:') ||
    lowerValue === '::1' ||
    lowerValue === '[::1]' ||
    lowerValue.startsWith('[::1]:')
  )
}

function stringOption(value: unknown): string {
  if (typeof value !== 'string') {
    throw new Error('Passkey-Daten der API sind ungueltig.')
  }
  return value
}

function decodeBase64Url(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')
  const binary = atob(padded)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }
  return bytes.buffer
}

function encodeBase64Url(value: ArrayBuffer): string {
  const bytes = new Uint8Array(value)
  let binary = ''
  for (const byte of bytes) {
    binary += String.fromCharCode(byte)
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}
