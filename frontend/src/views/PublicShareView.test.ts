import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import PublicShareView from './PublicShareView.vue'
import {
  getPublicShare,
  getPublicShareMemoryByPath,
  getPublicShareMemoryHeader,
  getPublicShareMemoryJournal,
  listPublicShareChildMemories,
  listPublicShareMemoryMedia,
  listPublicShareMemoryPlaylists,
  listPublicShareMemoryTracks,
  loginPublicShare,
  logoutPublicShare,
  type Memory,
  type MemoryHeader,
  type PublicShareMemorySummary,
  type PublicShareSummary
} from '../features/memories/api'

vi.mock('../features/memories/api', () => ({
  createPublicShareChildMemory: vi.fn(),
  createPublicShareMemoryPlaylist: vi.fn(),
  deletePublicShareMedia: vi.fn(),
  deletePublicShareMemory: vi.fn(),
  deletePublicShareMemoryPlaylist: vi.fn(),
  deletePublicShareMemoryTrack: vi.fn(),
  getPublicShare: vi.fn(),
  getPublicShareMemoryByPath: vi.fn(),
  getPublicShareMemoryHeader: vi.fn(),
  getPublicShareMemoryJournal: vi.fn(),
  listPublicShareChildMemories: vi.fn(),
  listPublicShareMemoryMedia: vi.fn(),
  listPublicShareMemoryPlaylists: vi.fn(),
  listPublicShareMemoryTracks: vi.fn(),
  loginPublicShare: vi.fn(),
  logoutPublicShare: vi.fn(),
  removePublicShareMemoryCover: vi.fn(),
  setPublicShareMemoryCover: vi.fn(),
  updatePublicShareMediaMetadata: vi.fn(),
  updatePublicShareMemory: vi.fn(),
  updatePublicShareMemoryJournal: vi.fn(),
  updatePublicShareMemoryPlaylist: vi.fn(),
  updatePublicShareMemoryTrack: vi.fn(),
  uploadPublicShareMedia: vi.fn(),
  uploadPublicShareMemoryMedia: vi.fn(),
  uploadPublicShareMemoryTrack: vi.fn()
}))

const getPublicShareMock = vi.mocked(getPublicShare)
const getPublicShareMemoryByPathMock = vi.mocked(getPublicShareMemoryByPath)
const getPublicShareMemoryHeaderMock = vi.mocked(getPublicShareMemoryHeader)
const getPublicShareMemoryJournalMock = vi.mocked(getPublicShareMemoryJournal)
const listPublicShareChildMemoriesMock = vi.mocked(listPublicShareChildMemories)
const listPublicShareMemoryMediaMock = vi.mocked(listPublicShareMemoryMedia)
const listPublicShareMemoryPlaylistsMock = vi.mocked(listPublicShareMemoryPlaylists)
const listPublicShareMemoryTracksMock = vi.mocked(listPublicShareMemoryTracks)
const loginPublicShareMock = vi.mocked(loginPublicShare)
const logoutPublicShareMock = vi.mocked(logoutPublicShare)

