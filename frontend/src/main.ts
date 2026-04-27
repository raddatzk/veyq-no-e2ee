import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import App from './App.vue'
import router from './router'
import { authState } from './authState'
import { bootstrap as bootstrapSession } from './api/bootstrapClient'
import './style.css'
import 'primeicons/primeicons.css'

async function bootstrap() {
  try {
    const session = await bootstrapSession()
    authState.setupRequired = session.adminRequired

    authState.user = session.user
    authState.isAuthenticated = session.user !== null
    authState.userLabel = session.user?.displayName || session.user?.email || ''
    authState.tenants = session.tenants
  } catch {
    authState.isAuthenticated = false
    authState.userLabel = ''
    authState.user = null
    authState.tenants = []
  } finally {
    authState.loading = false
  }

  createApp(App)
    .use(PrimeVue, {
      theme: {
        preset: Aura,
        options: {
          darkModeSelector: false
        }
      }
    })
    .use(router)
    .mount('#app')
}

void bootstrap()
