import { flushPromises } from '@vue/test-utils'
import { computed, effectScope, ref, type EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getMemoryByPath,
  getMemoryHeader,
  getMemoryJournal,
  getMemoryNavigation,
  listChildMemories,
  listMemoriesByFolder,
  listMemoryMedia,
  listMemoryMediaBuckets,
  listMemoryPlaylists,
  listMemoryTracks,
  waitForNewMemoryMedia,
  type Memory,
  type MemoryHeader,
  type MemoryMedia,
  type MemoryMediaBucket
} from '../api'
import { useMemoryRouteData } from './useMemoryRouteData'

vi.mock('../api', () => ({
  getMemoryByPath: vi.fn(),
  getMemoryHeader: vi.fn(),
  getMemoryJournal: vi.fn(),
  getMemoryNavigation: vi.fn(),
  listChildMemories: vi.fn(),
  listMemoriesByFolder: vi.fn(),
  listMemoryMedia: vi.fn(),
  listMemoryMediaBuckets: vi.fn(),
  listMemoryPlaylists: vi.fn(),
  listMemoryTracks: vi.fn(),
  waitForNewMemoryMedia: vi.fn()
}))

const getMemoryByPathMock = vi.mocked(getMemoryByPath)
const getMemoryHeaderMock = vi.mocked(getMemoryHeader)
const getMemoryJournalMock = vi.mocked(getMemoryJournal)
const getMemoryNavigationMock = vi.mocked(getMemoryNavigation)
const listChildMemoriesMock = vi.mocked(listChildMemories)
const listMemoriesByFolderMock = vi.mocked(listMemoriesByFolder)
const listMemoryMediaMock = vi.mocked(listMemoryMedia)
const listMemoryMediaBucketsMock = vi.mocked(listMemoryMediaBuckets)
const listMemoryPlaylistsMock = vi.mocked(listMemoryPlaylists)
const listMemoryTracksMock = vi.mocked(listMemoryTracks)
const waitForNewMemoryMediaMock = vi.mocked(waitForNewMemoryMedia)

