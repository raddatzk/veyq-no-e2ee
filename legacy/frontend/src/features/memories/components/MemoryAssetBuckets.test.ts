import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  SELF_PRIMARY_BUCKET_PLACEHOLDER_ID,
  type MemoryMedia,
  type MemoryMediaBucket
} from '../api'
import MemoryAssetBuckets from './MemoryAssetBuckets.vue'

class ResizeObserverStub {
  constructor(private readonly callback: ResizeObserverCallback) {}

  observe(target: Element) {
    this.callback(
      [{ contentRect: { width: 640 } } as ResizeObserverEntry],
      this as unknown as ResizeObserver
    )
    Object.defineProperty(target, 'clientWidth', { value: 640, configurable: true })
  }

  disconnect() {}
}

describe('MemoryAssetBuckets', () => {
  const originalResizeObserver = globalThis.ResizeObserver

  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver
  })

  afterEach(() => {
    globalThis.ResizeObserver = originalResizeObserver
  })

  it('renders bucket hierarchy and filters the active bucket by camera', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        buckets: [
          bucket({ id: 'bucket-kevin', displayName: 'Kevin', mediaCount: 2 }),
          bucket({
            id: 'bucket-trip',
            parentBucketId: 'bucket-kevin',
            displayName: 'Kamera',
            mediaCount: 1,
            primary: false
          })
        ],
        media: [
          media({
            id: 'media-1',
            bucketId: 'bucket-kevin',
            cameraMake: 'Canon',
            cameraModel: 'R6'
          }),
          media({ id: 'media-2', bucketId: 'bucket-kevin', cameraMake: 'Sony', cameraModel: 'A7' }),
          media({ id: 'media-3', bucketId: 'bucket-trip', cameraMake: 'Canon', cameraModel: 'R6' })
        ]
      },
      global: { stubs: dialogStubs() }
    })

    await flushPromises()

    expect(wrapper.findAll('.asset-bucket-select option').map((option) => option.text())).toEqual([
      'Kevin (2)',
      '↳ Kamera (1)'
    ])
    expect(
      wrapper
        .findAll('.asset-bucket-select option')
        .map((option) => option.attributes('data-depth'))
    ).toEqual(['0', '1'])

    const expectedDate = formatDate(media().capturedAt)
    expect(wrapper.findAll('.asset-bucket-media-caption').map((item) => item.text())).toEqual([
      `First upload${expectedDate}`,
      `First upload${expectedDate}`
    ])

    const canonChip = wrapper
      .findAll('.asset-camera-chip')
      .find((chip) => chip.text().includes('Canon R6'))
    await canonChip!.trigger('click')

    expect(wrapper.findAll('.media-grid-tile')).toHaveLength(1)
  })

  it('deduplicates repeated media ids inside the active bucket', async () => {
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

    await flushPromises()

    expect(wrapper.findAll('.media-grid-tile')).toHaveLength(1)
    expect(wrapper.find('.asset-camera-chip span').text()).toBe('1')
  })

  it('shows only media assigned directly to the current memory in buckets', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        buckets: [bucket({ id: 'bucket-kevin', displayName: 'Kevin', mediaCount: 2 })],
        media: [
          media({
            id: 'media-parent',
            memoryId: 'mem-1',
            bucketId: 'bucket-kevin',
            title: 'Parent upload'
          }),
          media({
            id: 'media-child',
            memoryId: 'child-mem-1',
            bucketId: 'bucket-kevin',
            title: 'Child upload'
          })
        ]
      },
      global: { stubs: dialogStubs() }
    })

    await flushPromises()

    expect(wrapper.findAll('.media-grid-tile')).toHaveLength(1)
    expect(wrapper.find('.asset-bucket-media-caption').text()).toContain('Parent upload')
    expect(wrapper.text()).not.toContain('Child upload')
  })

  it('selects one visible media item and submits only that item with offset seconds', async () => {
    const updateMetadataBatch = vi
      .fn()
      .mockResolvedValue([media({ id: 'media-1', capturedAt: '2026-05-03T13:00:00Z' })])
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

    const canonChip = wrapper
      .findAll('.asset-camera-chip')
      .find((chip) => chip.text().includes('Canon R6'))
    await canonChip!.trigger('click')
    await flushPromises()
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
    expect(
      wrapper
        .findAll<HTMLInputElement>('.asset-media-checkbox input')
        .map((input) => input.element.checked)
    ).toEqual([true, true])

    const canonChip = wrapper
      .findAll('.asset-camera-chip')
      .find((chip) => chip.text().includes('Canon R6'))
    await canonChip!.trigger('click')
    expect(
      wrapper
        .findAll<HTMLInputElement>('.asset-media-checkbox input')
        .map((input) => input.element.checked)
    ).toEqual([true])

    await buttonByText(wrapper, 'Auswahl aufheben').trigger('click')
    expect(
      wrapper
        .findAll<HTMLInputElement>('.asset-media-checkbox input')
        .map((input) => input.element.checked)
    ).toEqual([false])
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

    await flushPromises()

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

    await flushPromises()

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

    await flushPromises()

    await wrapper.find('.asset-card-options-button').trigger('click')
    await buttonByText(wrapper, 'Aufnahmezeit verschieben').trigger('click')
    await wrapper.find('#asset-corrected-time').setValue('2026-05-12T16:30')

    expect(wrapper.text()).toContain(
      formatOffset(offsetSeconds('2026-05-12T16:30', media().capturedAt))
    )
  })

  it('moves a single media item from the asset card actions', async () => {
    const moveMedia = vi
      .fn()
      .mockResolvedValue([media({ id: 'media-2', bucketId: 'bucket-target' })])
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

    await flushPromises()

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

    await flushPromises()

    await wrapper.findAll('.asset-card-options-button')[1].trigger('click')
    await buttonByText(wrapper, 'Medium löschen').trigger('click')
    expect(wrapper.text()).toContain(
      'Diese Aktion löscht 1 ausgewählte Medien dauerhaft aus diesem Bucket.'
    )
    expect(wrapper.text()).toContain('Anzahl zur Bestätigung eingeben: 1')
    await wrapper.find('.asset-dialog-form').trigger('submit.prevent')

    expect(confirm).not.toHaveBeenCalled()
    expect(deleteMediaBatch).not.toHaveBeenCalled()

    await wrapper.find('#asset-delete-media-count').setValue('1')
    await wrapper.find('.asset-dialog-form').trigger('submit.prevent')

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
    expect(wrapper.text()).toContain(
      'Diese Aktion löscht 2 ausgewählte Medien dauerhaft aus diesem Bucket.'
    )
    expect(wrapper.text()).toContain('Anzahl zur Bestätigung eingeben: 2')
    await wrapper.find('.asset-dialog-form').trigger('submit.prevent')

    expect(deleteMediaBatch).not.toHaveBeenCalled()

    await wrapper.find('#asset-delete-media-count').setValue('2')
    await wrapper.find('.asset-dialog-form').trigger('submit.prevent')

    expect(deleteMediaBatch).toHaveBeenCalledWith('mem-1', ['media-1', 'media-2'])
  })

  it('emits the deleted bucket after a confirmed bucket delete succeeds', async () => {
    const deleteBucket = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [media({ id: 'media-1', bucketId: 'bucket-source' })],
        buckets: [bucket({ id: 'bucket-source', displayName: 'Source', mediaCount: 1 })],
        canDeleteMedia: true,
        deleteBucket
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.find('.asset-bucket-options-button').trigger('click')
    await buttonByText(wrapper, 'Löschen').trigger('click')
    await wrapper.find('#asset-delete-bucket-name').setValue('Source')
    await wrapper.find('.asset-dialog-form').trigger('submit.prevent')

    expect(deleteBucket).toHaveBeenCalledWith('mem-1', 'bucket-source')
    expect(wrapper.emitted('deletedBucket')?.[0]).toEqual(['bucket-source'])
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

    expect(
      wrapper.find<HTMLButtonElement>('.asset-selection-options-button').element.disabled
    ).toBe(true)

    await flushPromises()
    await wrapper.find('.asset-media-checkbox input').setValue(true)
    await wrapper.find('.asset-selection-options-button').trigger('click')
    await buttonByText(wrapper, 'Aufnahmezeit verschieben').trigger('click')
    expect(wrapper.find<HTMLButtonElement>('.asset-batch-form button').element.disabled).toBe(true)

    await wrapper.find('#asset-corrected-time').setValue('2026-05-03T10:15')
    expect(wrapper.find<HTMLButtonElement>('.asset-batch-form button').element.disabled).toBe(false)
  })

  it('emits selected media files when dropping onto the active bucket', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [],
        buckets: [bucket({ id: 'bucket-source', displayName: 'Source' })],
        canUpload: true
      },
      global: { stubs: dialogStubs() }
    })
    const image = new File(['image'], 'photo.jpg', { type: 'image/jpeg' })
    const text = new File(['note'], 'note.txt', { type: 'text/plain' })

    await wrapper.find('.asset-bucket-drop-target').trigger('drop', {
      dataTransfer: dataTransfer([image, text])
    })

    expect(wrapper.emitted('dropBucket')?.[0]).toEqual([[image], 'bucket-source', 'mem-1'])
  })

  it('renders a local current user primary bucket when the server list is empty', () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [],
        buckets: [],
        localPrimaryUploader: uploader(),
        canUpload: true
      },
      global: { stubs: dialogStubs() }
    })

    expect(wrapper.find('.asset-bucket-select option').text()).toBe('Kevin (0)')
  })

  it('does not render a local current user primary bucket when disabled', () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: '',
        memoryId: 'mem-1',
        media: [],
        buckets: [],
        localPrimaryUploader: uploader(),
        showLocalPrimaryBucket: false,
        canUpload: true
      },
      global: { stubs: dialogStubs() }
    })

    expect(wrapper.find('.asset-bucket-select').exists()).toBe(false)
  })

  it('omits the bucket id when uploading or dropping onto the local primary bucket', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [],
        buckets: [],
        localPrimaryUploader: uploader(),
        canUpload: true
      },
      global: { stubs: dialogStubs() }
    })
    const image = new File(['image'], 'photo.jpg', { type: 'image/jpeg' })

    await wrapper.find('.asset-bucket-options-button').trigger('click')
    await buttonByText(wrapper, 'Hochladen').trigger('click')
    await wrapper.find('.asset-bucket-drop-target').trigger('drop', {
      dataTransfer: dataTransfer([image])
    })

    expect(wrapper.emitted('uploadBucket')?.[0]).toEqual([null, 'mem-1'])
    expect(wrapper.emitted('dropBucket')?.[0]).toEqual([[image], null, 'mem-1'])
  })

  it('submits the local primary placeholder as parent for new subbuckets', async () => {
    const createBucket = vi.fn().mockResolvedValue(
      bucket({
        id: 'bucket-child',
        parentBucketId: 'bucket-primary',
        displayName: 'Phone imports',
        primary: false
      })
    )
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [],
        buckets: [],
        localPrimaryUploader: uploader(),
        canUpload: true,
        createBucket
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.find('.asset-bucket-options-button').trigger('click')
    await buttonByText(wrapper, 'Unterbucket').trigger('click')
    await wrapper.find('#asset-bucket-name').setValue('Phone imports')
    await wrapper.find('.asset-dialog-form').trigger('submit.prevent')

    expect(createBucket).toHaveBeenCalledWith(
      'mem-1',
      SELF_PRIMARY_BUCKET_PLACEHOLDER_ID,
      'Phone imports'
    )
  })

  it('does not show rename or delete actions for the local primary bucket', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [],
        buckets: [],
        localPrimaryUploader: uploader(),
        canUpload: true,
        canEditMetadata: true,
        canDeleteMedia: true,
        updateBucket: vi.fn(),
        deleteBucket: vi.fn()
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.find('.asset-bucket-options-button').trigger('click')

    expect(buttonByText(wrapper, 'Bearbeiten').attributes('disabled')).toBeDefined()
    expect(buttonByText(wrapper, 'Löschen').attributes('disabled')).toBeDefined()
  })

  it('opens the bucket gallery lightbox with the camera-filtered media and selects the current media with s', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [
          media({ id: 'media-1', cameraMake: 'Canon', cameraModel: 'R6' }),
          media({ id: 'media-2', cameraMake: 'Sony', cameraModel: 'A7' })
        ],
        buckets: [bucket()]
      },
      global: { stubs: dialogStubs() }
    })

    const canonChip = wrapper
      .findAll('.asset-camera-chip')
      .find((chip) => chip.text().includes('Canon R6'))
    await canonChip!.trigger('click')
    await flushPromises()
    await wrapper.find('.media-grid-tile').trigger('click')
    await flushPromises()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 's' }))
    await wrapper.vm.$nextTick()

    expect(wrapper.find<HTMLInputElement>('.asset-media-checkbox input').element.checked).toBe(true)
    expect(wrapper.find('.asset-bucket-media-overlay-selected').exists()).toBe(true)
    expect(wrapper.find('.media-grid-tile img').exists()).toBe(true)
    expect(wrapper.findAll('.media-grid-tile')).toHaveLength(1)
    expect(wrapper.get('[data-testid="lightbox"]').text()).toBe('media-1')
  })

  it('hides highlight badges inside bucket media tiles', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [media({ id: 'media-highlight', highlight: true })],
        buckets: [bucket()]
      },
      global: { stubs: dialogStubs() }
    })

    await flushPromises()

    expect(wrapper.find('.media-grid-highlight-badge').exists()).toBe(false)
  })

  it('shows the active bucket dropzone only during a file drag', async () => {
    const wrapper = mount(MemoryAssetBuckets, {
      props: {
        tenantName: 'tenant',
        memoryId: 'mem-1',
        media: [],
        buckets: [bucket({ id: 'bucket-source', displayName: 'Source' })],
        canUpload: true
      },
      global: { stubs: dialogStubs() }
    })

    expect(wrapper.find('.asset-bucket-dropzone').exists()).toBe(false)

    await wrapper.find('.asset-bucket-drop-target').trigger('dragenter', {
      dataTransfer: dataTransfer([])
    })

    expect(wrapper.find('.asset-bucket-dropzone').exists()).toBe(true)

    await wrapper.find('.asset-bucket-drop-target').trigger('dragleave')

    expect(wrapper.find('.asset-bucket-dropzone').exists()).toBe(false)
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
    },
    Select: {
      props: ['modelValue', 'options', 'optionLabel', 'optionValue'],
      emits: ['update:modelValue'],
      methods: {
        updateValue(this: { $emit: (event: string, value: string) => void }, event: Event) {
          this.$emit('update:modelValue', (event.target as HTMLSelectElement).value)
        }
      },
      template: `
        <select class="asset-bucket-select" :value="modelValue" @change="updateValue">
          <option
            v-for="option in options"
            :key="option[optionValue]"
            :value="option[optionValue]"
            :data-depth="option.depth"
          >
            {{ option.depth > 0 ? '↳ ' : '' }}{{ option[optionLabel] }}
          </option>
        </select>
      `
    },
    SelectButton: true,
    ProgressSpinner: true,
    MediaLightbox: {
      props: ['media', 'mediaItems'],
      template: '<section data-testid="lightbox">{{ media.id }}</section>'
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
    updatedAt: '2026-05-24T16:30:00Z',
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
    highlight: false,
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
    canManage: true,
    mediaCount: 2,
    createdAt: '2026-05-24T16:30:00Z',
    updatedAt: '2026-05-24T16:30:00Z',
    ...overrides
  }
}

function uploader(): MemoryMediaBucket['uploader'] {
  return {
    type: 'USER',
    displayName: 'Kevin',
    permalink: 'kevin',
    userId: 1
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

function dataTransfer(files: File[]) {
  return {
    files,
    types: ['Files'],
    dropEffect: 'none'
  }
}
