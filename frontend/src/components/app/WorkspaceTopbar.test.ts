import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { clearUploadQueue, startUploadBatch } from '../../features/memories/composables/useUploadQueue'
import { UploadCancelledError } from '../../shared/api/httpClient'
import WorkspaceTopbar from './WorkspaceTopbar.vue'

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  searchTenant: vi.fn()
}))
const routeState = vi.hoisted(() => ({
  name: 'tenant' as string | null,
  params: { tenantName: 'friends' } as Record<string, string>
}))

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => ({ push: mocks.push })
}))

vi.mock('../../features/memories/api', () => ({
  searchTenant: mocks.searchTenant
}))

describe('WorkspaceTopbar', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mocks.push.mockReset()
    mocks.searchTenant.mockReset()
    routeState.name = 'tenant'
    routeState.params = { tenantName: 'friends' }
  })

  afterEach(() => {
    vi.useRealTimers()
    clearUploadQueue()
  })

  it('opens search from the topbar and navigates to a result', async () => {
    mocks.searchTenant.mockResolvedValue([
      {
        kind: 'MEMORY',
        id: 'mem_123',
        displayName: 'Beach Day',
        path: '/summer/beach-day',
        scope: { type: 'TENANT' },
        matchTypes: new Set(['TITLE'])
      }
    ])
    const wrapper = mountTopbar()

    await wrapper.find('.workspace-topbar-center').trigger('click')
    await wrapper.find('.workspace-topbar-center input').setValue('beach')
    await vi.advanceTimersByTimeAsync(240)
    await flushPromises()

    expect(mocks.searchTenant).toHaveBeenCalledWith('friends', 'beach', 20)
    expect(wrapper.text()).toContain('Beach Day')

    await wrapper.find('.workspace-search-result').trigger('click')

    expect(mocks.push).toHaveBeenCalledWith('/friends/summer/beach-day')
    expect(wrapper.find('.workspace-search-panel').exists()).toBe(false)
  })

  it('opens with the keyboard shortcut and closes with escape', async () => {
    const wrapper = mountTopbar()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))
    await flushPromises()

    expect(wrapper.find('.workspace-search-panel').exists()).toBe(true)

    await wrapper.find('.workspace-topbar-center input').trigger('keydown', { key: 'Escape' })

    expect(wrapper.find('.workspace-search-panel').exists()).toBe(false)
  })

  it('supports keyboard selection with enter', async () => {
    mocks.searchTenant.mockResolvedValue([
      {
        kind: 'FOLDER',
        id: 'fol_123',
        displayName: 'Archive',
        path: '/archive',
        scope: { type: 'TENANT' },
        matchTypes: new Set(['TITLE'])
      },
      {
        kind: 'MEMORY',
        id: 'mem_123',
        displayName: 'Beach Day',
        path: '/beach-day',
        scope: { type: 'TENANT' },
        matchTypes: new Set(['TITLE'])
      }
    ])
    const wrapper = mountTopbar()

    await wrapper.find('.workspace-topbar-center').trigger('click')
    const input = wrapper.find('.workspace-topbar-center input')
    await input.setValue('beach')
    await vi.advanceTimersByTimeAsync(240)
    await flushPromises()
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Enter' })

    expect(mocks.push).toHaveBeenCalledWith('/friends/beach-day')
  })

  it('closes search when keyboard focus leaves the search area', async () => {
    mocks.searchTenant.mockResolvedValue([
      {
        kind: 'MEMORY',
        id: 'mem_123',
        displayName: 'Beach Day',
        path: '/beach-day',
        scope: { type: 'TENANT' },
        matchTypes: new Set(['TITLE'])
      }
    ])
    const wrapper = mountTopbar()
    const input = wrapper.find('.workspace-topbar-center input')

    await input.trigger('focus')
    await input.setValue('beach')
    await vi.advanceTimersByTimeAsync(240)
    await flushPromises()

    expect(wrapper.find('.workspace-search-panel').exists()).toBe(true)

    const nextButton = wrapper.find('.upload-status-trigger')
    await wrapper.find('.workspace-search').trigger('focusout', {
      relatedTarget: nextButton.element
    })

    expect(wrapper.find('.workspace-search-panel').exists()).toBe(false)
  })

  it('shows active uploads next to search and can cancel all', async () => {
    const uploadFile = vi.fn(
      (_file: File, options: { signal?: AbortSignal }) =>
        new Promise((_resolve, reject) => {
          options.signal?.addEventListener('abort', () => reject(new UploadCancelledError()))
        })
    )
    startUploadBatch({
      label: '2 Medien',
      files: [new File(['first'], 'first.jpg'), new File(['second'], 'second.jpg')],
      context: { type: 'memory', tenantName: 'friends', memoryId: 'mem-1' },
      uploadFile
    })
    const wrapper = mountTopbar()
    await nextTick()

    expect(wrapper.find('.upload-status-trigger').exists()).toBe(true)
    expect(wrapper.find('.upload-status-badge').text()).toBe('2')

    await wrapper.find('.upload-status-trigger').trigger('click')
    expect(wrapper.text()).toContain('first.jpg')
    expect(wrapper.text()).toContain('second.jpg')

    await wrapper.findAll('button').find((button) => button.text().includes('Alle abbrechen'))!.trigger('click')
    await nextTick()

    expect(wrapper.text()).toContain('Abgebrochen')
  })

  it('updates the open upload popover when a new upload starts', async () => {
    const wrapper = mountTopbar()
    await wrapper.find('.upload-status-trigger').trigger('click')
    expect(wrapper.text()).toContain('Keine aktiven Uploads')

    let capturedProgress: ((progress: { loaded: number; total: number | null }) => void) | undefined
    startUploadBatch({
      label: '1 Medium',
      files: [new File(['first-file'], 'live.jpg')],
      context: { type: 'memory', tenantName: 'friends', memoryId: 'mem-1' },
      uploadFile: (_file, options) =>
        new Promise((_resolve, reject) => {
          capturedProgress = options.onProgress
          options.signal?.addEventListener('abort', () => reject(new UploadCancelledError()))
        })
    })
    await nextTick()
    await flushPromises()
    capturedProgress?.({ loaded: 5, total: 10 })
    await nextTick()

    expect(wrapper.text()).toContain('live.jpg')
    expect(wrapper.text()).toContain('50%')
  })

  it('updates the open upload popover and badge as uploads finish', async () => {
    const resolvers: Array<() => void> = []
    const progressCallbacks: Array<(progress: { loaded: number; total: number | null }) => void> = []
    const uploadFile = vi.fn(
      (_file: File, options: { onProgress?: (progress: { loaded: number; total: number | null }) => void }) =>
        new Promise<void>((resolve) => {
          progressCallbacks.push((progress) => options.onProgress?.(progress))
          resolvers.push(resolve)
        })
    )

    startUploadBatch({
      label: '2 Medien',
      files: [new File(['first-file'], 'first.jpg'), new File(['second-file'], 'second.jpg')],
      context: { type: 'memory', tenantName: 'friends', memoryId: 'mem-1' },
      uploadFile
    })
    const wrapper = mountTopbar()
    await nextTick()
    await flushPromises()

    await wrapper.find('.upload-status-trigger').trigger('click')
    expect(wrapper.find('.upload-status-badge').text()).toBe('2')
    expect(wrapper.text()).toContain('first.jpg')

    progressCallbacks[0]?.({ loaded: 5, total: 10 })
    await nextTick()

    expect(wrapper.text()).toContain('50%')

    resolvers[0]()
    await flushPromises()
    await nextTick()

    expect(wrapper.find('.upload-status-badge').text()).toBe('1')
    expect(wrapper.text()).toContain('Fertig')
    expect(wrapper.text()).toContain('second.jpg')
  })

  it('shows upload batches in queue order when a later upload is added', async () => {
    const resolvers: Array<() => void> = []
    const uploadFile = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          resolvers.push(resolve)
        })
    )

    startUploadBatch({
      label: '450 Medien',
      files: [new File(['first-file'], 'wedding.jpg')],
      context: { type: 'memory', tenantName: 'friends', memoryId: 'mem-1' },
      uploadFile
    })
    startUploadBatch({
      label: '1 Medien',
      files: [new File(['second-file'], 'Teneriffa.jpg')],
      context: { type: 'memory', tenantName: 'friends', memoryId: 'mem-1' },
      uploadFile
    })
    const wrapper = mountTopbar()
    await nextTick()
    await flushPromises()

    await wrapper.find('.upload-status-trigger').trigger('click')
    const text = wrapper.text()

    expect(text.indexOf('450 Medien')).toBeLessThan(text.indexOf('1 Medien'))
    expect(text).toContain('Teneriffa.jpg')
    expect(text).toContain('Wartet')
  })

  it('closes the upload popover with escape and outside click', async () => {
    startUploadBatch({
      label: '1 Medium',
      files: [new File(['first-file'], 'first.jpg')],
      context: { type: 'memory', tenantName: 'friends', memoryId: 'mem-1' },
      uploadFile: () => new Promise(() => undefined)
    })
    const wrapper = mountTopbar()
    await nextTick()

    await wrapper.find('.upload-status-trigger').trigger('click')
    expect(wrapper.find('.upload-status-panel').exists()).toBe(true)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()
    expect(wrapper.find('.upload-status-panel').exists()).toBe(false)

    await wrapper.find('.upload-status-trigger').trigger('click')
    expect(wrapper.find('.upload-status-panel').exists()).toBe(true)

    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    await nextTick()
    expect(wrapper.find('.upload-status-panel').exists()).toBe(false)
  })
})

function mountTopbar() {
  return mount(WorkspaceTopbar, {
    global: {
      stubs: {
        Breadcrumb: { template: '<nav />' },
        Button: {
          template: '<button v-bind="$attrs" type="button" @click="$emit(\'click\', $event)">{{ label }}</button>',
          props: ['label'],
          emits: ['click']
        }
      }
    }
  })
}
