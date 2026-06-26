import { createRouter, createWebHistory } from 'vue-router'
import { useServerState } from './shared/state/useServerState'
import { session } from './app/session'
import SetupView from './views/SetupView.vue'
import LoginView from './views/LoginView.vue'
import TenantsView from './views/TenantsView.vue'
import TenantJoinView from './views/TenantJoinView.vue'

// Setup slice routing: a fresh instance lands on /setup; an already-set-up instance on /login. /tenants is
// the first authenticated content route (Server-Owner tenant administration, cr-0009) — it needs a session;
// the backend enforces the Server-Owner role with a 403.
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/setup', name: 'setup', component: SetupView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/tenants', name: 'tenants', component: TenantsView },
    // Public invitee-acceptance deep link (the bootstrap secret rides in the #fragment). No session gate —
    // preview/register are public and the existing-owner path signs in within the view (cr-0010).
    { path: '/tenant/accept', name: 'tenant-accept', component: TenantJoinView },
    { path: '/:pathMatch(.*)*', redirect: { name: 'login' } },
  ],
})

router.beforeEach((to) => {
  const { serverState } = useServerState()
  // Boot hasn't answered yet (null) — let navigation through; boot resolves before mount.
  if (serverState.setupCompleted === null) return true
  if (!serverState.setupCompleted && to.name !== 'setup') return { name: 'setup' }
  if (serverState.setupCompleted && to.name === 'setup') return { name: 'login' }
  // Authenticated routes need a live session (an access token).
  if (to.name === 'tenants' && session.accessToken === null) return { name: 'login' }
  return true
})

export default router
