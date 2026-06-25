import { createRouter, createWebHistory } from 'vue-router'
import { useServerState } from './shared/state/useServerState'
import SetupView from './views/SetupView.vue'
import LoginView from './views/LoginView.vue'

// Setup slice routing: a fresh instance lands on /setup; an already-set-up instance on the (stubbed) /login.
// The auth-gated content routes from the legacy app arrive with the login CR.
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/setup', name: 'setup', component: SetupView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/:pathMatch(.*)*', redirect: { name: 'login' } },
  ],
})

router.beforeEach((to) => {
  const { serverState } = useServerState()
  // Boot hasn't answered yet (null) — let navigation through; boot resolves before mount.
  if (serverState.setupCompleted === null) return true
  if (!serverState.setupCompleted && to.name !== 'setup') return { name: 'setup' }
  if (serverState.setupCompleted && to.name === 'setup') return { name: 'login' }
  return true
})

export default router
