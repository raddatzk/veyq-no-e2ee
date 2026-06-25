import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../../api/client', () => ({
  accountApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { unlockMasterKey } from './unlock'
import { accountApi } from '../../../api/client'
import { isUnlocked, masterKey, identityPrivate, clearVault } from '../../../app/vault'
import * as v1 from '../../../shared/crypto/v1'
import { b64std } from '../../../shared/base64'

const PASSWORD = 'correct horse battery'

/** Build the exact server-stored material setup writes: a PASSWORD wrap (KEK = Argon2id(secretKey+password))
 *  over the Master Key, plus the identity private key wrapped under that Master Key. Mirrors setupServer.ts. */
function buildMaterial(password: string) {
  const id = v1.generateIdentity()
  const masterKey = v1.randomBytes(32)
  const wrappedPriv = v1.symWrap(masterKey, v1.serializeIdentityPrivate(id), 'identity-priv', v1.randomBytes(12))

  const secretKey = v1.randomBytes(32)
  const salt = v1.randomBytes(16)
  const kek = v1.kekPasswordSecret(secretKey, password, salt)
  const masterWrap = v1.symWrap(kek, masterKey, 'master-key', v1.randomBytes(12))

  const material = {
    suiteId: 'v1',
    wraps: [
      { method: 'PASSWORD', wrappedKey: b64std(masterWrap), kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, salt)) },
      { method: 'RECOVERY', wrappedKey: b64std(masterWrap), kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, salt)) },
    ],
    wrappedIdentityPrivateKey: b64std(wrappedPriv),
  }
  return { material, id, masterKey, secretKey }
}

function mockMaterial(material: unknown) {
  vi.mocked(accountApi).mockReturnValue({
    getAccountKeyMaterial: () => Promise.resolve(material),
  } as unknown as ReturnType<typeof accountApi>)
}

beforeEach(() => {
  clearVault()
  vi.clearAllMocks()
})

describe('unlockMasterKey (uc.account.unlock)', () => {
  it('opens the Master Key + identity private key with the right password and Secret Key', async () => {
    const { material, id, masterKey: expectedMaster, secretKey } = buildMaterial(PASSWORD)
    mockMaterial(material)

    await unlockMasterKey(PASSWORD, b64std(secretKey))

    expect(isUnlocked()).toBe(true)
    // the unwrapped Master Key matches the one the wrap was built from
    expect(masterKey()).toEqual(expectedMaster)
    // and the identity private key round-trips (proves the Master Key actually opened the identity bundle)
    expect(identityPrivate()).toEqual(id.priv)
  })

  it('fails generically on a wrong password — no key material is held', async () => {
    const { material, secretKey } = buildMaterial(PASSWORD)
    mockMaterial(material)

    await expect(unlockMasterKey('wrong password', b64std(secretKey))).rejects.toThrow()
    expect(isUnlocked()).toBe(false)
    expect(masterKey()).toBeNull()
  })

  it('fails generically on a wrong Secret Key — no key material is held', async () => {
    const { material } = buildMaterial(PASSWORD)
    mockMaterial(material)

    await expect(unlockMasterKey(PASSWORD, b64std(v1.randomBytes(32)))).rejects.toThrow()
    expect(isUnlocked()).toBe(false)
    expect(masterKey()).toBeNull()
  })

  it('fails when the account has no PASSWORD wrap (E2)', async () => {
    const { material, secretKey } = buildMaterial(PASSWORD)
    const noPassword = { ...material, wraps: material.wraps.filter((w) => w.method !== 'PASSWORD') }
    mockMaterial(noPassword)

    await expect(unlockMasterKey(PASSWORD, b64std(secretKey))).rejects.toThrow()
    expect(isUnlocked()).toBe(false)
  })
})
