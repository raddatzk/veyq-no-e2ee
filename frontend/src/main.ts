import { createApp } from 'vue'
import './styles/tokens.css'
import './styles/auth.css'
import './styles/home.css'
import './styles/gallery.css'
import { i18n } from './i18n/i18n'
import App from './App.vue'

document.documentElement.lang = i18n.locale
createApp(App).mount('#app')
