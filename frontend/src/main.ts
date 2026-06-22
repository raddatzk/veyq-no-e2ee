import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import Aura from '@primeuix/themes/aura'
import App from './App.vue'
import router from './router'
import { i18n, type SupportedLocale } from './i18n'
import { primeVueLocale } from './i18n/primevueLocale'
import { boot } from './app/boot'
import './style.css'
import 'primeicons/primeicons.css'

async function bootstrap() {
  // Discover the instance state (fresh → setup, set-up → login, unreachable → error) before mounting.
  // The E2EE session lives in app/session; the legacy `authState` is now a reactive bridge over it.
  await boot()

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
