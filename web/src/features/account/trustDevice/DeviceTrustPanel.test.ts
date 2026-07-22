// @vitest-environment happy-dom
// cr-untrust-device-button — the "This device" settings panel surfaces device-trust state and the untrust action
// over the already-built trustDevice logic (uc.account.trust-device § Untrust). Covers: a trusted device offers
// the untrust action; confirming calls untrustThisDevice and flips to untrusted; a not-trusted or unsupported
// device shows status only, no action.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type DOMWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

const isTrusted = vi.fn()
const untrustThisDevice = vi.fn()
const isTrustSupported = vi.fn()

vi.mock('./trustDevice', () => ({
  isTrusted: (...a: unknown[]) => isTrusted(...a),
  untrustThisDevice: (...a: unknown[]) => untrustThisDevice(...a),
  isTrustSupported: () => isTrustSupported(),
}))

import DeviceTrustPanel from './DeviceTrustPanel.vue'
import { account } from '../../../app/account'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      common: { cancel: 'Cancel' },
      unlock: { untrust: 'Untrust this device' },
      settings: {
        deviceTitle: 'This device',
        deviceSub: 's',
        deviceTrusted: 'trusted',
        deviceUntrusted: 'not trusted',
        deviceUnsupported: 'unsupported',
        untrustConfirmTitle: 'Untrust?',
        untrustConfirmBody: 'body',
        untrustFailed: 'failed',
      },
    },
  },
})

const stubs = {
  Dialog: { props: ['visible'], template: '<div class="dialog" v-if="visible"><slot /></div>' },
  Button: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const mountPanel = () => mount(DeviceTrustPanel, { global: { plugins: [i18n], stubs } })
const untrustButtons = (w: ReturnType<typeof mountPanel>): DOMWrapper<Element>[] =>
  w.findAll('button').filter((b) => b.text() === 'Untrust this device')

beforeEach(() => {
  account.email = 'me@x.z'
  isTrusted.mockReset().mockResolvedValue(true)
  untrustThisDevice.mockReset().mockResolvedValue(undefined)
  isTrustSupported.mockReset().mockReturnValue(true)
})

describe('DeviceTrustPanel', () => {
  it('a trusted device shows the trusted status + the untrust action', async () => {
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain('trusted')
    expect(untrustButtons(w)).toHaveLength(1)
  })

  it('untrust: confirming calls untrustThisDevice and the device flips to untrusted', async () => {
    isTrusted.mockResolvedValueOnce(true).mockResolvedValueOnce(false) // before untrust → after
    const w = mountPanel()
    await flushPromises()

    await untrustButtons(w)[0].trigger('click') // open the confirm dialog
    await flushPromises()
    await untrustButtons(w).at(-1)!.trigger('click') // the dialog's confirm button
    await flushPromises()

    expect(untrustThisDevice).toHaveBeenCalledWith('me@x.z')
    expect(w.text()).toContain('not trusted')
    expect(untrustButtons(w)).toHaveLength(0)
  })

  it('a not-trusted device shows status only, no action', async () => {
    isTrusted.mockResolvedValue(false)
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain('not trusted')
    expect(untrustButtons(w)).toHaveLength(0)
  })

  it('an unsupported browser shows the unsupported note, no action', async () => {
    isTrustSupported.mockReturnValue(false)
    isTrusted.mockResolvedValue(false)
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain('unsupported')
    expect(untrustButtons(w)).toHaveLength(0)
  })
})
