import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { authState } from './authState'
import { me, setupStatus, tenants } from './api/authClient'
import './style.css'

async function bootstrap() {
  try {
    const setup = await setupStatus()
    authState.setupRequired = setup.adminRequired

    if (!setup.adminRequired) {
      const current = await me()
      authState.isAuthenticated = true
      authState.userLabel = current.displayName || current.email
      authState.tenants = await tenants()
    }
  } catch {
    authState.isAuthenticated = false
    authState.userLabel = ''
    authState.tenants = []
  } finally {
    authState.loading = false
  }

  createApp(App).use(router).mount('#app')
}

void bootstrap()
