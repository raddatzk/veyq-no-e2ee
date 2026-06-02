import { afterEach, describe, expect, it } from 'vitest'
import { authState, type TenantSummary } from '../../authState'
import { serverState } from '../../serverState'
import { useAuthState } from './useAuthState'

describe('useAuthState', () => {
  afterEach(() => {
    localStorage.clear()
    authState.isAuthenticated = false
    authState.userLabel = ''
    authState.user = null
    authState.tenants = []
    authState.activeTenantName = ''
    serverState.serverId = ''
  })

  it('restores the stored active tenant for the current server and user', () => {
    serverState.serverId = 'server-1'
    localStorage.setItem('veyq:server-1:7:last-active-tenant', 'family')

    useAuthState().setAuthenticated(
      {
        userId: 7,
        email: 'kevin@example.test',
        displayName: 'Kevin',
        permalink: 'kevin',
        serverRole: 'ServerMember',
        passkeyCount: 0,
        hasPassword: true
      },
      [tenant('friends'), tenant('family')]
    )

    expect(authState.activeTenantName).toBe('family')
  })

  it('prefers the tenant for the current domain over the stored active tenant', () => {
    serverState.serverId = 'server-1'
    localStorage.setItem('veyq:server-1:7:last-active-tenant', 'family')

    useAuthState().setAuthenticated(
      {
        userId: 7,
        email: 'kevin@example.test',
        displayName: 'Kevin',
        permalink: 'kevin',
        serverRole: 'ServerMember',
        passkeyCount: 0,
        hasPassword: true
      },
      [tenant('friends', window.location.host), tenant('family')]
    )

    expect(authState.activeTenantName).toBe('friends')
  })

  it('stores active tenant changes for the current server and user', () => {
    serverState.serverId = 'server-1'
    useAuthState().setAuthenticated(
      {
        userId: 7,
        email: 'kevin@example.test',
        displayName: 'Kevin',
        permalink: 'kevin',
        serverRole: 'ServerMember',
        passkeyCount: 0,
        hasPassword: true
      },
      [tenant('friends'), tenant('family')]
    )

    useAuthState().setActiveTenantName('family')

    expect(localStorage.getItem('veyq:server-1:7:last-active-tenant')).toBe('family')
  })

  it('preserves an explicit active tenant while refreshing tenant summaries on another tenant domain', () => {
    serverState.serverId = 'server-1'
    useAuthState().setAuthenticated(
      {
        userId: 7,
        email: 'kevin@example.test',
        displayName: 'Kevin',
        permalink: 'kevin',
        serverRole: 'ServerMember',
        passkeyCount: 0,
        hasPassword: true
      },
      [tenant('friends', window.location.host), tenant('family')]
    )

    useAuthState().setActiveTenantName('family')
    useAuthState().setTenants([
      tenant('friends', window.location.host),
      tenant('family', 'family-renamed.example.test')
    ])

    expect(authState.activeTenantName).toBe('family')
  })
})

function tenant(name: string, domain = `${name}.example.test`): TenantSummary {
  return {
    id: name === 'friends' ? 1 : 2,
    name,
    displayName: name,
    domain,
    role: 'TenantMember',
    admins: []
  }
}
