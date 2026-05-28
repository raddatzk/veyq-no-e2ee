import { describe, expect, it } from 'vitest'
import { i18n, LOCALE_STORAGE_KEY, resolveInitialLocale, setLocale } from '.'

function storage(value: string | null) {
  return {
    getItem: (key: string) => (key === LOCALE_STORAGE_KEY ? value : null)
  }
}

describe('i18n locale handling', () => {
  it('prefers stored locale before browser language', () => {
    expect(resolveInitialLocale(storage('en'), ['de-DE'])).toBe('en')
  })

  it('uses supported browser languages and falls back to German', () => {
    expect(resolveInitialLocale(storage(null), ['en-US'])).toBe('en')
    expect(resolveInitialLocale(storage(null), ['de-AT'])).toBe('de')
    expect(resolveInitialLocale(storage(null), ['fr-FR'])).toBe('de')
  })

  it('stores locale and updates document language', () => {
    setLocale('en')

    expect(i18n.global.locale.value).toBe('en')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en')
    expect(document.documentElement.lang).toBe('en')
  })

  it('localizes upload eta capability labels', () => {
    expect(i18n.global.te('capabilities.items.memory_media_upload_eta.title')).toBe(true)
    expect(i18n.global.te('capabilities.items.memory_media_upload_eta.description')).toBe(true)

    setLocale('en')
    expect(i18n.global.t('capabilities.items.memory_media_upload_eta.title')).toBe(
      'Upload time remaining estimates'
    )
  })
})
