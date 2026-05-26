import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/httpClient'
import { clearUploadQueue, useUploadQueue } from '../composables/useUploadQueue'
import {
  createMemory,
  createMemoryShare,
  deleteMemory,
  listMemoryShares,
  removeMemoryCover,
  setMemoryCover,
  updateMemory,
  updateMemoryShare,
  uploadMemoryMedia,
  type Memory,
  type MemoryMedia,
  type MemoryShareSummary
} from '../api'
import CreateMemoryDialog from './CreateMemoryDialog.vue'
import DeleteMemoryDialog from './DeleteMemoryDialog.vue'
import EditMemoryDialog from './EditMemoryDialog.vue'
import MemoryCoverDialog from './MemoryCoverDialog.vue'
import MemoryShareDialog from './MemoryShareDialog.vue'
import MemoryUploadDialog from './MemoryUploadDialog.vue'
import MemoryUploaderShareDialog from './MemoryUploaderShareDialog.vue'

vi.mock('../api', () => ({
  createMemory: vi.fn(),
  createMemoryShare: vi.fn(),
  deleteMemory: vi.fn(),
  listMemoryShares: vi.fn(),
  removeMemoryCover: vi.fn(),
  setMemoryCover: vi.fn(),
  updateMemory: vi.fn(),
  updateMemoryShare: vi.fn(),
  uploadMemoryMedia: vi.fn()
}))

const createMemoryMock = vi.mocked(createMemory)
const createMemoryShareMock = vi.mocked(createMemoryShare)
const deleteMemoryMock = vi.mocked(deleteMemory)
const listMemorySharesMock = vi.mocked(listMemoryShares)
const removeMemoryCoverMock = vi.mocked(removeMemoryCover)
const setMemoryCoverMock = vi.mocked(setMemoryCover)
const updateMemoryMock = vi.mocked(updateMemory)
const updateMemoryShareMock = vi.mocked(updateMemoryShare)
const uploadMemoryMediaMock = vi.mocked(uploadMemoryMedia)

