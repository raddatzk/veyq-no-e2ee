import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PrimeVue from 'primevue/config'
import CoverPickerDialog from './CoverPickerDialog.vue'
import { i18n } from '../../../i18n'
import { loadGalleryTiles } from '../../gallery/gallery'
import { setCover } from '../../memory/curate/curate'

vi.mock('../../gallery/gallery', () => ({ loadGalleryTiles: vi.fn() }))
vi.mock('../../memory/curate/curate', () => ({ setCover: vi.fn() }))
vi.mock('../../../app/session', () => ({
  session: { apiBase: '' },
  requireSession: () => ({ sessionToken: 't' }),
}))

const tile = (id: string) => ({
  id, url: `blob:${id}`, aspectRatio: 1, mimeType: 'image/jpeg', takenAt: '2026-01-10T00:00:00Z',
  highlighted: false, preview: {}, original: {},
})

let wrapper: VueWrapper | null = null

async function mountDialog() {
  wrapper = mount(CoverPickerDialog, {
    attachTo: document.body,
    global: { plugins: [i18n, PrimeVue] },
    props: {
      visible: false,
      active: { scope: { scopeId: 's' }, contentKey: new Uint8Array() } as never,
      nodeId: 'n1',
      coverMediaId: 'a',
    },
  })
  await wrapper.setProps({ visible: true }) // the watch loads tiles when the dialog opens
  await flushPromises()
  return wrapper
}

describe('CoverPickerDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'de'
    vi.mocked(loadGalleryTiles).mockResolvedValue([tile('a'), tile('b')] as never)
    vi.mocked(setCover).mockResolvedValue({} as never)
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    document.body.innerHTML = ''
  })

  it('loads tiles and marks the current cover', async () => {
    await mountDialog()
    expect(loadGalleryTiles).toHaveBeenCalled()
    const current = document.querySelector('.cover-grid-tile-current')
    expect(current).not.toBeNull()
    expect(current?.querySelector('.cover-grid-current-mark')).not.toBeNull()
  })

  it('pins the chosen tile and emits saved + closes', async () => {
    const w = await mountDialog()
    const tiles = document.querySelectorAll<HTMLButtonElement>('.cover-grid-tile')
    expect(tiles).toHaveLength(2)
    tiles[1].click() // choose tile "b"
    await flushPromises()

    expect(setCover).toHaveBeenCalledWith(expect.anything(), expect.anything(), expect.anything(), 'n1', 'b')
    expect(w.emitted('saved')?.[0]).toEqual(['b'])
    expect(w.emitted('update:visible')?.at(-1)).toEqual([false])
  })
})
