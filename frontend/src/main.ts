import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { authState } from './authState'
import { me } from './api/authClient'
import './style.css'

async function bootstrap() {
  try {
    const current = await me()
    authState.isAuthenticated = true
    authState.username = current.displayName || current.username
  } catch {
    authState.isAuthenticated = false
    authState.username = ''
  } finally {
    authState.loading = false
  }

  createApp(App).use(router).mount('#app')
}

void bootstrap()
