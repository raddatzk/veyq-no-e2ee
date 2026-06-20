import { describe, expect, it } from 'vitest'
import { buildHomePath, matchSpace, parseHomePath } from './homeRoute'
import type { SpaceEntry } from '../spaces/switchActiveSpace/switchActiveSpace'

const kp = { suiteId: 'v1', kemPublicKey: 'x' }
const personal: SpaceEntry = { kind: 'PERSONAL', displayName: 'Me', scopeId: 'sp', scopePublicKey: kp }
const tenant: SpaceEntry = { kind: 'TENANT', displayName: 'Acme', tenantId: 't', domain: 'acme.veyq.app', scopeId: 'st', scopePublicKey: kp }
const tenant2: SpaceEntry = { kind: 'TENANT', displayName: 'Globex', tenantId: 't2', domain: 'globex.veyq.app', scopeId: 'st2', scopePublicKey: kp }
const group: SpaceEntry = { kind: 'GROUP', displayName: 'Reise Gruppe', tenantId: 't', domain: 'acme.veyq.app', scopeId: 'sg', scopePublicKey: kp }

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
      expect(matchSpace(spaces, route, 'acme.veyq.app')?.scopeId).toBe(entry.scopeId)
      expect(route.segments).toEqual(['a', 'b'])
    }
  })

  it('resolves the prefix-less tenant by host (multiple tenants)', () => {
    const spaces = [personal, tenant, tenant2]
    const route = parseHomePath('/urlaube')!
    expect(matchSpace(spaces, route, 'globex.veyq.app')?.scopeId).toBe('st2')
    expect(matchSpace(spaces, route, 'acme.veyq.app')?.scopeId).toBe('st')
    // unknown host → falls back to the first tenant
    expect(matchSpace(spaces, route, 'localhost:5173')?.scopeId).toBe('st')
  })
})
