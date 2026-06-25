import { describe, expect, it } from 'vitest'
import { buildInviteLink, buildOwnerInviteLink, buildProtectedInviteLink, isProtectedInvite, parseInviteHash } from './inviteLink'

describe('inviteLink', () => {
  const parts = { inviteId: 'inv-1', bootstrapSecret: 'sec-2', tempPrivateKey: 'key-3' }
  const prot = { inviteId: 'inv-1', bootstrapSecret: 'sec-2', encTempKey: 'enc-9', pwSalt: 'salt-8' }

  it('round-trips a pragmatic invite through a link fragment', () => {
    const link = buildInviteLink('https://veyq.app', parts)
    expect(link.startsWith('https://veyq.app/#')).toBe(true)
    const parsed = parseInviteHash(link.slice(link.indexOf('#')))
    expect(parsed).toEqual(parts)
    expect(isProtectedInvite(parsed!)).toBe(false)
  })

  it('round-trips a passphrase-protected invite (ek/ps, no k)', () => {
    const link = buildProtectedInviteLink('https://veyq.app', prot)
    expect(link).toContain('ek=enc-9')
    expect(link).not.toContain('&k=')
    const parsed = parseInviteHash(link.slice(link.indexOf('#')))
    expect(parsed).toEqual(prot)
    expect(isProtectedInvite(parsed!)).toBe(true)
  })

  it('round-trips an owner invite without member key material', () => {
    const owner = { inviteId: 'owner-inv', bootstrapSecret: 'owner-sec' }
    const link = buildOwnerInviteLink('https://veyq.app', owner)
    expect(link).toBe('https://veyq.app/#invite=owner-inv&s=owner-sec')
    expect(parseInviteHash(link.slice(link.indexOf('#')))).toEqual(owner)
  })

  it('keeps the secrets in the fragment (not the query)', () => {
    const link = buildInviteLink('https://veyq.app/', parts)
    expect(link.indexOf('?')).toBe(-1)
    expect(link).toContain('#invite=inv-1')
  })

  it('returns null for non-invite hashes', () => {
    expect(parseInviteHash('')).toBeNull()
    expect(parseInviteHash('#invite=only-id')).toBeNull()
    expect(parseInviteHash('#foo=bar')).toBeNull()
  })
})
