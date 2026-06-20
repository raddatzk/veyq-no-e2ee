import { createRouter, createWebHistory, RouteLocationNormalized } from 'vue-router'
import { serverState } from './serverState'
import { isUnlocked } from './app/session'
import { parseInviteHash } from './features/spaces/invite/inviteLink'
import { parseConfirmEmailHash } from './features/account/emailChange/emailChange'
import LoginView from './views/LoginView.vue'
import InviteView from './views/InviteView.vue'
import EmailConfirmView from './views/EmailConfirmView.vue'
import ParticipantProfileView from './views/ParticipantProfileView.vue'
import PublicShareView from './views/PublicShareView.vue'
import ResetPasswordView from './views/ResetPasswordView.vue'
import SettingsView from './views/SettingsView.vue'
import SetupView from './views/SetupView.vue'
import TenantRouteView from './views/TenantRouteView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/setup', name: 'setup', component: SetupView },
    { path: '/login', name: 'login', component: LoginView },
    // Member-invite acceptance — the inviteId + secrets ride the URL fragment (#invite=…&s=…&k=…).
    { path: '/invite', name: 'invite', component: InviteView },
    { path: '/s/:shareId/:pathMatch(.*)*', name: 'public-share', component: PublicShareView },
    { path: '/password/reset', name: 'password-reset', component: ResetPasswordView },
    // Email-change confirmation — the one-time token rides the URL fragment (#confirm-email=…); token-only.
    { path: '/account/confirm-email', name: 'confirm-email', component: EmailConfirmView },
    { path: '/server/settings', name: 'server-settings', component: SettingsView },
    { path: '/~me/settings', name: 'user-settings', component: SettingsView },
    // A participant's profile within the active space (their shared Memories) — requires an unlocked session.
    { path: '/~user/:linkedUserId', name: 'participant-profile', component: ParticipantProfileView },
    { path: '/:pathMatch(.*)*', name: 'tenant', component: TenantRouteView }
  ]
})

// Public routes: reachable while locked (no unlocked session). Everything else requires unlock.
const publicRouteNames = new Set(['login', 'setup', 'invite', 'public-share', 'password-reset', 'confirm-email'])

router.beforeEach((to) => {
  // An invite link can land on any path with the invite in the fragment — route it to acceptance first.
  if (parseInviteHash(to.hash) && to.name !== 'invite') return { name: 'invite', hash: to.hash }
  // Likewise an email-change confirmation link (#confirm-email=…) routes to the confirm view first.
  if (parseConfirmEmailHash(to.hash) && to.name !== 'confirm-email') return { name: 'confirm-email', hash: to.hash }
  if (visitingAnyPageWithoutSetup(to)) return { name: 'setup' }
  if (visitingSetupPageWithSetup(to)) return '/'
  if (!isUnlocked() && !publicRouteNames.has(String(to.name ?? ''))) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

function visitingAnyPageWithoutSetup(to: RouteLocationNormalized) {
  return !serverState.setupCompleted && to.name !== 'setup'
}

function visitingSetupPageWithSetup(to: RouteLocationNormalized) {
  return serverState.setupCompleted && to.name === 'setup'
}

export default router
