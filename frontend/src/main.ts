import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import App from './App.vue'
import router from './router'
import { bootstrap as bootstrapClient } from './api/entrypointClient'
import { SetupRequiredError } from './shared/api/httpClient'
import { useAuthState } from './shared/state/useAuthState'
import { useServerState } from './shared/state/useServerState'
import './style.css'
import 'primeicons/primeicons.css'

async function bootstrap() {
  const { finishLoading, setAnonymous, setAuthenticated } = useAuthState()
  const { setSetupCompleted, setServerId} = useServerState()

  try {
    const entrypoint = await bootstrapClient()

    if (entrypoint.user) {
      setAuthenticated(entrypoint.user, entrypoint.tenants)
    } else {
      setAnonymous()
    }

    setSetupCompleted(true)
    setServerId(entrypoint.serverId)
  } catch (error) {
    setAnonymous()
    setSetupCompleted(!(error instanceof SetupRequiredError))
  } finally {
    finishLoading()
  }

  const app = createApp(App)
    .use(PrimeVue, {
      theme: {
        preset: Aura,
        options: {
          darkModeSelector: false
        }
      }
    })
    .use(router)

  router.isReady().then(() => {
    app.mount('#app')
  })
}

void bootstrap()
