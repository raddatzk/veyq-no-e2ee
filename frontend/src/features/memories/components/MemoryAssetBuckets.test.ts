import { mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { MemoryMedia } from '../api'
import MemoryAssetBuckets from './MemoryAssetBuckets.vue'

describe('MemoryAssetBuckets', () => {
  it('groups media by uploader and filters the active bucket by camera', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [
          media({ id: 'media-1', uploader: { type: 'USER', displayName: 'Kevin', userId: 1 }, cameraMake: 'Canon', cameraModel: 'R6' }),
          media({ id: 'media-2', uploader: { type: 'USER', displayName: 'Kevin', userId: 1 }, cameraMake: 'Sony', cameraModel: 'A7' }),
          media({ id: 'media-3', uploader: { type: 'USER', displayName: 'Anna', userId: 2 }, cameraMake: 'Canon', cameraModel: 'R6' })
        ]
      },
      global: { stubs: dialogStubs() }
    })

    expect(wrapper.findAll('.asset-bucket-button').map((button) => button.text())).toEqual([
      'Anna1',
      'Kevin2'
    ])

    await wrapper.findAll('.asset-bucket-button')[1].trigger('click')
    expect(wrapper.findAll('.asset-bucket-media-item').map((item) => item.text())).toEqual([
      'First upload03.05.2026, 12:00',
      'First upload03.05.2026, 12:00'
    ])

    const canonChip = wrapper.findAll('.asset-camera-chip').find((chip) => chip.text().includes('Canon R6'))
    await canonChip!.trigger('click')

    expect(wrapper.findAll('.asset-bucket-media-item')).toHaveLength(1)
  })

  it('selects one visible media item and submits only that item with offset seconds', async () => {
    const updateMetadataBatch = vi.fn().mockResolvedValue([
      media({ id: 'media-1', capturedAt: '2026-05-03T13:00:00Z' })
    ])
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [
          media({ id: 'media-1', cameraMake: 'Canon', cameraModel: 'R6' }),
          media({ id: 'media-2', cameraMake: 'Sony', cameraModel: 'A7' })
        ],
        updateMetadataBatch
      },
      global: { stubs: dialogStubs() }
    })

    const canonChip = wrapper.findAll('.asset-camera-chip').find((chip) => chip.text().includes('Canon R6'))
    await canonChip!.trigger('click')
    await wrapper.find('.asset-media-checkbox input').setValue(true)
    await wrapper.find('.asset-selection-options-button').trigger('click')
    await wrapper.find('#asset-corrected-time').setValue('2026-05-03T13:30')
    await wrapper.find('form').trigger('submit.prevent')

    expect(updateMetadataBatch).toHaveBeenCalledWith('mem-1', ['media-1'], 5400)
    expect(wrapper.emitted('updated')?.[0]).toEqual([
      [expect.objectContaining({ id: 'media-1', capturedAt: '2026-05-03T13:00:00Z' })]
    ])
  })

  it('selects all visible media and clears only visible selection', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [
          media({ id: 'media-1', cameraMake: 'Canon', cameraModel: 'R6' }),
          media({ id: 'media-2', cameraMake: 'Sony', cameraModel: 'A7' })
        ],
        updateMetadataBatch: vi.fn()
      },
      global: { stubs: dialogStubs() }
    })

    await buttonByText(wrapper, 'Alle auswählen').trigger('click')
    expect(wrapper.findAll<HTMLInputElement>('.asset-media-checkbox input').map((input) => input.element.checked)).toEqual([
      true,
      true
    ])

    const canonChip = wrapper.findAll('.asset-camera-chip').find((chip) => chip.text().includes('Canon R6'))
    await canonChip!.trigger('click')
    expect(wrapper.findAll<HTMLInputElement>('.asset-media-checkbox input').map((input) => input.element.checked)).toEqual([
      true
    ])

    await buttonByText(wrapper, 'Auswahl aufheben').trigger('click')
    expect(wrapper.findAll<HTMLInputElement>('.asset-media-checkbox input').map((input) => input.element.checked)).toEqual([
      false
    ])
  })

  it('submits all selected media in the uploader bucket when no camera filter is active', async () => {
    const updateMetadataBatch = vi.fn().mockResolvedValue([])
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [
          media({ id: 'media-1', cameraMake: 'Canon', cameraModel: 'R6' }),
          media({ id: 'media-2', cameraMake: 'Sony', cameraModel: 'A7' })
        ],
        updateMetadataBatch
      },
      global: { stubs: dialogStubs() }
    })

    await buttonByText(wrapper, 'Alle auswählen').trigger('click')
    await wrapper.find('.asset-selection-options-button').trigger('click')
    await wrapper.find('#asset-corrected-time').setValue('2026-05-03T11:30')
    await wrapper.find('form').trigger('submit.prevent')

    expect(updateMetadataBatch).toHaveBeenCalledWith('mem-1', ['media-1', 'media-2'], -1800)
  })

  it('opens an asset card options dialog for just that media item', async () => {
    const updateMetadataBatch = vi.fn().mockResolvedValue([])
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [
          media({ id: 'media-1', cameraMake: 'Canon', cameraModel: 'R6' }),
          media({ id: 'media-2', cameraMake: 'Sony', cameraModel: 'A7' })
        ],
        updateMetadataBatch
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.findAll('.asset-card-options-button')[1].trigger('click')
    await wrapper.find('#asset-corrected-time').setValue('2026-05-03T12:15')
    await wrapper.find('form').trigger('submit.prevent')

    expect(updateMetadataBatch).toHaveBeenCalledWith('mem-1', ['media-2'], 900)
  })

  it('uses created time as the reference fallback when captured time is missing', async () => {
    const updateMetadataBatch = vi.fn().mockResolvedValue([])
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [
          media({
            id: 'media-1',
            capturedAt: null,
            createdAt: '2026-05-03T08:00:00Z'
          })
        ],
        updateMetadataBatch
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.find('.asset-card-options-button').trigger('click')
    await wrapper.find('#asset-corrected-time').setValue('2026-05-03T10:30')
    await wrapper.find('form').trigger('submit.prevent')

    expect(updateMetadataBatch).toHaveBeenCalledWith('mem-1', ['media-1'], 1800)
  })

  it('shows a readable offset preview', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [media({ id: 'media-1' })],
        updateMetadataBatch: vi.fn()
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.find('.asset-card-options-button').trigger('click')
    await wrapper.find('#asset-corrected-time').setValue('2026-05-12T16:30')

    expect(wrapper.text()).toContain('+1W 2T 4h 30m')
  })

  it('keeps batch actions disabled without a selection or without a changed reference time', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [media({ id: 'media-1' })],
        updateMetadataBatch: vi.fn()
      },
      global: { stubs: dialogStubs() }
    })

    expect(wrapper.find<HTMLButtonElement>('.asset-selection-options-button').element.disabled).toBe(true)

    await wrapper.find('.asset-media-checkbox input').setValue(true)
    await wrapper.find('.asset-selection-options-button').trigger('click')
    expect(wrapper.find<HTMLButtonElement>('.asset-batch-form button').element.disabled).toBe(true)

    await wrapper.find('#asset-corrected-time').setValue('2026-05-03T10:15')
    expect(wrapper.find<HTMLButtonElement>('.asset-batch-form button').element.disabled).toBe(false)
  })
})

function buttonByText(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text() === text)
  if (!button) throw new Error(`Button not found: ${text}`)
  return button
}

function dialogStubs() {
  return {
    Dialog: {
      props: ['visible'],
      template: '<section v-if="visible"><slot /></section>'
    }
  }
}

function media(overrides: Partial<MemoryMedia> = {}): MemoryMedia {
  return {
    id: 'media-1',
    memoryId: 'mem-1',
    title: 'First upload',
    uploader: { type: 'USER', displayName: 'Kevin', userId: 1 },
    createdAt: '2026-05-24T16:30:00Z',
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 12,
    contentUrl: '/api/tenant/media/media-1/content',
    previewUrl: '/api/tenant/media/media-1/preview',
    capturedAt: '2026-05-03T10:00:00Z',
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    imageWidth: null,
    imageHeight: null,
    gpsLatitude: null,
    gpsLongitude: null,
    ...overrides
  }
}
