import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPasskeyCredential } from './passkeys'

const navigatorCredentials = Object.getOwnPropertyDescriptor(navigator, 'credentials')

describe('createPasskeyCredential', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    if (navigatorCredentials) {
      Object.defineProperty(navigator, 'credentials', navigatorCredentials)
    } else {
      Reflect.deleteProperty(navigator, 'credentials')
    }
  })

  it('uses Veyq as the relying party name when the API returns localhost', async () => {
    const createMock = mockPasskeyCreate()

    await createPasskeyCredential({
      rp: { id: 'localhost', name: 'localhost' },
      user: { id: 'dXNlcg', name: 'user@example.test', displayName: 'User' },
      challenge: 'Y2hhbGxlbmdl',
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }]
    })

    expect(createMock).toHaveBeenCalledWith(
      expect.objectContaining({
        publicKey: expect.objectContaining({
          rp: expect.objectContaining({ id: 'localhost', name: 'Veyq' })
        })
      })
    )
  })

  it('keeps a configured relying party display name', async () => {
    const createMock = mockPasskeyCreate()

    await createPasskeyCredential({
      rp: { id: 'memories.example.test', name: 'Family Memories' },
      user: { id: 'dXNlcg', name: 'user@example.test', displayName: 'User' },
      challenge: 'Y2hhbGxlbmdl',
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }]
    })

    expect(createMock).toHaveBeenCalledWith(
      expect.objectContaining({
        publicKey: expect.objectContaining({
          rp: expect.objectContaining({ id: 'memories.example.test', name: 'Family Memories' })
        })
      })
    )
  })
})

function mockPasskeyCreate() {
  const createMock = vi.fn().mockResolvedValue(fakeCredential())
  vi.stubGlobal('PublicKeyCredential', vi.fn())
  Object.defineProperty(navigator, 'credentials', {
    configurable: true,
    value: { create: createMock }
  })
  return createMock
}

function fakeCredential() {
  return {
    id: 'credential-id',
    rawId: new Uint8Array([1, 2, 3]).buffer,
    type: 'public-key',
    response: {
      clientDataJSON: new Uint8Array([4, 5, 6]).buffer,
      attestationObject: new Uint8Array([7, 8, 9]).buffer
    }
  }
}
