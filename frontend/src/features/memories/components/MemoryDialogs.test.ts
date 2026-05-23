import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemory, deleteMemory, removeMemoryCover, setMemoryCover, updateMemory, type Memory, type MemoryMedia } from '../api'
import CreateMemoryDialog from './CreateMemoryDialog.vue'
import DeleteMemoryDialog from './DeleteMemoryDialog.vue'
import EditMemoryDialog from './EditMemoryDialog.vue'
import MemoryCoverDialog from './MemoryCoverDialog.vue'

vi.mock('../api', () => ({
  createMemory: vi.fn(),
  deleteMemory: vi.fn(),
  removeMemoryCover: vi.fn(),
  setMemoryCover: vi.fn(),
  updateMemory: vi.fn()
}))

const createMemoryMock = vi.mocked(createMemory)
const deleteMemoryMock = vi.mocked(deleteMemory)
const removeMemoryCoverMock = vi.mocked(removeMemoryCover)
const setMemoryCoverMock = vi.mocked(setMemoryCover)
const updateMemoryMock = vi.mocked(updateMemory)

describe('Memory detail dialogs', () => {
  beforeEach(() => {
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:cover-source'),
      revokeObjectURL: vi.fn()
    })
    Object.defineProperty(HTMLImageElement.prototype, 'naturalWidth', {
      configurable: true,
      get: () => 800
    })
    Object.defineProperty(HTMLImageElement.prototype, 'naturalHeight', {
      configurable: true,
      get: () => 600
    })
  })

  afterEach(() => {
    vi.resetAllMocks()
    vi.unstubAllGlobals()
  })

  it('updates memory display metadata and emits the updated memory', async () => {
    const updated = memory({
      displayName: 'Updated Trip',
      name: 'updated-trip',
      path: '/updated-trip'
    })
    updateMemoryMock.mockResolvedValue(updated)
    const saved = vi.fn()

    const wrapper = mount(EditMemoryDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory(),
        onSaved: saved
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.findAll('input')[0].setValue('Updated Trip')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(updateMemoryMock).toHaveBeenCalledWith('tenant', 'mem-1', {
      displayName: 'Updated Trip',
      name: 'updated-trip',
      startDate: '2026-05-03',
      endDate: '2026-05-05'
    })
    expect(saved).toHaveBeenCalledWith(updated)
  })

  it('creates a memory with required date range and optional cropped cover', async () => {
    const file = new File(['cover'], 'cover.jpg', { type: 'image/jpeg' })
    const created = memory({ id: 'mem-created', name: 'summer-trip', displayName: 'Summer Trip' })
    createMemoryMock.mockResolvedValue(created)
    setMemoryCoverMock.mockResolvedValue(created)
    const saved = vi.fn()

    const wrapper = mount(CreateMemoryDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        tenantDisplayName: 'Tenant',
        parentMemoryId: 'mem-parent',
        onSaved: saved
      },
      global: { stubs: dialogStubs() }
    })

    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('Summer Trip')
    await inputs[2].setValue('2026-05-03')
    await inputs[3].setValue('2026-05-05')
    wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [file] })
    await flushPromises()
    await wrapper.find('.memory-cover-cropper img').trigger('load')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createMemoryMock).toHaveBeenCalledWith('tenant', {
      displayName: 'Summer Trip',
      name: 'summer-trip',
      startDate: '2026-05-03',
      endDate: '2026-05-05',
      parentId: 'mem-parent',
      folderPath: null,
      scope: null
    })
    const coverRequest = setMemoryCoverMock.mock.calls[0][2]
    expect(setMemoryCoverMock).toHaveBeenCalledWith('tenant', 'mem-created', coverRequest)
    expect((coverRequest.file as File | undefined)?.name).toBe('cover.jpg')
    expect(coverRequest.file?.type).toBe('image/jpeg')
    expect(coverRequest.cropWidth).toBeGreaterThan(0)
    expect(coverRequest.cropHeight).toBeGreaterThan(0)
    expect(saved).toHaveBeenCalled()
  })

  it('deletes a memory after explicit confirmation', async () => {
    deleteMemoryMock.mockResolvedValue()

    const wrapper = mount(DeleteMemoryDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory()
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Memory löschen'))!
      .trigger('click')
    await flushPromises()

    expect(deleteMemoryMock).toHaveBeenCalledWith('tenant', 'mem-1')
    expect(wrapper.emitted('deleted')).toHaveLength(1)
  })

  it('uploads a standalone cover image', async () => {
    const file = new File(['cover'], 'cover.jpg', { type: 'image/jpeg' })
    const updated = memory({ coverUrl: '/api/tenant/memories/mem-1/cover/content' })
    setMemoryCoverMock.mockResolvedValue(updated)
    const saved = vi.fn()

    const wrapper = mount(MemoryCoverDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory({ coverMediaId: 'media-1' }),
        media: [],
        onSaved: saved
      },
      global: { stubs: dialogStubs() }
    })

    wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [file] })
    await flushPromises()
    await wrapper.find('.memory-cover-cropper img').trigger('load')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Cover speichern'))!
      .trigger('click')
    await flushPromises()

    const request = setMemoryCoverMock.mock.calls[0][2]
    expect((request.file as File | undefined)?.name).toBe('cover.jpg')
    expect(request.file?.type).toBe('image/jpeg')
    expect(request.cropWidth).toBeGreaterThan(0)
    expect(request.cropHeight).toBeGreaterThan(0)
    expect(saved).toHaveBeenCalledWith(updated)
  })

  it('creates a standalone cover from existing memory media', async () => {
    const updated = memory({ coverUrl: '/api/tenant/memories/mem-1/cover/content' })
    setMemoryCoverMock.mockResolvedValue(updated)
    const saved = vi.fn()

    const wrapper = mount(MemoryCoverDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory(),
        media: [media()],
        onSaved: saved
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper.find('.memory-cover-option').trigger('click')
    await flushPromises()
    await wrapper.find('.memory-cover-cropper img').trigger('load')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Cover speichern'))!
      .trigger('click')
    await flushPromises()

    const request = setMemoryCoverMock.mock.calls[0][2]
    expect(request.mediaId).toBe('media-1')
    expect(request.file).toBeUndefined()
    expect(request.cropWidth).toBeGreaterThan(0)
    expect(request.cropHeight).toBeGreaterThan(0)
    expect(saved).toHaveBeenCalledWith(updated)
  })

  it('preloads an existing media cover with saved crop metadata', async () => {
    const updated = memory({ coverUrl: '/api/tenant/memories/mem-1/cover/content' })
    setMemoryCoverMock.mockResolvedValue(updated)

    const wrapper = mount(MemoryCoverDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory({
          coverMediaId: 'media-1',
          coverCrop: { x: 0, y: 0, width: 1, height: 0.75 }
        }),
        media: [media()]
      },
      global: { stubs: dialogStubs() }
    })

    await flushPromises()
    expect(wrapper.text()).toContain('Beach')
    await wrapper.find('.memory-cover-cropper img').trigger('load')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Cover speichern'))!
      .trigger('click')
    await flushPromises()

    expect(setMemoryCoverMock.mock.calls[0][2]).toMatchObject({ mediaId: 'media-1' })
  })

  it('updates crop only for an existing standalone cover without reuploading it', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const updated = memory({ coverUrl: '/api/tenant/memories/mem-1/cover/content' })
    setMemoryCoverMock.mockResolvedValue(updated)

    const wrapper = mount(MemoryCoverDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory({
          coverUrl: '/api/tenant/memories/mem-1/cover/content',
          coverCrop: { x: 0, y: 0, width: 1, height: 0.75 }
        }),
        media: []
      },
      global: { stubs: dialogStubs() }
    })

    await flushPromises()
    await wrapper.find('.memory-cover-cropper img').trigger('load')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Cover speichern'))!
      .trigger('click')
    await flushPromises()

    const request = setMemoryCoverMock.mock.calls[0][2]
    expect(fetchMock).not.toHaveBeenCalled()
    expect(request.file).toBeUndefined()
    expect(request.mediaId).toBeUndefined()
    expect(request.cropWidth).toBeGreaterThan(0)
    expect(request.cropHeight).toBeGreaterThan(0)
  })

  it('disables saving until a cover source is loaded', () => {
    const wrapper = mount(MemoryCoverDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory(),
        media: []
      },
      global: { stubs: dialogStubs() }
    })

    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('Cover speichern'))!
    expect(saveButton.attributes('disabled')).toBeDefined()
  })

  it('removes an existing cover', async () => {
    const updated = memory({ coverMediaId: null })
    removeMemoryCoverMock.mockResolvedValue(updated)
    const saved = vi.fn()

    const wrapper = mount(MemoryCoverDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory({ coverUrl: '/api/tenant/memories/mem-1/cover/content' }),
        media: [],
        onSaved: saved
      },
      global: { stubs: dialogStubs() }
    })

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Cover entfernen'))!
      .trigger('click')
    await flushPromises()

    expect(removeMemoryCoverMock).toHaveBeenCalledWith('tenant', 'mem-1')
    expect(saved).toHaveBeenCalledWith(updated)
  })
})

