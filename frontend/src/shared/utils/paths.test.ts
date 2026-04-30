import { describe, expect, it } from 'vitest'
import {
  explorerPathToTenantRoute,
  pathQuery,
  routePathToExplorerPath,
  tenantApiPath
} from './paths'

describe('path utilities', () => {
  it('converts route path params to explorer paths', () => {
    expect(routePathToExplorerPath(undefined)).toBe('/')
    expect(routePathToExplorerPath('photos')).toBe('/photos')
    expect(routePathToExplorerPath(['@family', 'trip'])).toBe('/@family/trip')
  })

  it('converts explorer paths to tenant routes', () => {
    expect(explorerPathToTenantRoute('friends', '/')).toBe('/friends')
    expect(explorerPathToTenantRoute('friends', '/@family/trip')).toBe('/friends/@family/trip')
  })

  it('encodes api paths and path queries', () => {
    expect(tenantApiPath('my tenant', 'explorer', '?path=%2F')).toBe('/api/my%20tenant/explorer?path=%2F')
    expect(pathQuery('/@family/trip')).toBe('path=%2F%40family%2Ftrip')
  })
})