describe('Memory detail dialogs', () => {
  beforeEach(() => {
    vi.stubGlobal('navigator', {
      clipboard: {
        writeText: vi.fn()
      }
    })
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
    clearUploadQueue()
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

    const deleteButton = () =>
      wrapper.findAll('button').find((button) => button.text().includes('Memory löschen'))!
    expect(deleteButton().attributes('disabled')).toBeDefined()
    await wrapper.find('input').setValue('Trip')
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

  it('uploads selected media files with their file names as titles', async () => {
    const first = new File(['first'], 'beach.jpg', { type: 'image/jpeg' })
    const second = new File(['second'], 'clip.mov', { type: 'video/quicktime' })
    uploadMemoryMediaMock.mockResolvedValue(media())

    const wrapper = mount(MemoryUploadDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memoryId: 'mem-1'
      },
      global: { stubs: dialogStubs() }
    })

    wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [first, second] })
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(uploadMemoryMediaMock).toHaveBeenNthCalledWith(1, 'tenant', 'mem-1', {
      file: first,
      title: 'beach.jpg'
    }, expect.any(Object))
    expect(uploadMemoryMediaMock).toHaveBeenNthCalledWith(2, 'tenant', 'mem-1', {
      file: second,
      title: 'clip.mov'
    }, expect.any(Object))
  })

  it('removes individual selected media files and clears the full selection', async () => {
    const first = new File(['first'], 'beach.jpg', { type: 'image/jpeg' })
    const second = new File(['second'], 'clip.mov', { type: 'video/quicktime' })
    const third = new File(['third'], 'mountain.jpg', { type: 'image/jpeg' })
    uploadMemoryMediaMock.mockResolvedValue(media())

    const wrapper = mount(MemoryUploadDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memoryId: 'mem-1'
      },
      global: { stubs: dialogStubs() }
    })

    wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [first, second] })
    await flushPromises()
    await wrapper.find('button[aria-label="beach.jpg aus Auswahl entfernen"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('beach.jpg')
    expect(wrapper.text()).toContain('clip.mov')
    expect(wrapper.text()).toContain('1 Medien ausgewählt')

    wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [first, second, third] })
    await flushPromises()
    await wrapper.find('.memory-upload-clear-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Keine Medien ausgewählt')
    wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [third] })
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(uploadMemoryMediaMock).toHaveBeenCalledTimes(1)
    expect(uploadMemoryMediaMock).toHaveBeenCalledWith('tenant', 'mem-1', {
      file: third,
      title: 'mountain.jpg'
    }, expect.any(Object))
  })

  it('passes file names to injected share media upload handlers', async () => {
    const file = new File(['first'], 'shared.jpg', { type: 'image/jpeg' })
    const uploadMedia = vi.fn().mockResolvedValue(media())

    const wrapper = mount(MemoryUploadDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memoryId: 'mem-1',
        uploadMedia
      },
      global: { stubs: dialogStubs() }
    })

    wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [file] })
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(uploadMedia).toHaveBeenCalledWith('mem-1', {
      file,
      title: 'shared.jpg'
    }, expect.any(Object))
  })

  it('starts a background upload batch and closes the dialog', async () => {
    const first = new File(['first-file'], 'beach.jpg', { type: 'image/jpeg' })
    const uploadMedia = vi.fn().mockResolvedValue(media())
    const saved = vi.fn()
    const { visibleBatches } = useUploadQueue()

    const wrapper = mount(MemoryUploadDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memoryId: 'mem-1',
        uploadMedia,
        onSaved: saved
      },
      global: { stubs: dialogStubs() }
    })

    wrapper.findComponent({ name: 'FileUpload' }).vm.$emit('select', { files: [first] })
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(visibleBatches.value[0].files[0].name).toBe('beach.jpg')
    expect(uploadMedia).toHaveBeenCalledWith('mem-1', { file: first, title: 'beach.jpg' }, expect.any(Object))
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
    expect(saved).toHaveBeenCalled()
  })

  it('shows localized cover error codes', async () => {
    const file = new File(['cover'], 'cover.jpg', { type: 'image/jpeg' })
    setMemoryCoverMock.mockRejectedValue(
      new ApiError(400, 'MEMORY_COVER_CROP_INVALID', 'Cover crop is invalid', {})
    )

    const wrapper = mount(MemoryCoverDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory(),
        media: []
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

    expect(wrapper.text()).toContain('Der gewählte Cover-Ausschnitt ist ungültig.')
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

  it('manages existing shares through compact row actions', async () => {
    listMemorySharesMock.mockResolvedValue([
      share({ id: 'shr-1', name: 'Family', role: 'VIEWER', hasPassword: true }),
      share({ id: 'shr-upload', name: 'Drop Zone', role: 'UPLOADER', hasPassword: false })
    ])
    createMemoryShareMock.mockResolvedValue(share({ id: 'shr-2', hasPassword: false }))
    updateMemoryShareMock.mockResolvedValue(share({ id: 'shr-1', name: 'Family' }))

    const wrapper = mount(MemoryShareDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory()
      },
      global: dialogGlobal()
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Family')
    expect(wrapper.text()).not.toContain('Drop Zone')
    expect(wrapper.find('.form-grid select').text()).not.toContain('Uploader')
    expect(wrapper.find('.management-control').text()).not.toContain('Uploader')
    expect(wrapper.find('[aria-label="Viewer: Kann die geteilte Memory und ihre Medien ansehen."]').exists()).toBe(true)
    expect(
      wrapper.find('[aria-label="Viewer: Kann die geteilte Memory und ihre Medien ansehen."]').attributes('title')
    ).toBeUndefined()
    expect(
      wrapper
        .find(
          '[aria-label="Contributor: Kann zusätzlich Metadaten bearbeiten, Medien hochladen und Kapitel anlegen, aber nicht löschen."]'
        )
        .exists()
    ).toBe(true)
    expect(
      wrapper
        .find(
          '[aria-label="Manager: Kann zusätzlich Medien und Unter-Memories löschen, aber nicht die geteilte Root-Memory."]'
        )
        .exists()
    ).toBe(true)
    expect(wrapper.find('.management-row [aria-label="Viewer: Kann die geteilte Memory und ihre Medien ansehen."]').exists()).toBe(true)

    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(createMemoryShareMock).toHaveBeenCalledWith('tenant', 'mem-1', {
      name: null,
      role: 'VIEWER',
      password: null
    })

    await wrapper.find('input[aria-label="Passwortschutz aktivieren"]').setValue(true)
    await flushPromises()
    const createButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('Link erstellen'))!
    expect(createButton.attributes('disabled')).toBeDefined()

    await wrapper.find('input[autocomplete="new-password"]').setValue('share-password123')
    await createButton.trigger('click')
    await flushPromises()
    expect(createMemoryShareMock).toHaveBeenCalledWith('tenant', 'mem-1', {
      name: null,
      role: 'VIEWER',
      password: 'share-password123'
    })

    await wrapper.find('.management-control').setValue('CONTRIBUTOR')
    await flushPromises()
    expect(updateMemoryShareMock).toHaveBeenCalledWith('tenant', 'shr-1', { role: 'CONTRIBUTOR' })

    await wrapper.find('button[aria-label="Freigabe-Link kopieren"]').trigger('click')
    await flushPromises()
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining('/s/shr-1'))
    expect(wrapper.text()).toContain('Freigabe-Link wurde kopiert.')

    await wrapper.find('button[aria-label="Freigabe umbenennen"]').trigger('click')
    await flushPromises()
    await wrapper.findAll('input').at(-1)!.setValue('Friends')
    await wrapper.findAll('form').at(-1)!.trigger('submit')
    await flushPromises()
    expect(updateMemoryShareMock).toHaveBeenCalledWith('tenant', 'shr-1', { name: 'Friends' })

    await wrapper.find('button[aria-label="Passwort ändern"]').trigger('click')
    await flushPromises()
    await wrapper.findAll('input').at(-1)!.setValue('new-share-password123')
    await wrapper.findAll('form').at(-1)!.trigger('submit')
    await flushPromises()
    expect(updateMemoryShareMock).toHaveBeenCalledWith('tenant', 'shr-1', {
      password: 'new-share-password123'
    })

    expect(wrapper.find('button[aria-label="Passwortschutz entfernen"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="Passwort entfernen"]').exists()).toBe(false)

    await wrapper.find('button[aria-label="Passwort ändern"]').trigger('click')
    await flushPromises()
    await wrapper.findAll('input[aria-label="Passwortschutz aktivieren"]').at(-1)!.setValue(false)
    await wrapper.findAll('form').at(-1)!.trigger('submit')
    await flushPromises()
    expect(updateMemoryShareMock).toHaveBeenCalledWith('tenant', 'shr-1', { password: '' })
  })

  it('manages uploader shares separately from memory shares', async () => {
    listMemorySharesMock.mockResolvedValue([
      share({ id: 'shr-view', name: 'Family', role: 'VIEWER', hasPassword: true }),
      share({
        id: 'shr-upload',
        name: 'Drop Zone',
        role: 'UPLOADER',
        hasPassword: true,
        shareUrl: '/s/shr-upload'
      })
    ])
    createMemoryShareMock.mockResolvedValue(share({ id: 'shr-new', role: 'UPLOADER', hasPassword: false }))
    updateMemoryShareMock.mockResolvedValue(share({ id: 'shr-upload', role: 'UPLOADER', name: 'Drop Zone' }))

    const wrapper = mount(MemoryUploaderShareDialog, {
      props: {
        visible: true,
        tenantName: 'tenant',
        memory: memory()
      },
      global: { stubs: dialogStubs() }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Drop Zone')
    expect(wrapper.text()).not.toContain('Family')

    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(createMemoryShareMock).toHaveBeenCalledWith('tenant', 'mem-1', {
      name: null,
      role: 'UPLOADER',
      password: null
    })

    await wrapper.find('input[aria-label="Passwortschutz aktivieren"]').setValue(true)
    await flushPromises()
    const createButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('Uploader-Link erstellen'))!
    expect(createButton.attributes('disabled')).toBeDefined()

    await wrapper.find('input[autocomplete="new-password"]').setValue('upload-password123')
    await createButton.trigger('click')
    await flushPromises()
    expect(createMemoryShareMock).toHaveBeenCalledWith('tenant', 'mem-1', {
      name: null,
      role: 'UPLOADER',
      password: 'upload-password123'
    })

    await wrapper.find('button[aria-label="Freigabe-Link kopieren"]').trigger('click')
    await flushPromises()
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining('/s/shr-upload'))
    expect(wrapper.text()).toContain('Freigabe-Link wurde kopiert.')

    await wrapper.find('button[aria-label="Freigabe umbenennen"]').trigger('click')
    await flushPromises()
    await wrapper.findAll('input').at(-1)!.setValue('Guest uploads')
    await wrapper.findAll('form').at(-1)!.trigger('submit')
    await flushPromises()
    expect(updateMemoryShareMock).toHaveBeenCalledWith('tenant', 'shr-upload', { name: 'Guest uploads' })

    await wrapper.find('button[aria-label="Freigabe deaktivieren"]').trigger('click')
    await flushPromises()
    expect(updateMemoryShareMock).toHaveBeenCalledWith('tenant', 'shr-upload', { enabled: false })

    await wrapper.find('button[aria-label="Passwort ändern"]').trigger('click')
    await flushPromises()
    await wrapper.findAll('input').at(-1)!.setValue('new-upload-password123')
    await wrapper.findAll('form').at(-1)!.trigger('submit')
    await flushPromises()
    expect(updateMemoryShareMock).toHaveBeenCalledWith('tenant', 'shr-upload', {
      password: 'new-upload-password123'
    })
  })
})

