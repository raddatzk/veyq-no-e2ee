import { describe, it, expect } from 'vitest'
import { buildCreateServerOwnerRequest } from './setupServer'
import * as v1 from '../../../shared/crypto/v1'

// Standard base64 (padded or not) — the encoding the operator-blind setup contract pins for its wire fields
// (`[A-Za-z0-9+/]*={0,2}`). A base64url value (with `-`/`_`) would be rejected by the backend at the boundary.
const STD_B64 = /^[A-Za-z0-9+/]*={0,2}$/

describe('setupServer — Model B request (uc.admin.setup-server)', () => {
  const { request, emergencyKit } = buildCreateServerOwnerRequest(
    'Owner@Example.com ',
    'Kevin',
    'correct horse battery',
    'Our Family veyq',
  )

  it('carries the contract metadata', () => {
    expect(request.email).toBe('Owner@Example.com ')
    expect(request.displayName).toBe('Kevin')
    expect(request.instanceName).toBe('Our Family veyq')
    expect(request.suiteId).toBe('v1')
  })

  it('never sends the raw password (operator-blind, ADR 0003)', () => {
    expect(JSON.stringify(request)).not.toContain('correct horse battery')
    expect((request as unknown as Record<string, unknown>).password).toBeUndefined()
  })

  it('registers only a standard-base64 auth public key', () => {
    expect(request.authPublicKey).toMatch(STD_B64)
    // 32-byte Ed25519 public key → 44 chars padded base64
    expect(request.authPublicKey.length).toBe(44)
  })

  it('emits standard-base64 (not base64url) for every opaque field', () => {
    for (const field of [request.identityPublicKey, request.wrappedIdentityPrivateKey, request.recoveryMasterKeyTag!]) {
      expect(field).toMatch(STD_B64)
    }
    for (const wrap of request.masterKeyWraps) {
      expect(wrap.wrappedKey).toMatch(STD_B64)
      expect(wrap.kdfParams).toMatch(STD_B64)
    }
    expect(emergencyKit.secretKey).toMatch(STD_B64)
  })

  it('wraps the master key under PASSWORD and RECOVERY, each carrying kdfParams', () => {
    const methods = request.masterKeyWraps.map((w) => w.method)
    expect(methods).toEqual(['PASSWORD', 'RECOVERY'])
    for (const wrap of request.masterKeyWraps) expect(wrap.kdfParams).toBeTruthy()
  })
})

describe('Model B auth derivation (ADR 0003)', () => {
  it('derives a deterministic salt + keypair from email+password', () => {
    const salt = v1.deriveAuthSalt('user@example.com')
    expect(v1.deriveAuthSalt('user@example.com')).toEqual(salt)
    // normalization: trim + lowercase
    expect(v1.deriveAuthSalt(' User@Example.com ')).toEqual(salt)

    const a = v1.deriveAuthKeypair('pw-abc', salt)
    const b = v1.deriveAuthKeypair('pw-abc', salt)
    expect(b.publicKey).toEqual(a.publicKey)
    expect(v1.deriveAuthKeypair('different', salt).publicKey).not.toEqual(a.publicKey)
  })
})
