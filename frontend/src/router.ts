import { createRouter, createWebHistory, RouteLocationNormalized } from 'vue-router'
import { useAuthState } from './shared/state/useAuthState'
import { useServerState } from './shared/state/useServerState'
import HomeView from './views/HomeView.vue'
import LoginView from './views/LoginView.vue'
import InviteView from './views/InviteView.vue'
import EmailConfirmView from './views/EmailConfirmView.vue'
import ResetPasswordView from './views/ResetPasswordView.vue'
import SettingsView from './views/SettingsView.vue'
import SetupView from './views/SetupView.vue'
import TenantRouteView from './views/TenantRouteView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/setup', name: 'setup', component: SetupView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/invite/:userId', name: 'invite', component: InviteView },
    { path: '/password/reset/:userId', name: 'password-reset', component: ResetPasswordView },
    { path: '/email/confirm', name: 'email-confirm', component: EmailConfirmView },
    { path: '/settings', name: 'server-settings', component: SettingsView },
    { path: '/~me', name: 'user-settings', component: SettingsView },
    { path: '/:tenantName/:pathMatch(.*)*', name: 'tenant', component: TenantRouteView }
  ]
})

router.beforeEach((to) => {
  const { authState } = useAuthState()
  if (authState.loading) return true

  if (visitingAnyPageWithoutSetup(to)) return { name: 'setup' }

  if (visitingAnyPageWithSetupWithoutLogin(to)) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (visitingSetupPageWithSetup(to)) return `/${resolveHome()}`

  if (visitingHomePageWithSetupLoginAndTenants(to)) return `/${resolveHome()}`

  if (visitingTenant(to)) {
    const tenant = to.params.tenantName
    if (tenant) storeHome(Array.isArray(tenant) ? tenant[0] : tenant)
  }

  return true
})

function visitingAnyPageWithoutSetup(to: RouteLocationNormalized) {
  const { serverState } = useServerState()
  return !serverState.setupCompleted && to.name !== 'setup'
}

function visitingAnyPageWithSetupWithoutLogin(to: RouteLocationNormalized) {
  const { authState } = useAuthState()
  return !authState.isAuthenticated &&
    to.name !== 'login' &&
    to.name !== 'setup' &&
    to.name !== 'invite' &&
    to.name !== 'password-reset'
}

function visitingSetupPageWithSetup(to: RouteLocationNormalized) {
  const { serverState } = useServerState()
  return serverState.setupCompleted && to.name === 'setup'
}

function visitingHomePageWithSetupLoginAndTenants(to: RouteLocationNormalized) {
  const { authState } = useAuthState()
  return to.name === 'home' && authState.tenants.length !== 0
}

function visitingTenant(to: RouteLocationNormalized) {
  return to.name === 'tenant'
}

function getStoredTenant(): string | null{
  const { authState } = useAuthState()
  const { serverState } = useServerState()
  const serverId = serverState.serverId
  const userId = authState.user!.userId
  return localStorage.getItem(`lunva:${serverId}:${userId}:last-active-tenant`)
}

function resolveHome(): string {
  const { authState } = useAuthState()
  const storedTenant = getStoredTenant()

  return (storedTenant === null) ? (authState.tenants.length != 0 ? authState.tenants[0].name : '') : storedTenant
}

function storeHome(tenantName: string) {
  const { authState } = useAuthState()
  const { serverState } = useServerState()
  const serverId = serverState.serverId
  const userId = authState.user!.userId
  localStorage.setItem(`lunva:${serverId}:${userId}:last-active-tenant`, tenantName)
}

export default router