function dialogStubs() {
  return {
    Button: {
      template:
        '<button v-bind="$attrs" :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
      emits: ['click'],
      props: ['disabled', 'label', 'type']
    },
    Dialog: {
      template: '<div v-if="visible"><slot /></div>',
      props: ['visible']
    },
    InputText: {
      template:
        '<input v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    FileUpload: {
      name: 'FileUpload',
      template: '<button type="button">Bild auswählen</button>',
      emits: ['select']
    },
    Message: { template: '<div><slot /></div>' },
    Select: {
      inheritAttrs: false,
      props: ['modelValue', 'options', 'optionLabel', 'optionValue'],
      emits: ['update:modelValue'],
      template: `
        <div>
          <select
            v-bind="$attrs"
            :value="modelValue"
            @change="$emit('update:modelValue', $event.target.value)"
          >
            <option
              v-for="option in options"
              :key="option[optionValue]"
              :value="option[optionValue]"
            >
              {{ option[optionLabel] }}
            </option>
          </select>
          <div class="select-value-slot">
            <slot name="value" :value="modelValue" />
          </div>
          <div class="select-option-slots">
            <div v-for="option in options" :key="option[optionValue]">
              <slot name="option" :option="option" />
            </div>
          </div>
        </div>
      `
    },
    ToggleSwitch: {
      props: ['modelValue', 'ariaLabel'],
      emits: ['update:modelValue'],
      template:
        '<input type="checkbox" :aria-label="ariaLabel || $attrs[\'aria-label\']" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />'
    }
  }
}

function dialogGlobal() {
  return {
    stubs: dialogStubs(),
    directives: {
      tooltip: {}
    }
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
    favorite: false,
    scope: null,
    ...overrides
  }
}

function media(overrides: Partial<MemoryMedia> = {}): MemoryMedia {
  return {
    id: 'media-1',
    memoryId: 'mem-1',
    title: 'Beach',
    uploader: { type: 'USER', displayName: 'Owner', userId: 1 },
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

function share(overrides: Partial<MemoryShareSummary> = {}): MemoryShareSummary {
  return {
    id: 'shr-1',
    memoryId: 'mem-1',
    name: undefined,
    role: 'VIEWER',
    enabled: true,
    shareUrl: '/s/shr-1',
    hasPassword: true,
    createdAt: '2026-05-24T10:00:00Z',
    updatedAt: '2026-05-24T10:00:00Z',
    ...overrides
  }
}
