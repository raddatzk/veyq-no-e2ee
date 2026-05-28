import { mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { MemoryMedia, MemoryMediaBucket } from '../api'
import MemoryAssetBuckets from './MemoryAssetBuckets.vue'

describe('MemoryAssetBuckets', () => {
  it('renders bucket hierarchy and filters the active bucket by camera', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        buckets: [
          bucket({ id: 'bucket-kevin', displayName: 'Kevin', mediaCount: 2 }),
          bucket({ id: 'bucket-trip', parentBucketId: 'bucket-kevin', displayName: 'Kamera', mediaCount: 1, primary: false })
        ],
        media: [
          media({ id: 'media-1', bucketId: 'bucket-kevin', cameraMake: 'Canon', cameraModel: 'R6' }),
          media({ id: 'media-2', bucketId: 'bucket-kevin', cameraMake: 'Sony', cameraModel: 'A7' }),
          media({ id: 'media-3', bucketId: 'bucket-trip', cameraMake: 'Canon', cameraModel: 'R6' })
        ]
      },
      global: { stubs: dialogStubs() }
    })

    expect(wrapper.findAll('.asset-bucket-button').map((button) => button.text())).toEqual([
      'Kevin2',
      'Kamera1'
    ])

    const expectedDate = formatDate(media().capturedAt)
    expect(wrapper.findAll('.asset-bucket-media-item').map((item) => item.text())).toEqual([
      `First upload${expectedDate}`,
      `First upload${expectedDate}`
    ])

    const canonChip = wrapper.findAll('.asset-camera-chip').find((chip) => chip.text().includes('Canon R6'))
    await canonChip!.trigger('click')

    expect(wrapper.findAll('.asset-bucket-media-item')).toHaveLength(1)
  })

  it('deduplicates repeated media ids inside the active bucket', () => {
    const duplicated = media({ id: 'media-duplicate', bucketId: 'bucket-kevin' })
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        buckets: [bucket({ id: 'bucket-kevin', displayName: 'Kevin' })],
        media: [duplicated, { ...duplicated }]
      },
      global: { stubs: dialogStubs() }
    })

    expect(wrapper.findAll('.asset-bucket-media-item')).toHaveLength(1)
    expect(wrapper.find('.asset-camera-chip span').text()).toBe('1')
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
        buckets: [bucket()],
        canEditMetadata: true,
        updateMetadataBatch
      },
      global: { stubs: dialogStubs() }
    })

    const canonChip = wrapper.findAll('.asset-camera-chip').find((chip) => chip.text().includes('Canon R6'))
    await canonChip!.trigger('click')
    await wrapper.find('.asset-media-checkbox input').setValue(true)
    await wrapper.find('.asset-selection-options-button').trigger('click')
    await buttonByText(wrapper, 'Aufnahmezeit verschieben').trigger('click')
    await wrapper.find('#asset-corrected-time').setValue('2026-05-03T13:30')
    await wrapper.find('form').trigger('submit.prevent')

    expect(updateMetadataBatch).toHaveBeenCalledWith(
      'mem-1',
      ['media-1'],
      offsetSeconds('2026-05-03T13:30', media().capturedAt)
    )
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
        buckets: [bucket()],
        canEditMetadata: true,
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
        buckets: [bucket()],
        canEditMetadata: true,
        updateMetadataBatch
      },
      global: { stubs: dialogStubs() }
    })

    await buttonByText(wrapper, 'Alle auswählen').trigger('click')
    await wrapper.find('.asset-selection-options-button').trigger('click')
    await buttonByText(wrapper, 'Aufnahmezeit verschieben').trigger('click')
    await wrapper.find('#asset-corrected-time').setValue('2026-05-03T11:30')
    await wrapper.find('form').trigger('submit.prevent')

    expect(updateMetadataBatch).toHaveBeenCalledWith(
      'mem-1',
      ['media-1', 'media-2'],
      offsetSeconds('2026-05-03T11:30', media().capturedAt)
    )
  })

  it('opens an asset card offset action for just that media item', async () => {
    const updateMetadataBatch = vi.fn().mockResolvedValue([])
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [
          media({ id: 'media-1', cameraMake: 'Canon', cameraModel: 'R6' }),
          media({ id: 'media-2', cameraMake: 'Sony', cameraModel: 'A7' })
        ],
        buckets: [bucket()],
        canEditMetadata: true,
        updateMetadataBatch
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.findAll('.asset-card-options-button')[1].trigger('click')
    await buttonByText(wrapper, 'Aufnahmezeit verschieben').trigger('click')
    await wrapper.find('#asset-corrected-time').setValue('2026-05-03T12:15')
    await wrapper.find('form').trigger('submit.prevent')

    expect(updateMetadataBatch).toHaveBeenCalledWith(
      'mem-1',
      ['media-2'],
      offsetSeconds('2026-05-03T12:15', media().capturedAt)
    )
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
        buckets: [bucket()],
        canEditMetadata: true,
        updateMetadataBatch
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.find('.asset-card-options-button').trigger('click')
    await buttonByText(wrapper, 'Aufnahmezeit verschieben').trigger('click')
    await wrapper.find('#asset-corrected-time').setValue('2026-05-03T10:30')
    await wrapper.find('form').trigger('submit.prevent')

    expect(updateMetadataBatch).toHaveBeenCalledWith(
      'mem-1',
      ['media-1'],
      offsetSeconds('2026-05-03T10:30', '2026-05-03T08:00:00Z')
    )
  })

  it('shows a readable offset preview', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [media({ id: 'media-1' })],
        buckets: [bucket()],
        canEditMetadata: true,
        updateMetadataBatch: vi.fn()
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.find('.asset-card-options-button').trigger('click')
    await buttonByText(wrapper, 'Aufnahmezeit verschieben').trigger('click')
    await wrapper.find('#asset-corrected-time').setValue('2026-05-12T16:30')

    expect(wrapper.text()).toContain(formatOffset(offsetSeconds('2026-05-12T16:30', media().capturedAt)))
  })

  it('moves a single media item from the asset card actions', async () => {
    const moveMedia = vi.fn().mockResolvedValue([media({ id: 'media-2', bucketId: 'bucket-target' })])
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [
          media({ id: 'media-1', bucketId: 'bucket-source' }),
          media({ id: 'media-2', bucketId: 'bucket-source' })
        ],
        buckets: [
          bucket({ id: 'bucket-source', displayName: 'Source' }),
          bucket({ id: 'bucket-target', displayName: 'Target' })
        ],
        canEditMetadata: true,
        moveMedia
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.findAll('.asset-card-options-button')[1].trigger('click')
    await buttonByText(wrapper, 'Medium verschieben').trigger('click')
    await wrapper.find('#asset-move-target').setValue('bucket-target')
    await wrapper.find('.asset-dialog-form').trigger('submit.prevent')

    expect(moveMedia).toHaveBeenCalledWith('mem-1', ['media-2'], 'bucket-target')
    expect(wrapper.emitted('updated')?.[0]).toEqual([
      [expect.objectContaining({ id: 'media-2', bucketId: 'bucket-target' })]
    ])
  })

  it('deletes a single media item from the asset card actions', async () => {
    const deleteMediaBatch = vi.fn().mockResolvedValue(undefined)
    const confirm = vi.spyOn(window, 'confirm')
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [
          media({ id: 'media-1', bucketId: 'bucket-source' }),
          media({ id: 'media-2', bucketId: 'bucket-source' })
        ],
        buckets: [bucket({ id: 'bucket-source' })],
        canDeleteMedia: true,
        deleteMediaBatch
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.findAll('.asset-card-options-button')[1].trigger('click')
    await buttonByText(wrapper, 'Medium löschen').trigger('click')
    expect(wrapper.text()).toContain('Diese Aktion löscht 1 ausgewählte Medien dauerhaft aus diesem Bucket.')
    await wrapper.find('.asset-dialog-form').trigger('submit.prevent')

    expect(confirm).not.toHaveBeenCalled()
    expect(deleteMediaBatch).toHaveBeenCalledWith('mem-1', ['media-2'])
    confirm.mockRestore()
  })

  it('opens a styled delete dialog for selected media', async () => {
    const deleteMediaBatch = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [
          media({ id: 'media-1', bucketId: 'bucket-source' }),
          media({ id: 'media-2', bucketId: 'bucket-source' })
        ],
        buckets: [bucket({ id: 'bucket-source' })],
        canDeleteMedia: true,
        deleteMediaBatch
      },
      global: { stubs: dialogStubs() }
    })

    await buttonByText(wrapper, 'Alle auswählen').trigger('click')
    await wrapper.find('.asset-selection-options-button').trigger('click')
    await buttonByText(wrapper, 'Auswahl löschen').trigger('click')
    expect(wrapper.text()).toContain('Diese Aktion löscht 2 ausgewählte Medien dauerhaft aus diesem Bucket.')
    await wrapper.find('.asset-dialog-form').trigger('submit.prevent')

    expect(deleteMediaBatch).toHaveBeenCalledWith('mem-1', ['media-1', 'media-2'])
  })

  it('keeps batch actions disabled without a selection or without a changed reference time', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [media({ id: 'media-1' })],
        buckets: [bucket()],
        canEditMetadata: true,
        updateMetadataBatch: vi.fn()
      },
      global: { stubs: dialogStubs() }
    })

    expect(wrapper.find<HTMLButtonElement>('.asset-selection-options-button').element.disabled).toBe(true)

    await wrapper.find('.asset-media-checkbox input').setValue(true)
    await wrapper.find('.asset-selection-options-button').trigger('click')
    await buttonByText(wrapper, 'Aufnahmezeit verschieben').trigger('click')
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
    },
    Menu: {
      props: ['model'],
      data: () => ({ visible: false }),
      methods: {
        toggle(this: { visible: boolean }) {
          this.visible = !this.visible
        }
      },
      template: `
        <section v-if="visible">
          <button
            v-for="item in model"
            :key="item.label"
            type="button"
            :disabled="item.disabled"
            @click="item.command && item.command()"
          >
            {{ item.label }}
          </button>
        </section>
      `
    }
  }
}

