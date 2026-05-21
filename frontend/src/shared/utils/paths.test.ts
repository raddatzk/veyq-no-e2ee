import { describe, expect, it } from 'vitest'
import {
  memoryPathToTenantRoute,
  isSettingsPath,
  pathQuery,
  routePathToMemoryPath,
  tenantApiPath
} from './paths'

describe('path utilities', () => {
  it('converts route path params to memory paths', () => {
    expect(routePathToMemoryPath(undefined)).toBe('/')
    expect(routePathToMemoryPath('photos')).toBe('/photos')
    expect(routePathToMemoryPath(['@family', 'trip'])).toBe('/@family/trip')
  })

  it('converts memory paths to tenant routes', () => {
    expect(memoryPathToTenantRoute('friends', '/')).toBe('/friends')
    expect(memoryPathToTenantRoute('friends', '/@family/trip')).toBe('/friends/@family/trip')
  })

  it('encodes api paths and path queries', () => {
    expect(tenantApiPath('my tenant', 'memory-navigation', '?path=%2F')).toBe('/api/my%20tenant/memory-navigation?path=%2F')
    expect(pathQuery('/@family/trip')).toBe('path=%2F%40family%2Ftrip')
  })

  it('detects settings paths by the final segment', () => {
    expect(isSettingsPath('/settings')).toBe(true)
    expect(isSettingsPath('/@family/settings')).toBe(true)
    expect(isSettingsPath('/@family/settings/archive')).toBe(false)
    expect(isSettingsPath('/@family/photos')).toBe(false)
  })
})