describe('useMemoryRouteData', () => {
  let scope: EffectScope

  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(document, 'visibilityState', {
      value: 'visible',
      configurable: true
    })
    getMemoryNavigationMock.mockResolvedValue({
      location: { kind: 'FOLDER', path: '/', displayName: 'Root' },
      breadcrumbs: [],
      scopeRoots: [],
      folders: [],
      favoriteMemories: []
    })
    listMemoriesByFolderMock.mockResolvedValue([])
    getMemoryJournalMock.mockResolvedValue({
      memoryId: 'mem-1',
      markdown: '',
      updatedAt: '2026-05-27T06:00:00Z'
    })
    listChildMemoriesMock.mockResolvedValue([])
    listMemoryTracksMock.mockResolvedValue([])
    listMemoryPlaylistsMock.mockResolvedValue([])
    waitForNewMemoryMediaMock.mockImplementation(() => new Promise(() => {}))
  })

  afterEach(() => {
    scope?.stop()
  })

  it('merges long-polled Memory media and refreshes buckets and header counts', async () => {
    getMemoryByPathMock.mockResolvedValue(memory())
    getMemoryHeaderMock
      .mockResolvedValueOnce(memoryHeader({ directMediaCount: 0 }))
      .mockResolvedValueOnce(memoryHeader({ directMediaCount: 1 }))
    listMemoryMediaMock.mockResolvedValueOnce([
      memoryMedia({ id: 'media-existing', updatedAt: '2026-05-03T12:00:00Z' })
    ])
    listMemoryMediaBucketsMock
      .mockResolvedValueOnce([mediaBucket({ mediaCount: 0 })])
      .mockResolvedValueOnce([mediaBucket({ mediaCount: 1 })])
    const update = deferred<Awaited<ReturnType<typeof waitForNewMemoryMedia>>>()
    waitForNewMemoryMediaMock.mockReturnValueOnce(update.promise).mockImplementation(() => new Promise(() => {}))

    const tenantName = ref('tenant')
    const routePath = ref('/trip')
    let data: ReturnType<typeof useMemoryRouteData> | undefined
    scope = effectScope()
    scope.run(() => {
      data = useMemoryRouteData(computed(() => tenantName.value), computed(() => routePath.value))
    })

    await flushUntil(() => data?.media.value.map((item) => item.id).join(',') === 'media-existing')

    expect(data?.media.value.map((item) => item.id)).toEqual(['media-existing'])
    expect(data?.memoryHeader.value?.directMediaCount).toBe(0)

    update.resolve({
      media: [
        memoryMedia({
          id: 'media-fresh',
          title: 'Fresh upload',
          createdAt: '2026-05-03T12:01:00Z',
          capturedAt: '2026-05-03T11:59:00Z'
        })
      ]
    })
    await flushUntil(() => listMemoryMediaBucketsMock.mock.calls.length === 2)

    expect(waitForNewMemoryMediaMock).toHaveBeenCalledWith(
      'tenant',
      'mem-1',
      true,
      '2026-05-03T12:00:00Z',
      'media-existing',
      '1eee2587783688b1a385456d969a244dddcbf4ec41a43a80a273cdcbbff485db',
      expect.any(AbortSignal)
    )
    expect(listMemoryMediaMock).toHaveBeenCalledTimes(1)
    expect(listMemoryMediaBucketsMock).toHaveBeenCalledTimes(2)
    expect(getMemoryHeaderMock).toHaveBeenCalledTimes(2)
    expect(data?.media.value.map((item) => item.id)).toEqual(['media-fresh', 'media-existing'])
    expect(data?.mediaBuckets.value.map((bucket) => bucket.mediaCount)).toEqual([1])
    expect(data?.memoryHeader.value?.directMediaCount).toBe(1)
  })

  it('removes deleted media from long-polled current media ids without refetching media', async () => {
    getMemoryByPathMock.mockResolvedValue(memory())
    getMemoryHeaderMock
      .mockResolvedValueOnce(memoryHeader({ directMediaCount: 2 }))
      .mockResolvedValueOnce(memoryHeader({ directMediaCount: 1 }))
    listMemoryMediaMock.mockResolvedValueOnce([
      memoryMedia({ id: 'media-existing', updatedAt: '2026-05-03T12:00:00Z' }),
      memoryMedia({ id: 'media-deleted', updatedAt: '2026-05-03T12:01:00Z', highlight: true })
    ])
    listMemoryMediaBucketsMock
      .mockResolvedValueOnce([mediaBucket({ mediaCount: 2 })])
      .mockResolvedValueOnce([mediaBucket({ mediaCount: 1 })])
    const update = deferred<Awaited<ReturnType<typeof waitForNewMemoryMedia>>>()
    waitForNewMemoryMediaMock.mockReturnValueOnce(update.promise).mockImplementation(() => new Promise(() => {}))

    const tenantName = ref('tenant')
    const routePath = ref('/trip')
    let data: ReturnType<typeof useMemoryRouteData> | undefined
    scope = effectScope()
    scope.run(() => {
      data = useMemoryRouteData(computed(() => tenantName.value), computed(() => routePath.value))
    })

    await flushUntil(() => data?.media.value.map((item) => item.id).join(',') === 'media-existing,media-deleted')

    update.resolve({
      media: [],
      currentMediaIds: ['media-existing']
    })
    await flushUntil(() => listMemoryMediaBucketsMock.mock.calls.length === 2)

    expect(listMemoryMediaMock).toHaveBeenCalledTimes(1)
    expect(listMemoryMediaBucketsMock).toHaveBeenCalledTimes(2)
    expect(getMemoryHeaderMock).toHaveBeenCalledTimes(2)
    expect(data?.media.value.map((item) => item.id)).toEqual(['media-existing'])
    expect(data?.media.value.some((item) => item.highlight)).toBe(false)
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((innerResolve) => {
    resolve = innerResolve
  })
  return { promise, resolve }
}

async function flushUntil(done: () => boolean) {
  await vi.waitFor(async () => {
    await flushPromises()
    expect(done()).toBe(true)
  })
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

function memoryHeader(overrides: Partial<MemoryHeader> = {}): MemoryHeader {
  return {
    id: 'mem-1',
    displayName: 'Trip',
    path: '/trip',
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

function memoryMedia(overrides: Partial<MemoryMedia> = {}): MemoryMedia {
  return {
    id: 'media-1',
    memoryId: 'mem-1',
    title: 'Photo',
    uploader: { type: 'USER', displayName: 'Kevin', permalink: 'kevin', userId: 1 },
    createdAt: '2026-05-03T12:00:00Z',
    updatedAt: '2026-05-03T12:00:00Z',
    capturedAt: '2026-05-03T12:00:00Z',
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 1024,
    contentUrl: '/api/tenant/memory-media/media-1/content',
    thumbnailUrl: '/api/tenant/memory-media/media-1/thumbnail',
    previewUrl: '/api/tenant/memory-media/media-1/preview',
    posterUrl: null,
    bucketId: 'bucket-1',
    imageWidth: 1024,
    imageHeight: 768,
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    gpsLatitude: null,
    gpsLongitude: null,
    highlight: false,
    ...overrides
  }
}

function mediaBucket(overrides: Partial<MemoryMediaBucket> = {}): MemoryMediaBucket {
  return {
    id: 'bucket-1',
    memoryId: 'mem-1',
    parentBucketId: undefined,
    displayName: 'Kevin',
    primary: true,
    uploader: { type: 'USER', displayName: 'Kevin', permalink: 'kevin', userId: 1 },
    canManage: true,
    mediaCount: 0,
    createdAt: '2026-05-03T12:00:00Z',
    updatedAt: '2026-05-03T12:00:00Z',
    ...overrides
  }
}
