import { describe, expect, it } from 'vitest'
import { buildInviteLink, parseInviteHash } from './inviteLink'

describe('inviteLink', () => {
  const parts = { inviteId: 'inv-1', bootstrapSecret: 'sec-2', tempPrivateKey: 'key-3' }

  it('round-trips an invite through a link fragment', () => {
    const link = buildInviteLink('https://veyq.app', parts)
    expect(link.startsWith('https://veyq.app/#')).toBe(true)
    const hash = link.slice(link.indexOf('#'))
    expect(parseInviteHash(hash)).toEqual(parts)
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
