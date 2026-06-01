import { createRouter, createWebHistory, RouteLocationNormalized } from 'vue-router'
import { useAuthState } from './shared/state/useAuthState'
import { useServerState } from './shared/state/useServerState'
import LoginView from './views/LoginView.vue'
import InviteView from './views/InviteView.vue'
import EmailConfirmView from './views/EmailConfirmView.vue'
import PublicShareView from './views/PublicShareView.vue'
import ResetPasswordView from './views/ResetPasswordView.vue'
import ResetPasskeyView from './views/ResetPasskeyView.vue'
import SettingsView from './views/SettingsView.vue'
import SetupView from './views/SetupView.vue'
import TenantRouteView from './views/TenantRouteView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/setup', name: 'setup', component: SetupView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/invite/:userId', name: 'invite', component: InviteView },
    { path: '/s/:shareId/:pathMatch(.*)*', name: 'public-share', component: PublicShareView },
    { path: '/password/reset/:userId', name: 'password-reset', component: ResetPasswordView },
    { path: '/passkey/reset/:userId', name: 'passkey-reset', component: ResetPasskeyView },
    { path: '/email/confirm', name: 'email-confirm', component: EmailConfirmView },
    { path: '/server/settings', name: 'server-settings', component: SettingsView },
    { path: '/~me/settings', name: 'user-settings', component: SettingsView },
    { path: '/:pathMatch(.*)*', name: 'tenant', component: TenantRouteView }
  ]
})

router.beforeEach((to) => {
  const { authState } = useAuthState()
  if (authState.loading) return true

  if (visitingAnyPageWithoutSetup(to)) return { name: 'setup' }

  if (visitingAnyPageWithSetupWithoutLogin(to)) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (visitingSetupPageWithSetup(to)) return '/'

  return true
})

function visitingAnyPageWithoutSetup(to: RouteLocationNormalized) {
  const { serverState } = useServerState()
  return !serverState.setupCompleted && to.name !== 'setup'
}

function visitingAnyPageWithSetupWithoutLogin(to: RouteLocationNormalized) {
  const { authState } = useAuthState()
  return (
    !authState.isAuthenticated &&
    to.name !== 'login' &&
    to.name !== 'setup' &&
    to.name !== 'invite' &&
    to.name !== 'public-share' &&
    to.name !== 'password-reset' &&
    to.name !== 'passkey-reset' &&
    to.name !== 'email-confirm'
  )
}

function visitingSetupPageWithSetup(to: RouteLocationNormalized) {
  const { serverState } = useServerState()
  return serverState.setupCompleted && to.name === 'setup'
}

export default router
