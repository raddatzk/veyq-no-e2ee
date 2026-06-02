import { authState, type TenantSummary, type UserSummary } from '../../authState'
import { useServerState } from './useServerState'

export function useAuthState() {
  function setAuthenticated(user: UserSummary, tenants: TenantSummary[]) {
    authState.isAuthenticated = true
    authState.user = user
    authState.userLabel = user.displayName || user.email
    authState.tenants = tenants
    authState.activeTenantName = resolveActiveTenantName(tenants)
  }

  function setAnonymous() {
    authState.isAuthenticated = false
    authState.userLabel = ''
    authState.user = null
    authState.tenants = []
    authState.activeTenantName = ''
  }

  function setTenants(tenants: TenantSummary[]) {
    authState.tenants = tenants
    authState.activeTenantName = resolveRefreshedActiveTenantName(tenants)
  }

  function setActiveTenantName(tenantName: string) {
    if (!authState.tenants.some((tenant) => tenant.name === tenantName)) return
    authState.activeTenantName = tenantName
    storeActiveTenantName(tenantName)
  }

  function finishLoading() {
    authState.loading = false
  }

  return {
    authState,
    setAuthenticated,
    setAnonymous,
    setActiveTenantName,
    setTenants,
    finishLoading
  }
}

function resolveActiveTenantName(tenants: TenantSummary[]): string {
  const currentTenant = tenants.find((tenant) => tenant.domain === currentHost())
  if (currentTenant) return currentTenant.name
  const stored = getStoredActiveTenantName()
  if (stored && tenants.some((tenant) => tenant.name === stored)) return stored
  return tenants[0]?.name || ''
}

function resolveRefreshedActiveTenantName(tenants: TenantSummary[]): string {
  if (authState.activeTenantName && tenants.some((tenant) => tenant.name === authState.activeTenantName)) {
    return authState.activeTenantName
  }
  return resolveActiveTenantName(tenants)
}

function currentHost(): string {
  return window.location.host.toLowerCase()
}

function getStoredActiveTenantName(): string {
  const key = activeTenantStorageKey()
  return key ? localStorage.getItem(key) || '' : ''
}

function storeActiveTenantName(tenantName: string) {
  const key = activeTenantStorageKey()
  if (key) localStorage.setItem(key, tenantName)
}

function activeTenantStorageKey(): string {
  const { serverState } = useServerState()
  const serverId = serverState.serverId
  const userId = authState.user?.userId
  return serverId && userId ? `veyq:${serverId}:${userId}:last-active-tenant` : ''
}
