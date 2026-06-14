// App-wide reactive session state. On web the instance *is* the host that served the page, so the
// API is same-origin (`/api/...`) — no instance URL to enter (that's a mobile concern). Persisted bits
// (theme) survive reloads; the unlocked keys never touch storage.
import { reactive } from 'vue'
import type { UnlockedSession } from '../features/account/login/login'

const THEME_KEY = 'veyq.theme'
type Theme = 'dark' | 'light'

export const session = reactive({
  apiBase: '', // same-origin: fetch hits `/api/...` on the instance that served this page
  theme: (localStorage.getItem(THEME_KEY) as Theme | null) ?? 'dark',
  unlocked: null as UnlockedSession | null,
})

export function applyTheme(theme: Theme): void {
  session.theme = theme
  document.documentElement.classList.toggle('veyq-dark', theme === 'dark')
  localStorage.setItem(THEME_KEY, theme)
}

export function setUnlocked(unlocked: UnlockedSession | null): void {
  session.unlocked = unlocked
}
