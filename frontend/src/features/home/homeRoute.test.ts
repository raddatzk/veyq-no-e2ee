import { describe, expect, it } from 'vitest'
import { buildHomePath, matchSpace, parseHomePath } from './homeRoute'
import type { SpaceEntry } from '../spaces/switchActiveSpace/switchActiveSpace'

const kp = { suiteId: 'v1', kemPublicKey: 'x' }
const personal: SpaceEntry = { kind: 'PERSONAL', displayName: 'Me', scopeId: 'sp', scopePublicKey: kp }
const tenant: SpaceEntry = { kind: 'TENANT', displayName: 'Acme', tenantId: 't', scopeId: 'st', scopePublicKey: kp }
const group: SpaceEntry = { kind: 'GROUP', displayName: 'Reise Gruppe', tenantId: 't', scopeId: 'sg', scopePublicKey: kp }

describe('homeRoute', () => {
  it('builds tenant paths with no prefix', () => {
    expect(buildHomePath(tenant, ['urlaube', 'italien'])).toBe('/urlaube/italien')
    expect(buildHomePath(tenant, [], undefined)).toBe('/')
    expect(buildHomePath(tenant, ['urlaube'], 'tag-1')).toBe('/urlaube/tag-1')
  })

  it('prefixes personal with ~me and groups with @<name-slug>', () => {
    expect(buildHomePath(personal, ['urlaube'])).toBe('/~me/urlaube')
    expect(buildHomePath(group, ['urlaube', 'italien'])).toBe('/@reise-gruppe/urlaube/italien')
  })

  it('parses each space kind back out', () => {
    expect(parseHomePath('/urlaube/italien')).toEqual({ kind: 'TENANT', segments: ['urlaube', 'italien'] })
    expect(parseHomePath('/~me/urlaube')).toEqual({ kind: 'PERSONAL', segments: ['urlaube'] })
    expect(parseHomePath('/@reise-gruppe/urlaube/italien')).toEqual({ kind: 'GROUP', groupSlug: 'reise-gruppe', segments: ['urlaube', 'italien'] })
    expect(parseHomePath('/')).toBeNull()
  })

  it('round-trips through build → parse → matchSpace', () => {
    const spaces = [personal, tenant, group]
    for (const entry of spaces) {
      const path = buildHomePath(entry, ['a', 'b'])
      const route = parseHomePath(path)!
      expect(matchSpace(spaces, route)?.scopeId).toBe(entry.scopeId)
      expect(route.segments).toEqual(['a', 'b'])
    }
  })
})
