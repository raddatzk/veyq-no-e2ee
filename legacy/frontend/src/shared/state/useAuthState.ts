import { authState, type TenantSummary } from '../../authState'

// Auth mutations now flow through app/session.ts (setUnlocked). What remains here is the space/tenant
// selection that legacy navigation reads — populated by the space layer during the port.
export function useAuthState() {
  function setActiveTenantName(tenantName: string) {
    if (!authState.tenants.some((tenant) => tenant.name === tenantName)) return
    authState.activeTenantName = tenantName
    storeActiveTenantName(tenantName)
  }

  function setTenants(tenants: TenantSummary[]) {
    authState.tenants = tenants
    if (!authState.activeTenantName || !tenants.some((t) => t.name === authState.activeTenantName)) {
      authState.activeTenantName = resolveActiveTenantName(tenants)
    }
  }

  function finishLoading() {
    authState.loading = false
  }

  return {
    authState,
    setActiveTenantName,
    setTenants,
    finishLoading,
  }
}

function resolveActiveTenantName(tenants: TenantSummary[]): string {
  const currentTenant = tenants.find((tenant) => tenant.domain && normalizedHost(tenant.domain) === currentHost())
  if (currentTenant) return currentTenant.name
  const stored = getStoredActiveTenantName()
  if (stored && tenants.some((tenant) => tenant.name === stored)) return stored
  return tenants[0]?.name || ''
}

function currentHost(): string {
  return normalizedHost(window.location.host)
}

function normalizedHost(value: string): string {
  return value.trim().toLowerCase().replace(/\.$/, '')
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
  const userId = authState.user?.id
  return userId ? `veyq:${userId}:last-active-tenant` : ''
}
