import { readonly, ref } from 'vue'
import logoDarkSvg from '../../assets/brand/logo-dark.svg?raw'
import logoLightSvg from '../../assets/brand/logo-light.svg?raw'

export type ThemePreference = 'system' | 'light' | 'dark'

const THEME_STORAGE_KEY = 'veyq:theme'
const FAVICON_ID = 'veyq-favicon'
const FAVICON_FALLBACK_SELECTOR = 'link.veyq-favicon-fallback[rel="icon"]'
const FAVICON_LIGHT_HREF = svgDataUrl(logoLightSvg)
const FAVICON_DARK_HREF = svgDataUrl(logoDarkSvg)
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
  applyFavicon(nextResolvedTheme)
}

function applyFavicon(nextResolvedTheme: 'light' | 'dark') {
  if (typeof document === 'undefined') return

  document.querySelectorAll(FAVICON_FALLBACK_SELECTOR).forEach((link) => link.remove())
  document.getElementById(FAVICON_ID)?.remove()

  const link = document.createElement('link')
  link.id = FAVICON_ID
  link.rel = 'icon'
  link.type = 'image/svg+xml'
  link.href = nextResolvedTheme === 'dark' ? FAVICON_DARK_HREF : FAVICON_LIGHT_HREF
  document.head.append(link)
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

function svgDataUrl(svg: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}
