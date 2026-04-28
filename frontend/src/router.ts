import { createRouter, createWebHistory } from 'vue-router'
import { useAuthState } from './shared/state/useAuthState'
import { useServerState } from './shared/state/useServerState'
import HomeView from './views/HomeView.vue'
import LoginView from './views/LoginView.vue'
import SetupView from './views/SetupView.vue'
import TenantView from './views/TenantView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/setup', name: 'setup', component: SetupView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/:tenantName/:pathMatch(.*)*', name: 'tenant', component: TenantView }
  ]
})

router.beforeEach((to) => {
  const { authState } = useAuthState()
  const { serverState } = useServerState()

  if (authState.loading) return true
  if (!serverState.setupCompleted && to.name !== 'setup') return { name: 'setup' }
  if (serverState.setupCompleted && to.name === 'setup') return { name: 'home' }
  if (!authState.isAuthenticated && to.name === 'tenant') return { name: 'login' }
  return true
})

export default router
