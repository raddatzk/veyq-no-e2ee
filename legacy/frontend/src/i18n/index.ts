import { createI18n } from 'vue-i18n'
import { messages } from './messages'

export const SUPPORTED_LOCALES = ['de', 'en'] as const
export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number]
export const LOCALE_STORAGE_KEY = 'veyq:locale'

export const i18n = createI18n({
  legacy: false,
  locale: resolveInitialLocale(),
  fallbackLocale: 'de',
  messages
})

setDocumentLanguage(i18n.global.locale.value as SupportedLocale)

export function resolveInitialLocale(
  storage: Pick<Storage, 'getItem'> | null = safeLocalStorage(),
  languages: readonly string[] = safeNavigatorLanguages()
): SupportedLocale {
  const storedLocale = storage?.getItem(LOCALE_STORAGE_KEY)
  if (isSupportedLocale(storedLocale)) return storedLocale

  for (const language of languages) {
    const locale = normalizeLocale(language)
    if (locale) return locale
  }

  return 'de'
}

export function setLocale(locale: SupportedLocale) {
  i18n.global.locale.value = locale
  safeLocalStorage()?.setItem(LOCALE_STORAGE_KEY, locale)
  setDocumentLanguage(locale)
}

export function isSupportedLocale(value: unknown): value is SupportedLocale {
  return typeof value === 'string' && SUPPORTED_LOCALES.includes(value as SupportedLocale)
}

export function translateErrorCode(code: string): string | null {
  const key = `errors.${code}`
  return i18n.global.te(key) ? i18n.global.t(key) : null
}

function normalizeLocale(value: string): SupportedLocale | null {
  const primary = value.toLowerCase().split('-')[0]
  return isSupportedLocale(primary) ? primary : null
}

function safeLocalStorage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null
  }
}

function safeNavigatorLanguages(): readonly string[] {
  if (typeof navigator === 'undefined') return []
  return navigator.languages?.length ? navigator.languages : [navigator.language]
}

function setDocumentLanguage(locale: SupportedLocale) {
  if (typeof document !== 'undefined') document.documentElement.lang = locale
}
