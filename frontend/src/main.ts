import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import Aura from '@primeuix/themes/aura'
import App from './App.vue'
import router from './router'
import { bootstrap as bootstrapClient } from './api/entrypointClient'
import { i18n, type SupportedLocale } from './i18n'
import { primeVueLocale } from './i18n/primevueLocale'
import { SetupRequiredError } from './shared/api/httpClient'
import { useAuthState } from './shared/state/useAuthState'
import { useServerState } from './shared/state/useServerState'
import { initializeTheme } from './shared/state/useThemeState'
import './style.css'
import 'primeicons/primeicons.css'

async function bootstrap() {
  initializeTheme()
  const { finishLoading, setAnonymous, setAuthenticated } = useAuthState()
  const { setSetupCompleted, setServerId } = useServerState()

  try {
    const entrypoint = await bootstrapClient()

    setServerId(entrypoint.serverId)
    if (entrypoint.user) {
      setAuthenticated(entrypoint.user, entrypoint.tenants)
    } else {
      setAnonymous()
    }

    setSetupCompleted(true)
  } catch (error) {
    setAnonymous()
    setSetupCompleted(!(error instanceof SetupRequiredError))
  } finally {
    finishLoading()
  }

  const initialLocale = i18n.global.locale.value as SupportedLocale
  const app = createApp(App)
    .use(PrimeVue, {
      locale: primeVueLocale(initialLocale),
      theme: {
        preset: Aura,
        options: {
          darkModeSelector: '.veyq-dark'
        }
      }
    })
    .use(i18n)
    .use(router)

  app.directive('tooltip', Tooltip)

  router.isReady().then(() => {
    app.mount('#app')
  })
}

void bootstrap()
