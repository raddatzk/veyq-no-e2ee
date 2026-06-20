import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import Aura from '@primeuix/themes/aura'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n/i18n'
import { primeVueLocale } from './i18n/primevueLocale'
import { boot } from './app/boot'
import './style.css'
import 'primeicons/primeicons.css'

async function bootstrap() {
  // Discover the instance state (fresh → setup, set-up → login, unreachable → error) before mounting.
  await boot()

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
