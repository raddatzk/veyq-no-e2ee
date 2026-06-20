import { beforeEach } from 'vitest'
import { setLocale } from '../i18n/i18n'

beforeEach(() => {
  setLocale('de')
  // node-env test files (logic/e2e suites) have no DOM — only stub window.confirm when a DOM exists.
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'confirm', {
      configurable: true,
      value: () => true
    })
  }
})
