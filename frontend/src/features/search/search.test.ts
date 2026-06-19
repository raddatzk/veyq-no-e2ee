import { describe, expect, it } from 'vitest'
import { matchByName, matchByNameOrParticipants } from './search'

// uc.search.filter (web): the client substring-matches decrypted node names locally (server-blind).
// Pure logic extracted from searchNodes — no network.
describe('search name filter', () => {
  const items = [{ name: 'Italy 2024' }, { name: 'Italian dinner' }, { name: 'Paris' }]

  it('matches case-insensitively as a substring', () => {
    expect(matchByName(items, 'ital').map((i) => i.name)).toEqual(['Italy 2024', 'Italian dinner'])
    expect(matchByName(items, 'PARIS').map((i) => i.name)).toEqual(['Paris'])
  })

  it('returns no hits for an empty or whitespace query', () => {
    expect(matchByName(items, '')).toEqual([])
    expect(matchByName(items, '   ')).toEqual([])
  })

  it('returns no hits when nothing matches', () => {
    expect(matchByName(items, 'zzz')).toEqual([])
  })

  it('caps results at 25', () => {
    const many = Array.from({ length: 40 }, (_, i) => ({ name: `node ${i}` }))
    expect(matchByName(many, 'node').length).toBe(25)
  })

  it('matches decrypted participant names while returning memory hits', () => {
    const hits = [
      { name: 'Italy 2024', participantNames: ['Kevin', 'Mira'] },
      { name: 'Paris', participantNames: ['Sam'] },
    ]
    expect(matchByNameOrParticipants(hits, 'mira').map((i) => i.name)).toEqual(['Italy 2024'])
  })
})
