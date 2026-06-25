import { createRouter, createWebHistory, RouteLocationNormalized } from 'vue-router'
import { useAuthState } from './shared/state/useAuthState'
import { useServerState } from './shared/state/useServerState'
import { parseInviteHash } from './features/spaces/invite/inviteLink'
import LoginView from './views/LoginView.vue'
import HomeView from './views/HomeView.vue'
import MemoryJournalView from './views/MemoryJournalView.vue'
import InviteView from './views/InviteView.vue'
import EmailConfirmView from './views/EmailConfirmView.vue'
import PublicShareView from './views/PublicShareView.vue'
import ResetPasswordView from './views/ResetPasswordView.vue'
import SettingsView from './views/SettingsView.vue'
import SetupView from './views/SetupView.vue'
import TenantJoinView from './views/TenantJoinView.vue'
import TenantRouteView from './views/TenantRouteView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/setup', name: 'setup', component: SetupView },
    { path: '/login', name: 'login', component: LoginView },
    // Member-invite acceptance — the inviteId + secrets ride the URL fragment (#invite=…&s=…&k=…).
    { path: '/invite', name: 'invite', component: InviteView },
    { path: '/join', name: 'tenant-join', component: TenantJoinView },
    { path: '/join/:token', name: 'tenant-join-legacy', component: TenantJoinView },
    { path: '/s/:shareId/:pathMatch(.*)*', name: 'public-share', component: PublicShareView },
    { path: '/password/reset/:userId', name: 'password-reset', component: ResetPasswordView },
    { path: '/email/confirm', name: 'email-confirm', component: EmailConfirmView },
    { path: '/server/settings', name: 'server-settings', component: SettingsView },
    { path: '/space/settings', name: 'space-settings', component: SettingsView },
    { path: '/~me/settings', name: 'user-settings', component: SettingsView },
    // E2EE home + journal (focused memories slice). The legacy tenant router remains the catch-all.
    { path: '/', name: 'home', component: HomeView },
    { path: '/m/:nodeId', name: 'memory-journal', component: MemoryJournalView },
    { path: '/:pathMatch(.*)*', name: 'tenant', component: TenantRouteView }
  ]
})

router.beforeEach((to) => {
  const { authState } = useAuthState()
  if (authState.loading) return true

  // An invite link can land on any path with the invite in the fragment — route it to acceptance first.
  if (parseInviteHash(to.hash) && to.name !== 'invite') return { name: 'invite', hash: to.hash }

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
    to.name !== 'tenant-join' &&
    to.name !== 'tenant-join-legacy' &&
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
