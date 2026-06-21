import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import Aura from '@primeuix/themes/aura'
import { definePreset } from '@primeuix/themes'
import App from './App.vue'

// veyq is a purple-accented brand (see --app-accent: #5b4df5 light / #9b8cff dark in styles/base.css).
// Aura ships an emerald primary, which clashes with the purple shell. Remap PrimeVue's primary palette to
// the veyq violet so every PrimeVue control (buttons, segmented toggles, links …) matches the app chrome.
const VeyqAura = definePreset(Aura, {
  semantic: {
    primary: {
      50: '#f0eeff',
      100: '#e0dbff',
      200: '#c7bdff',
      300: '#a799ff',
      400: '#8b78fb',
      500: '#5b4df5',
      600: '#4f42e0',
      700: '#4436c4',
      800: '#392da0',
      900: '#2f2680',
      950: '#1f1857',
    },
  },
})
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
        preset: VeyqAura,
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