describe('PublicShareView', () => {
  afterEach(() => {
    vi.clearAllMocks()
    window.localStorage.clear()
  })

  it('shows a public footer and opens browser privacy settings in a dialog', async () => {
    getPublicShareMock.mockResolvedValue({
      id: 'share-1',
      role: 'VIEWER',
      authenticated: false,
      passwordRequired: true,
      displayNameRequired: false,
      canViewGallery: false,
      canUpload: false,
      canEdit: false,
      canDelete: false,
      rootMemory: {
        id: 'mem-1',
        name: 'trip',
        displayName: 'Trip',
        path: '/',
        parentId: '',
        childCount: 0,
        mediaCount: 0,
        startDate: '2026-05-03',
        endDate: '2026-05-05'
      }
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/s/:shareId/:pathMatch(.*)*', name: 'public-share', component: PublicShareView }]
    })
    await router.push('/s/share-1')
    await router.isReady()

    const wrapper = mount(PublicShareView, {
      global: {
        plugins: [router],
        stubs: viewStubs()
      }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Bereitgestellt von veyq')
    expect(wrapper.find('a[href="https://github.com/veyq/veyq"]').text()).toBe('Projekt')
    expect(wrapper.find('.privacy-settings-dialog-content').exists()).toBe(false)

    await wrapper.find('.public-share-footer button').trigger('click')

    expect(wrapper.find('.privacy-settings-dialog-content').exists()).toBe(true)
    expect(wrapper.text()).toContain('Karten')
    expect(wrapper.text()).toContain('OpenStreetMap')
  })

  it('keeps the public share hero on protected login screens', async () => {
    getPublicShareMock.mockResolvedValue(publicShare({ authenticated: false, passwordRequired: true }))
    const router = await publicShareRouter('/s/share-1')

    const wrapper = mount(PublicShareView, {
      global: {
        plugins: [router],
        stubs: viewStubs()
      }
    })
    await flushPromises()

    expect(wrapper.find('.public-share-header').exists()).toBe(true)
    expect(wrapper.find('.public-share-header h1').text()).toBe('Trip')
    expect(wrapper.find('[data-testid="memory-detail"]').exists()).toBe(false)
  })

  it('opens a password-protected viewer share without a display name', async () => {
    getPublicShareMock.mockResolvedValue(publicShare({ authenticated: false, passwordRequired: true }))
    loginPublicShareMock.mockResolvedValue(
      publicShare({ authenticated: true, passwordRequired: true, canViewGallery: true })
    )
    getPublicShareMemoryByPathMock.mockResolvedValue(memory())
    getPublicShareMemoryHeaderMock.mockResolvedValue(memoryHeader())
    getPublicShareMemoryJournalMock.mockResolvedValue({
      memoryId: 'mem-1',
      markdown: '',
      updatedAt: '2026-05-27T06:00:00Z'
    })
    listPublicShareChildMemoriesMock.mockResolvedValue([])
    listPublicShareMemoryMediaMock.mockResolvedValue([])
    listPublicShareMemoryTracksMock.mockResolvedValue([])
    listPublicShareMemoryPlaylistsMock.mockResolvedValue([])
    const router = await publicShareRouter('/s/share-1')

    const wrapper = mount(PublicShareView, {
      global: {
        plugins: [router],
        stubs: viewStubs()
      }
    })
    await flushPromises()

    expect(wrapper.find('input[placeholder="Anzeigename"]').exists()).toBe(false)
    const passwordInput = wrapper.find('input[placeholder="Passwort"]')
    expect(passwordInput.exists()).toBe(true)
    expect(wrapper.find('.public-share-login button[type="submit"]').attributes('disabled')).toBeDefined()

    await passwordInput.setValue('share-password123')

    expect(wrapper.find('.public-share-login button[type="submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('.public-share-login').trigger('submit')
    await flushPromises()

    expect(loginPublicShareMock).toHaveBeenCalledWith('share-1', 'share-password123', null)
  })

  it('hides the public share hero once the memory detail is visible', async () => {
    mockProtectedMemory()
    const router = await publicShareRouter('/s/share-1')

    const wrapper = mount(PublicShareView, {
      global: {
        plugins: [router],
        stubs: viewStubs()
      }
    })
    await flushPromises()

    expect(wrapper.find('.public-share-header').exists()).toBe(false)
    expect(wrapper.find('[data-testid="memory-detail"]').exists()).toBe(true)
    expect(wrapper.find('.public-share-actions').exists()).toBe(true)
  })

  it('uses memory tab hashes and drops them when opening a child memory', async () => {
    mockProtectedMemory()
    const router = await publicShareRouter('/s/share-1#journal')

    const wrapper = mount(PublicShareView, {
      global: {
        plugins: [router],
        stubs: viewStubs()
      }
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="memory-detail"]').attributes('data-active-tab')).toBe('journal')

    await wrapper.find('[data-testid="select-tracks-tab"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/s/share-1#tracks')

    await wrapper.find('[data-testid="open-child-memory"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/s/share-1/day-1')
  })

  it('keeps a close action for authenticated share sessions', async () => {
    mockProtectedMemory()
    logoutPublicShareMock.mockResolvedValue(undefined)
    getPublicShareMock
      .mockResolvedValueOnce(publicShare({ authenticated: true, canViewGallery: true }))
      .mockResolvedValueOnce(publicShare({ authenticated: false, passwordRequired: true }))
    const router = await publicShareRouter('/s/share-1')

    const wrapper = mount(PublicShareView, {
      global: {
        plugins: [router],
        stubs: viewStubs()
      }
    })
    await flushPromises()

    await wrapper.find('.public-share-actions button').trigger('click')
    await flushPromises()

    expect(logoutPublicShareMock).toHaveBeenCalledWith('share-1')
    expect(getPublicShareMock).toHaveBeenLastCalledWith('share-1')
  })
})

async function publicShareRouter(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/s/:shareId/:pathMatch(.*)*', name: 'public-share', component: PublicShareView }]
  })
  await router.push(path)
  await router.isReady()
  return router
}

function mockProtectedMemory() {
  getPublicShareMock.mockResolvedValue(publicShare({ authenticated: true, canViewGallery: true }))
  getPublicShareMemoryByPathMock.mockResolvedValue(memory())
  getPublicShareMemoryHeaderMock.mockResolvedValue(memoryHeader())
  getPublicShareMemoryJournalMock.mockResolvedValue({
    memoryId: 'mem-1',
    markdown: '',
    updatedAt: '2026-05-27T06:00:00Z'
  })
  listPublicShareChildMemoriesMock.mockResolvedValue([])
  listPublicShareMemoryMediaMock.mockResolvedValue([])
  listPublicShareMemoryTracksMock.mockResolvedValue([])
  listPublicShareMemoryPlaylistsMock.mockResolvedValue([])
}

function publicShare(overrides: Partial<PublicShareSummary> = {}): PublicShareSummary {
  return {
    id: 'share-1',
    role: 'VIEWER',
    authenticated: false,
    passwordRequired: false,
    displayNameRequired: false,
    canViewGallery: false,
    canUpload: false,
    canEdit: false,
    canDelete: false,
    rootMemory: publicShareMemory(),
    ...overrides
  }
}

function publicShareMemory(overrides: Partial<PublicShareMemorySummary> = {}): PublicShareMemorySummary {
  return {
    id: 'mem-1',
    parentId: '',
    name: 'trip',
    displayName: 'Trip',
    path: '/',
    startDate: '2026-05-03',
    endDate: '2026-05-05',
    childCount: 0,
    mediaCount: 0,
    ...overrides
  }
}

function memory(overrides: Partial<Memory> = {}): Memory {
  return {
    id: 'mem-1',
    parentId: null,
    name: 'trip',
    displayName: 'Trip',
    path: '/',
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

function memoryHeader(overrides: Partial<MemoryHeader> = {}): MemoryHeader {
  return {
    id: 'mem-1',
    displayName: 'Trip',
    path: '/',
    coverUrl: null,
    coverCrop: null,
    startDate: '2026-05-03',
    endDate: '2026-05-05',
    directMediaCount: 0,
    descendantMediaCount: 0,
    childMemoryCount: 0,
    favorite: false,
    scope: null,
    participants: [],
    ...overrides
  }
}

function viewStubs() {
  return {
    Button: {
      props: ['label', 'type', 'disabled'],
      emits: ['click'],
      template: '<button :type="type || `button`" :disabled="disabled" @click="$emit(`click`)">{{ label }}<slot /></button>'
    },
    CreateMemoryDialog: true,
    Dialog: {
      props: ['visible', 'header'],
      template: '<div v-if="visible" class="p-dialog"><h2>{{ header }}</h2><slot /></div>'
    },
    FileUpload: true,
    InputText: {
      props: ['modelValue', 'type', 'autocomplete', 'placeholder'],
      emits: ['update:modelValue'],
      template:
        '<input :type="type || `text`" :autocomplete="autocomplete" :placeholder="placeholder" :value="modelValue" @input="$emit(`update:modelValue`, $event.target.value)" />'
    },
    MemoryDetail: {
      props: ['activeTab'],
      emits: ['update:activeTab', 'openMemory'],
      template: `
        <section data-testid="memory-detail" :data-active-tab="activeTab">
          <button type="button" data-testid="select-tracks-tab" @click="$emit('update:activeTab', 'tracks')" />
          <button type="button" data-testid="open-child-memory" @click="$emit('openMemory', { path: '/day-1' })" />
        </section>
      `
    },
    Message: {
      template: '<div><slot /></div>'
    },
    ProgressSpinner: true,
    UploadStatusMenu: true
  }
}
