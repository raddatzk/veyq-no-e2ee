// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { i18n, setLocale, SUPPORTED_LOCALES, LOCALE_LABELS } from './index'

beforeEach(() => {
  localStorage.clear()
  setLocale('en')
})

describe('setLocale', () => {
  it('switches the active UI locale and persists it', () => {
    setLocale('de')
    expect(i18n.global.locale.value).toBe('de')
    expect(localStorage.getItem('veyq:locale')).toBe('de')
    // a real string actually changes
    expect(i18n.global.t('shell.spaces')).toBe('Bereiche')

    setLocale('en')
    expect(i18n.global.t('shell.spaces')).toBe('Spaces')
  })

  it('exposes the supported locales + their endonyms', () => {
    expect(SUPPORTED_LOCALES).toEqual(['en', 'de'])
    expect(LOCALE_LABELS).toEqual({ en: 'English', de: 'Deutsch' })
  })
})
