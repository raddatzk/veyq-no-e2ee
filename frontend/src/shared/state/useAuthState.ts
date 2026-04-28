import { authState, type TenantSummary, type UserSummary } from '../../authState'

export function useAuthState() {
  function setAuthenticated(user: UserSummary, tenants: TenantSummary[]) {
    authState.isAuthenticated = true
    authState.user = user
    authState.userLabel = user.displayName || user.email
    authState.tenants = tenants
  }

  function setAnonymous() {
    authState.isAuthenticated = false
    authState.userLabel = ''
    authState.user = null
    authState.tenants = []
  }

  function setTenants(tenants: TenantSummary[]) {
    authState.tenants = tenants
  }

  function finishLoading() {
    authState.loading = false
  }

  return {
    authState,
    setAuthenticated,
    setAnonymous,
    setTenants,
    finishLoading
  }
}
