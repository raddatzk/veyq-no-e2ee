import { describe, it, expect, vi, beforeEach } from 'vitest'

// The pin store's API + vault are mocked; the crypto (seal/unseal under the Master Key) is REAL, so the tests
// prove a genuine round-trip and the version-guarded union-merge, not a stub.
vi.mock('../../api/client', () => ({ accountIdentityPinsApi: vi.fn() }))
vi.mock('./login/login', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn() }))

import { checkAndPin, acceptKeyChange, getPin, IDENTITY_PINS } from './identityPins'
import { accountIdentityPinsApi } from '../../api/client'
import { masterKey } from '../../app/vault'
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'

// One fixed Master Key for the suite; the mocked vault hands out a FRESH copy each call because the data layer
// zeroes the copy it receives (returning the same buffer would zero our master after the first use).
const MK = v1.randomBytes(32)
const enc = new TextEncoder()
const dec = new TextDecoder()
const seal = (pins: Record<string, unknown>) =>
  b64std(v1.symWrap(MK, enc.encode(JSON.stringify({ pins })), IDENTITY_PINS, v1.randomBytes(12)))
const unsealPins = (blob: string): Record<string, { key: string; state: string; firstSeenAt: number }> =>
  JSON.parse(dec.decode(v1.symUnwrap(MK, fromB64std(blob), IDENTITY_PINS))).pins

// A fake server store with REAL optimistic concurrency: a PUT whose version != the stored version is a 409.
function fakeServer(initial: { blob: string | null; version: number } = { blob: null, version: 0 }) {
  const state = { ...initial }
  const stale = () => ({ response: { status: 409, clone: () => ({ json: async () => ({ code: 'stale_version' }) }) } })
  const getIdentityPins = vi.fn(async () => ({ blob: state.blob, version: state.version }))
  const putIdentityPins = vi.fn(async ({ identityPinsUpdate }: { identityPinsUpdate: { blob: string; version: number } }) => {
    if (identityPinsUpdate.version !== state.version) throw stale()
    state.blob = identityPinsUpdate.blob
    state.version += 1
    return { blob: state.blob, version: state.version }
  })
  vi.mocked(accountIdentityPinsApi).mockReturnValue(
    { getIdentityPins, putIdentityPins } as unknown as ReturnType<typeof accountIdentityPinsApi>,
  )
  return { state, getIdentityPins, putIdentityPins, stale }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
})

describe('checkAndPin (TOFU)', () => {
  it('pins on first use and stores the sealed blob; a re-check of the same key is a match', async () => {
    const srv = fakeServer()
    const key = 'aWRlbnRpdHkta2V5LW9uZQ=='

    expect(await checkAndPin('u1', key)).toBe('firstUse')
    // The server now holds one opaque blob at version 1 that unseals to the pinned contact.
    expect(srv.state.version).toBe(1)
    expect(srv.state.blob).not.toBeNull()
    const pins = unsealPins(srv.state.blob!)
    expect(pins.u1.key).toBe(key)
    expect(pins.u1.state).toBe('pinned')

    // A later wrap to the SAME key proceeds without a write (no version bump).
    expect(await checkAndPin('u1', key)).toBe('match')
    expect(srv.state.version).toBe(1)
  })

  it('surfaces a CHANGED key without trusting it — the store is left untouched', async () => {
    const srv = fakeServer()
    await checkAndPin('u1', 'a2V5LW9uZQ==')
    const versionAfterPin = srv.state.version

    expect(await checkAndPin('u1', 'a2V5LXR3bw==')).toBe('changed')
    expect(srv.state.version).toBe(versionAfterPin) // never re-pinned the changed key
    expect(unsealPins(srv.state.blob!).u1.key).toBe('a2V5LW9uZQ==') // original pin intact
  })

  it('reads an existing pin from a previously-sealed store (cross-device round-trip)', async () => {
    // The store was written on another device (a sealed blob at version 3); this device opens it and sees the pin.
    const srv = fakeServer({ blob: seal({ u9: { key: 'cHJlLXBpbm5lZA==', state: 'pinned', firstSeenAt: 1 } }), version: 3 })
    expect(await checkAndPin('u9', 'cHJlLXBpbm5lZA==')).toBe('match')
    expect(await checkAndPin('u9', 'ZGlmZmVyZW50')).toBe('changed')
    expect(srv.putIdentityPins).not.toHaveBeenCalled() // pure reads
  })

  it('union-merges on a 409 stale_version — a concurrent device pin is preserved, not clobbered', async () => {
    const srv = fakeServer()
    // Make the FIRST PUT lose to a concurrent device that just pinned contact "other" at v0→v1.
    let firstPut = true
    srv.putIdentityPins.mockImplementation(async ({ identityPinsUpdate }: { identityPinsUpdate: { blob: string; version: number } }) => {
      if (firstPut) {
        firstPut = false
        srv.state.blob = seal({ other: { key: 'b3RoZXIta2V5', state: 'pinned', firstSeenAt: 1 } })
        srv.state.version = 1
        throw srv.stale()
      }
      if (identityPinsUpdate.version !== srv.state.version) throw srv.stale()
      srv.state.blob = identityPinsUpdate.blob
      srv.state.version += 1
      return { blob: srv.state.blob, version: srv.state.version }
    })

    expect(await checkAndPin('mine', 'bXkta2V5')).toBe('firstUse')
    // After the re-read + re-apply + retry: BOTH contacts are pinned (the concurrent one was merged, not lost).
    const pins = unsealPins(srv.state.blob!)
    expect(pins.other.key).toBe('b3RoZXIta2V5')
    expect(pins.mine.key).toBe('bXkta2V5')
    expect(srv.state.version).toBe(2)
  })
})

describe('acceptKeyChange (human accept → re-pin)', () => {
  it('re-pins a contact to the new key, preserving firstSeenAt, so a later check matches', async () => {
    const srv = fakeServer({ blob: seal({ u1: { key: 'b2xkLWtleQ==', state: 'pinned', firstSeenAt: 42 } }), version: 1 })

    await acceptKeyChange('u1', 'bmV3LWtleQ==')
    const pins = unsealPins(srv.state.blob!)
    expect(pins.u1.key).toBe('bmV3LWtleQ==')
    expect(pins.u1.firstSeenAt).toBe(42) // preserved across the re-pin
    expect((await getPin('u1'))?.key).toBe('bmV3LWtleQ==')
    expect(await checkAndPin('u1', 'bmV3LWtleQ==')).toBe('match') // the changed key now trusted
  })
})
