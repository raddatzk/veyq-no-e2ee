import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import Aura from '@primeuix/themes/aura'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n/i18n'
import { primeVueLocale } from './i18n/primevueLocale'
import { useAuthState } from './shared/state/useAuthState'
import { useServerState } from './shared/state/useServerState'
import { initializeTheme } from './shared/state/useThemeState'
import { getSetupStatus } from './features/account/instance/instance'
import { login } from './features/account/login/login'
import { clearRememberedLogin, readRememberedLogin } from './features/account/rememberedLogin/rememberedLogin'
import { session, setUnlocked } from './app/session'
import './style.css'
import 'primeicons/primeicons.css'

// Re-login from stored credentials (email + password + Secret Key) — the E2EE replacement for a refresh
// token. A fresh login also re-derives the master/identity keys, which a token refresh never could.
async function tryRememberedLogin(): Promise<void> {
  const creds = readRememberedLogin()
  if (!creds) return
  try {
    setUnlocked(await login(session.apiBase, creds.email, creds.password, creds.secretKey))
  } catch {
    clearRememberedLogin()
  }
}

async function bootstrap() {
  initializeTheme()
  const { finishLoading } = useAuthState()
  const { setSetupCompleted } = useServerState()

  try {
    const status = await getSetupStatus(session.apiBase)
    setSetupCompleted(!status.setupRequired)
    if (!status.setupRequired) await tryRememberedLogin()
  } catch {
    // Backend unreachable — don't force the setup screen; the login screen surfaces the error on submit.
    setSetupCompleted(true)
  } finally {
    finishLoading()
  }

  const app = createApp(App)
    .use(PrimeVue, {
      locale: primeVueLocale(i18n.locale),
      theme: {
        preset: Aura,
        options: {
          darkModeSelector: '.veyq-dark'
        }
      }
    })
    .use(router)

  app.directive('tooltip', Tooltip)

  router.isReady().then(() => {
    app.mount('#app')
  })
}

void bootstrap()
