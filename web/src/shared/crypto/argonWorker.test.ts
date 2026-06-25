import { describe, it, expect } from 'vitest'

import { deriveAuthKeypairAsync, unlockKekAsync } from './argonWorker'
import * as v1 from './v1'

// In the test env there is no Worker global, so the async wrappers take the synchronous fallback. These
// assert the fallback is byte-identical to the conformance-pinned suite (the worker runs the SAME v1 code).
describe('argonWorker sync fallback', () => {
  it('deriveAuthKeypairAsync matches v1.deriveAuthKeypair', async () => {
    const salt = new Uint8Array(16).fill(5)
    const got = await deriveAuthKeypairAsync('correct horse battery', salt)
    const expected = v1.deriveAuthKeypair('correct horse battery', salt)
    expect(got.publicKey).toEqual(expected.publicKey)
    expect(got.privateKey).toEqual(expected.privateKey)
  })

  it('unlockKekAsync matches v1.kekPasswordSecret', async () => {
    const secretKey = new Uint8Array(32).fill(9)
    const salt = new Uint8Array(16).fill(7)
    const got = await unlockKekAsync(secretKey, 'pw', salt)
    expect(got).toEqual(v1.kekPasswordSecret(secretKey, 'pw', salt))
  })
})
