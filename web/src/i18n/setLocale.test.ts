// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import {
  i18n,
  setLocalePreference,
  localePreference,
  effectiveLocale,
  SUPPORTED_LOCALES,
  LOCALE_PREFERENCES,
  LOCALE_LABELS,
} from './index'

beforeEach(() => {
  localStorage.clear()
  setLocalePreference('en')
})

describe('language preference', () => {
  it('switches to a concrete language + persists it', () => {
    setLocalePreference('de')
    expect(i18n.global.locale.value).toBe('de')
    expect(localStorage.getItem('veyq:locale')).toBe('de')
    expect(localePreference().value).toBe('de')
    expect(i18n.global.t('shell.spaces')).toBe('Bereiche')

    setLocalePreference('en')
    expect(i18n.global.t('shell.spaces')).toBe('Spaces')
  })

  it('"system" resolves to the OS language (a supported locale) + persists the preference', () => {
    setLocalePreference('system')
    expect(localStorage.getItem('veyq:locale')).toBe('system')
    expect(localePreference().value).toBe('system')
    expect(SUPPORTED_LOCALES).toContain(i18n.global.locale.value)
  })

  it('effectiveLocale maps a preference to a concrete locale', () => {
    expect(effectiveLocale('en')).toBe('en')
    expect(effectiveLocale('de')).toBe('de')
    expect(SUPPORTED_LOCALES).toContain(effectiveLocale('system'))
  })

  it('offers System + the two endonyms', () => {
    expect(LOCALE_PREFERENCES).toEqual(['system', 'en', 'de'])
    expect(LOCALE_LABELS).toEqual({ en: 'English', de: 'Deutsch' })
  })
})
