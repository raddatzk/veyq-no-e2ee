import { config } from '@vue/test-utils'
import { beforeEach } from 'vitest'
import { i18n, setLocale } from '../i18n'

config.global.plugins = [...(config.global.plugins || []), i18n]

beforeEach(() => {
  setLocale('de')
  // Node-environment tests (pure logic/crypto, `@vitest-environment node`) have no DOM — only stub
  // window.confirm when a DOM is present (component tests run under happy-dom).
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'confirm', {
      configurable: true,
      value: () => true
    })
  }
})