function dialogStubs() {
  return {
    Button: {
      template:
        '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
      emits: ['click'],
      props: ['disabled', 'label', 'type']
    },
    Dialog: {
      template: '<div v-if="visible"><slot /></div>',
      props: ['visible']
    },
    InputText: {
      template:
        '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    FileUpload: {
      name: 'FileUpload',
      template: '<button type="button">Bild auswählen</button>',
      emits: ['select']
    },
    Message: { template: '<div><slot /></div>' }
  }
}

function memory(overrides: Partial<Memory> = {}): Memory {
  return {
    id: 'mem-1',
    parentId: null,
    name: 'trip',
    displayName: 'Trip',
    path: '/trip',
    coverMediaId: null,
    coverUrl: null,
    coverCrop: null,
    startDate: '2026-05-03',
    endDate: '2026-05-05',
    childCount: 0,
    mediaCount: 0,
    scope: null,
    ...overrides
  }
}

function media(overrides: Partial<MemoryMedia> = {}): MemoryMedia {
  return {
    id: 'media-1',
    memoryId: 'mem-1',
    title: 'Beach',
    ownerUserId: 1,
    uploaderDisplayName: 'Owner',
    createdAt: '2026-05-01T12:00:00Z',
    mimeType: 'image/jpeg',
    sizeBytes: 12,
    contentUrl: '/api/tenant/memories/mem-1/media/media-1/content',
    previewUrl: '/api/tenant/memories/mem-1/media/media-1/preview',
    capturedAt: null,
    capturedAtOverride: false,
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    imageWidth: 800,
    imageHeight: 600,
    gpsLatitude: null,
    gpsLongitude: null,
    ...overrides
  }
}
