import { config } from '@vue/test-utils'
import { beforeEach } from 'vitest'
import { i18n, setLocale } from '../i18n'

config.global.plugins = [...(config.global.plugins || []), i18n]

beforeEach(() => {
  setLocale('de')
  Object.defineProperty(window, 'confirm', {
    configurable: true,
    value: () => true
  })
})
