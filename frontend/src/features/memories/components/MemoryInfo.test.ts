import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PrimeVue from 'primevue/config'
import MemoryInfo from './MemoryInfo.vue'
import { i18n } from '../../../i18n'
import { loadGalleryTiles } from '../../gallery/gallery'
import { getTracks } from '../../memory/track/track'
import { listBuckets } from '../../memory/buckets/buckets'

vi.mock('../../gallery/gallery', () => ({ loadGalleryTiles: vi.fn() }))
vi.mock('../../memory/track/track', () => ({ getTracks: vi.fn() }))
vi.mock('../../memory/buckets/buckets', () => ({ listBuckets: vi.fn() }))
vi.mock('../../../app/session', () => ({
  session: { apiBase: '' },
  requireSession: () => ({ sessionToken: 't', user: { id: 'u1', displayName: 'Me' } }),
}))

const tile = (over: Record<string, unknown>) => ({
  id: 'm', url: 'blob:x', aspectRatio: 1, mimeType: 'image/jpeg', takenAt: '2026-01-10T00:00:00Z',
  highlighted: false, preview: {}, original: {}, ...over,
})

function mountInfo(over: Record<string, unknown> = {}) {
  return mount(MemoryInfo, {
    global: { plugins: [i18n, PrimeVue] },
    props: {
      active: { scope: { scopeId: 's' }, contentKey: new Uint8Array(), entry: {} } as never,
      nodeId: 'n1',
      node: { id: 'n1', type: 'MEMORY', createdAt: '2026-01-15T12:00:00Z', coverMediaId: 'c1' } as never,
      childrenCount: 3,
      playlistCount: 1,
      ...over,
    },
  })
}

describe('MemoryInfo', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'de'
    vi.mocked(loadGalleryTiles).mockResolvedValue([
      tile({ id: 'a', mimeType: 'image/jpeg', fileSize: 1_048_576, lat: 1, lng: 2, cameraMake: 'Canon', cameraModel: 'EOS', takenAt: '2026-01-10T00:00:00Z' }),
      tile({ id: 'b', mimeType: 'image/jpeg', fileSize: 1_048_576, cameraMake: 'Canon', cameraModel: 'EOS', takenAt: '2026-01-12T00:00:00Z' }),
      tile({ id: 'c', mimeType: 'video/mp4', fileSize: 1_048_576, takenAt: '2026-01-14T00:00:00Z' }),
    ] as never)
    vi.mocked(getTracks).mockResolvedValue([
      { id: 't1', name: 'Walk', points: [{ lat: 0, lng: 0 }, { lat: 0.01, lng: 0.01 }] },
    ] as never)
    vi.mocked(listBuckets).mockResolvedValue([
      { id: 'primary:user:u1', ownerKey: 'user:u1', primary: true, mediaCount: 2 },
      { id: 'primary:user:u2', ownerKey: 'user:u2', primary: true, mediaCount: 1 },
    ] as never)
  })

  it('renders the derived statistics digest', async () => {
    const wrapper = mountInfo()
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('Memory') // type
    expect(text).toContain('2 Fotos')
    expect(text).toContain('1 Videos')
    expect(text).toContain('3.0 MB') // storage (3 × 1 MiB)
    expect(text).toContain('Canon EOS') // distinct camera
    expect(text).toContain('1 von 3') // with-location count
    expect(text).toContain('Festgelegt') // cover is set
  })

  it('shows contributor / chapter / track / playlist counts', async () => {
    const wrapper = mountInfo()
    await flushPromises()
    const rows = wrapper.findAll('.memory-info-list dd').map((dd) => dd.text())

    expect(rows).toContain('2') // contributors (distinct ownerKeys)
    expect(rows).toContain('3') // chapters
    expect(rows).toContain('1') // tracks
  })

  it('hides optional rows when there is nothing to show', async () => {
    vi.mocked(getTracks).mockResolvedValue([] as never)
    vi.mocked(listBuckets).mockResolvedValue([] as never)
    const wrapper = mountInfo({ childrenCount: 0, playlistCount: 0 })
    await flushPromises()
    const text = wrapper.text()

    expect(text).not.toContain('Tracks')
    expect(text).not.toContain('Kapitel')
    expect(text).not.toContain('Beitragende')
  })
})
