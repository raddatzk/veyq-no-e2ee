import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import App from './App.vue'
import router from './router'
import { authState } from './authState'
import { serverState } from './serverState'
import { bootstrap as bootstrapClient } from './api/entrypointClient'
import { SetupRequiredError } from './api/httpClient'
import './style.css'
import 'primeicons/primeicons.css'

async function bootstrap() {
  try {
    const entrypoint = await bootstrapClient()

    authState.isAuthenticated = entrypoint.user !== null
    authState.user = entrypoint.user
    authState.userLabel = entrypoint.user?.displayName || entrypoint.user?.email || ''
    authState.tenants = entrypoint.tenants

    serverState.setupCompleted = true
  } catch (e) {
    authState.isAuthenticated = false
    authState.userLabel = ''
    authState.user = null
    authState.tenants = []

    serverState.setupCompleted = !(e instanceof SetupRequiredError)
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
