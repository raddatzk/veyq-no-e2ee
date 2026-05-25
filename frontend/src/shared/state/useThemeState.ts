import { readonly, ref } from 'vue'

export type ThemePreference = 'system' | 'light' | 'dark'

const THEME_STORAGE_KEY = 'veyq:theme'
const themePreference = ref<ThemePreference>(readStoredThemePreference())
const resolvedTheme = ref<'light' | 'dark'>('light')
let mediaQuery: MediaQueryList | null = null

export function initializeTheme() {
  if (typeof window !== 'undefined') {
    mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    mediaQuery.addEventListener('change', applyTheme)
  }
  applyTheme()
}

export function useThemeState() {
  return {
    themePreference: readonly(themePreference),
    resolvedTheme: readonly(resolvedTheme),
    setThemePreference
  }
}

export function setThemePreference(preference: ThemePreference) {
  themePreference.value = preference
  storage()?.setItem(THEME_STORAGE_KEY, preference)
  applyTheme()
}

function applyTheme() {
  const nextResolvedTheme =
    themePreference.value === 'system' ? (mediaQuery?.matches ? 'dark' : 'light') : themePreference.value
  resolvedTheme.value = nextResolvedTheme

  if (typeof document === 'undefined') return
  document.documentElement.classList.toggle('veyq-dark', nextResolvedTheme === 'dark')
  document.documentElement.classList.toggle('veyq-light', nextResolvedTheme === 'light')
  document.documentElement.dataset.theme = themePreference.value
}

function readStoredThemePreference(): ThemePreference {
  const value = storage()?.getItem(THEME_STORAGE_KEY)
  return value === 'light' || value === 'dark' || value === 'system' ? value : 'system'
}

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null
  }
}