function media(overrides: Partial<MemoryMedia> = {}): MemoryMedia {
  return {
    id: 'media-1',
    memoryId: 'mem-1',
    bucketId: 'bucket-1',
    title: 'First upload',
    uploader: { type: 'USER', displayName: 'Kevin', userId: 1 },
    createdAt: '2026-05-24T16:30:00Z',
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 12,
    contentUrl: '/api/tenant/media/media-1/content',
    thumbnailUrl: '/api/tenant/media/media-1/thumbnail',
    previewUrl: '/api/tenant/media/media-1/preview',
    posterUrl: null,
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

function bucket(overrides: Partial<MemoryMediaBucket> = {}): MemoryMediaBucket {
  return {
    id: 'bucket-1',
    memoryId: 'mem-1',
    displayName: 'Kevin',
    primary: true,
    uploader: { type: 'USER', displayName: 'Kevin', userId: 1 },
    mediaCount: 2,
    createdAt: '2026-05-24T16:30:00Z',
    updatedAt: '2026-05-24T16:30:00Z',
    ...overrides
  }
}

function formatDate(value: string | null) {
  if (!value) return 'Keine Aufnahmezeit'
  return new Intl.DateTimeFormat('de-DE', {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(new Date(value))
}

function offsetSeconds(localDateTime: string, referenceIso: string | null) {
  if (!referenceIso) return 0
  return Math.trunc((new Date(localDateTime).getTime() - new Date(referenceIso).getTime()) / 1000)
}

function formatOffset(seconds: number) {
  const sign = seconds < 0 ? '-' : '+'
  let remaining = Math.abs(seconds)
  const days = Math.floor(remaining / 86_400)
  remaining -= days * 86_400
  const hours = Math.floor(remaining / 3_600)
  remaining -= hours * 3_600
  const minutes = Math.floor(remaining / 60)
  const parts = [
    days ? `${days}T` : '',
    hours ? `${hours}h` : '',
    minutes ? `${minutes}m` : ''
  ].filter(Boolean)
  return parts.length ? `${sign}${parts.join(' ')}` : '+0m'
}
