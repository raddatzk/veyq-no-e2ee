import { createRouter, createWebHistory } from 'vue-router'
import { useServerState } from './shared/state/useServerState'
import { session } from './app/session'
import { vaultState } from './app/vault'
import SetupView from './views/SetupView.vue'
import LoginView from './views/LoginView.vue'
import TenantsView from './views/TenantsView.vue'
import TenantJoinView from './views/TenantJoinView.vue'
import MemoryHomeView from './views/MemoryHomeView.vue'
import MemoryView from './views/MemoryView.vue'

// Setup slice routing: a fresh instance lands on /setup; an already-set-up instance on /login. /tenants is
// the first authenticated content route (Server-Owner tenant administration, cr-0009) — it needs a session;
// the backend enforces the Server-Owner role with a 403. The Memory shell (`/`, `/n/:nodeId`, cr-0030) is the
// authenticated layout (`meta.layout: 'app'`) and additionally needs an unlocked vault (`requiresUnlock`).
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: MemoryHomeView, meta: { layout: 'app', requiresUnlock: true } },
    { path: '/n/:nodeId', name: 'node', component: MemoryHomeView, meta: { layout: 'app', requiresUnlock: true } },
    { path: '/m/:nodeId', name: 'memory', component: MemoryView, meta: { layout: 'app', requiresUnlock: true } },
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
  // The Memory shell needs a session AND an unlocked vault (it derives K_SC from the vaulted identity).
  if (to.meta.requiresUnlock) {
    if (session.accessToken === null || !vaultState.unlocked) return { name: 'login' }
  }
  // Authenticated routes need a live session (an access token).
  if (to.name === 'tenants' && session.accessToken === null) return { name: 'login' }
  // An already-unlocked session on the login card jumps straight into the shell.
  if (to.name === 'login' && session.accessToken !== null && vaultState.unlocked) return { name: 'home' }
  return true
})

export default router
