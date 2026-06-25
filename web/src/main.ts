import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { boot } from './app/boot'
import './style.css'
import 'primeicons/primeicons.css'

async function bootstrap() {
  // Discover the instance state (fresh → setup, set-up → login, unreachable → error banner) before mounting,
  // so the router lands on the right first screen.
  await boot()

  const app = createApp(App)
    .use(PrimeVue, { theme: { preset: Aura, options: { darkModeSelector: '.veyq-dark' } } })
    .use(i18n)
    .use(router)

  router.isReady().then(() => app.mount('#app'))
}

void bootstrap()
