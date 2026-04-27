import { createRouter, createWebHashHistory } from 'vue-router'
import { authState } from './authState'
import HomeView from './views/HomeView.vue'
import LoginView from './views/LoginView.vue'
import SetupView from './views/SetupView.vue'
import TenantView from './views/TenantView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/setup', name: 'setup', component: SetupView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/:tenantName', name: 'tenant', component: TenantView }
  ]
})

router.beforeEach((to) => {
  if (authState.loading) return true
  if (authState.setupRequired && to.name !== 'setup') return { name: 'setup' }
  if (!authState.setupRequired && to.name === 'setup') return { name: 'home' }
  if (!authState.isAuthenticated && to.name === 'tenant') return { name: 'login' }
  return true
})

export default router
