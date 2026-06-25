import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PrimeVue from 'primevue/config'
import UploaderShareDialog from './UploaderShareDialog.vue'
import { i18n } from '../../../i18n'
import { createShare, listShares, revokeShare } from '../../memory/share/share'

vi.mock('../../memory/share/share', () => ({
  createShare: vi.fn(),
  listShares: vi.fn(),
  revokeShare: vi.fn(),
}))
vi.mock('../../../app/session', () => ({
  session: { apiBase: '' },
  requireSession: () => ({ sessionToken: 't' }),
}))

let wrapper: VueWrapper | null = null

async function mountDialog() {
  wrapper = mount(UploaderShareDialog, {
    attachTo: document.body,
    global: { plugins: [i18n, PrimeVue] },
    props: {
      visible: false,
      active: { scope: { scopeId: 's' }, contentKey: new Uint8Array() } as never,
      nodeId: 'n1',
    },
  })
  await wrapper.setProps({ visible: true }) // the watch loads shares when the dialog opens
  await flushPromises()
  return wrapper
}

describe('UploaderShareDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'de'
    vi.mocked(listShares).mockResolvedValue([
      { shareId: 's-up', nodeId: 'n1', role: 'UPLOADER', passwordProtected: false, label: 'Gäste', createdAt: '2026-01-01' },
      { shareId: 's-view', nodeId: 'n1', role: 'VIEWER', passwordProtected: true, label: 'Familie', createdAt: '2026-01-01' },
    ] as never)
    vi.mocked(createShare).mockResolvedValue({ shareId: 's-new', link: 'https://x/s/s-new#key' } as never)
    vi.mocked(revokeShare).mockResolvedValue(undefined as never)
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    document.body.innerHTML = ''
  })

  it('lists only UPLOADER shares', async () => {
    await mountDialog()
    const rows = document.querySelectorAll('.share-row')
    expect(rows).toHaveLength(1)
    expect(rows[0].textContent).toContain('Gäste')
    expect(document.body.textContent).not.toContain('Familie')
  })

  it('creates an upload-only link with the UPLOADER role', async () => {
    await mountDialog()
    const form = document.querySelector('form') as HTMLFormElement
    form.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }))
    await flushPromises()

    expect(createShare).toHaveBeenCalledWith(
      expect.anything(), expect.anything(), expect.anything(), 'n1',
      expect.objectContaining({ role: 'UPLOADER' }),
    )
    expect(document.querySelector('.share-link')?.textContent).toContain('https://x/s/s-new#key')
  })
})
