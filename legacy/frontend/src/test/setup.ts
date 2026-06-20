import { beforeEach } from 'vitest'
import { setLocale } from '../i18n/i18n'

beforeEach(() => {
  setLocale('de')
  Object.defineProperty(window, 'confirm', {
    configurable: true,
    value: () => true
  })
})
