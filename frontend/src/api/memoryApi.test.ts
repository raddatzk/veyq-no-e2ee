import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createMemory,
  deleteMemory,
  favoriteMemory,
  getMemoryHeader,
  getMemoryByPath,
  getPublicShareMemoryHeader,
  listMemories,
  listMemoriesByFolder,
  listMemoryMedia,
  removeMemoryCover,
  setMemoryCover,
  updateMemory,
  updateMemoryMediaMetadataBatch,
  unfavoriteMemory,
  uploadMemoryMedia,
  waitForNewMemoryMedia
} from './memoryApi'

const mocks = vi.hoisted(() => ({
  generatedCall: vi.fn((request: Promise<unknown>) => request),
  listMemories: vi.fn(),
  listMemoriesByFolder: vi.fn(),
  getMemoryByPath: vi.fn(),
  getMemoryHeader: vi.fn(),
  favoriteMemory: vi.fn(),
  unfavoriteMemory: vi.fn(),
  createMemory: vi.fn(),
  updateMemory: vi.fn(),
  deleteMemory: vi.fn(),
  setMemoryCover: vi.fn(),
  removeMemoryCover: vi.fn(),
  listMemoryMedia: vi.fn(),
  updateMemoryMediaMetadataBatch: vi.fn(),
  callApi: vi.fn(),
  uploadBlob: vi.fn(),
  getPublicShareMemoryHeader: vi.fn()
}))

vi.mock('./generatedClient', () => ({
  generatedCall: mocks.generatedCall,
  memoriesApi: {
    listMemories: mocks.listMemories,
    listMemoriesByFolder: mocks.listMemoriesByFolder,
    getMemoryByPath: mocks.getMemoryByPath,
    getMemoryHeader: mocks.getMemoryHeader,
    favoriteMemory: mocks.favoriteMemory,
    unfavoriteMemory: mocks.unfavoriteMemory,
    createMemory: mocks.createMemory,
    updateMemory: mocks.updateMemory,
    deleteMemory: mocks.deleteMemory,
    setMemoryCover: mocks.setMemoryCover,
    removeMemoryCover: mocks.removeMemoryCover
  },
  memoryMediaApi: {
    listMemoryMedia: mocks.listMemoryMedia,
    updateMemoryMediaMetadataBatch: mocks.updateMemoryMediaMetadataBatch
  },
  publicSharesApi: {
    getPublicShareMemoryHeader: mocks.getPublicShareMemoryHeader
  }
}))

vi.mock('../shared/api/httpClient', () => ({
  callApi: mocks.callApi,
  uploadBlob: mocks.uploadBlob
}))

describe('memoryApi facade', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('maps folder and path based memory lookups through generatedCall', async () => {
    mocks.listMemoriesByFolder.mockResolvedValue([memoryResponse('trip')])
    mocks.getMemoryByPath.mockResolvedValue(memoryResponse('trip'))

    const memories = await listMemoriesByFolder('tenant', '/travel')
    const memory = await getMemoryByPath('tenant', '/travel/trip')

    expect(memories[0].path).toBe('/trip')
    expect(memory.id).toBe('mem-trip')
    expect(mocks.listMemoriesByFolder).toHaveBeenCalledWith({
      tenantName: 'tenant',
      folderPath: '/travel'
    })
    expect(mocks.getMemoryByPath).toHaveBeenCalledWith({
      tenantName: 'tenant',
      path: '/travel/trip'
    })
    expect(mocks.generatedCall).toHaveBeenCalledTimes(2)
  })

  it('normalizes optional memory fields to null', async () => {
    mocks.listMemories.mockResolvedValue([
      {
        id: 'mem-1',
        name: 'trip',
        displayName: 'Trip',
        path: '/trip',
        startDate: '2026-05-03',
        endDate: '2026-05-05',
        childCount: 0,
        mediaCount: 0,
        favorite: true
      }
    ])

    const memories = await listMemories('tenant')

    expect(memories).toEqual([
      {
        id: 'mem-1',
        name: 'trip',
        displayName: 'Trip',
        path: '/trip',
        parentId: null,
        coverMediaId: null,
        coverUrl: null,
        coverCrop: null,
        startDate: '2026-05-03',
        endDate: '2026-05-05',
        childCount: 0,
        mediaCount: 0,
        favorite: true,
        scope: null
      }
    ])
    expect(mocks.listMemories).toHaveBeenCalledWith({ tenantName: 'tenant' })
    expect(mocks.generatedCall).toHaveBeenCalledTimes(1)
  })

  it('normalizes memory header fields and forwards generated calls', async () => {
    mocks.getMemoryHeader.mockResolvedValue({
      id: 'mem-1',
      displayName: 'Trip',
      path: '/trip',
      startDate: '2026-05-03',
      endDate: '2026-05-05',
      directMediaCount: 2,
      descendantMediaCount: 3,
      childMemoryCount: 1,
      favorite: true,
      scope: { type: 'TENANT' },
      participants: []
    })

    const header = await getMemoryHeader('tenant', 'mem-1')

    expect(header).toEqual({
      id: 'mem-1',
      displayName: 'Trip',
      path: '/trip',
      coverUrl: null,
      coverCrop: null,
      startDate: '2026-05-03',
      endDate: '2026-05-05',
      directMediaCount: 2,
      descendantMediaCount: 3,
      childMemoryCount: 1,
      favorite: true,
      scope: { type: 'TENANT' },
      participants: []
    })
    expect(mocks.getMemoryHeader).toHaveBeenCalledWith({
      tenantName: 'tenant',
      memoryId: 'mem-1'
    })
  })

  it('normalizes public share memory header without scope', async () => {
    mocks.getPublicShareMemoryHeader.mockResolvedValue({
      id: 'mem-1',
      displayName: 'Trip',
      path: '/',
      startDate: '2026-05-03',
      endDate: '2026-05-05',
      directMediaCount: 1,
      descendantMediaCount: 0,
      childMemoryCount: 0
    })

    const header = await getPublicShareMemoryHeader('shr-1', 'mem-1')

    expect(header.scope).toBeNull()
    expect(header.favorite).toBe(false)
    expect(header.coverUrl).toBeNull()
    expect(header.directMediaCount).toBe(1)
    expect(mocks.getPublicShareMemoryHeader).toHaveBeenCalledWith({
      shareId: 'shr-1',
      memoryId: 'mem-1'
    })
  })

  it('maps favorite toggles through generated clients', async () => {
    mocks.favoriteMemory.mockResolvedValue({ ...memoryResponse('trip'), favorite: true })
    mocks.unfavoriteMemory.mockResolvedValue({ ...memoryResponse('trip'), favorite: false })

    const favorited = await favoriteMemory('tenant', 'mem-trip')
    const unfavorited = await unfavoriteMemory('tenant', 'mem-trip')

    expect(favorited.favorite).toBe(true)
    expect(unfavorited.favorite).toBe(false)
    expect(mocks.favoriteMemory).toHaveBeenCalledWith({ tenantName: 'tenant', memoryId: 'mem-trip' })
    expect(mocks.unfavoriteMemory).toHaveBeenCalledWith({ tenantName: 'tenant', memoryId: 'mem-trip' })
  })

  it('maps create and update request null values to generated undefined fields', async () => {
    mocks.createMemory.mockResolvedValue(memoryResponse('created'))
    mocks.updateMemory.mockResolvedValue(memoryResponse('updated'))

    await createMemory('tenant', {
      name: 'created',
      displayName: 'Created',
      startDate: '2026-05-03',
      endDate: '2026-05-05',
      parentId: null,
      folderPath: null,
      scope: null
    })
    await updateMemory('tenant', 'mem-1', {
      parentId: null,
      name: null,
      displayName: 'Updated',
      startDate: '2026-05-04',
      endDate: '2026-05-06'
    })

    expect(mocks.createMemory).toHaveBeenCalledWith({
      tenantName: 'tenant',
      createMemoryRequest: {
        name: 'created',
        displayName: 'Created',
        startDate: '2026-05-03',
        endDate: '2026-05-05',
        parentId: undefined,
        folderPath: undefined,
        scope: undefined
      }
    })
    expect(mocks.updateMemory).toHaveBeenCalledWith({
      tenantName: 'tenant',
      memoryId: 'mem-1',
      updateMemoryRequest: {
        parentId: undefined,
        name: undefined,
        displayName: 'Updated',
        startDate: '2026-05-04',
        endDate: '2026-05-06'
      }
    })
  })

  it('normalizes memory media fields and forwards includeChildren', async () => {
    mocks.listMemoryMedia.mockResolvedValue([
      {
        id: 'media-1',
        memoryId: 'mem-1',
        title: 'Photo',
        uploader: { type: 'USER', displayName: 'Kevin', userId: 1 },
        createdAt: '2026-01-01T00:00:00Z',
        capturedAtOverride: false,
        mimeType: 'image/jpeg',
        sizeBytes: 12,
        contentUrl: '/content'
      }
    ])

    const media = await listMemoryMedia('tenant', 'mem-1', true)

    expect(media[0]).toMatchObject({
      id: 'media-1',
      capturedAt: null,
      previewUrl: null,
      thumbnailUrl: null,
      cameraMake: null,
      cameraModel: null,
      lensModel: null,
      imageWidth: null,
      imageHeight: null,
      gpsLatitude: null,
      gpsLongitude: null
    })
    expect(mocks.listMemoryMedia).toHaveBeenCalledWith({
      tenantName: 'tenant',
      memoryId: 'mem-1',
      includeChildren: true
    })
  })

  it('long-polls for new memory media with the updated cursor', async () => {
    mocks.callApi.mockResolvedValue({
      media: [
        {
          id: 'media-2',
          memoryId: 'mem-1',
          title: 'Fresh',
          uploader: { type: 'USER', displayName: 'Kevin', userId: 1 },
          createdAt: '2026-01-01T00:01:00Z',
          capturedAtOverride: false,
          mimeType: 'image/jpeg',
          sizeBytes: 12,
          contentUrl: '/content'
        }
      ]
    })
    const controller = new AbortController()

    const updates = await waitForNewMemoryMedia(
      'tenant',
      'mem-1',
      true,
      '2026-01-01T00:00:00Z',
      'media-1',
      controller.signal
    )

    expect(updates.media[0].capturedAt).toBeNull()
    expect(mocks.callApi).toHaveBeenCalledWith(
      '/api/tenant/memories/mem-1/media/updates?includeChildren=true&afterUpdatedAt=2026-01-01T00%3A00%3A00Z&afterMediaId=media-1',
      { signal: controller.signal }
    )
  })

  it('uploads memory media through a resumable chunk session without an empty optional title', async () => {
    const file = new File(['image-data-12345'], 'photo.jpg', { type: 'image/jpeg' })
    mocks.callApi
      .mockResolvedValueOnce({ uploadId: 'upl-1', chunkSizeBytes: 10 })
      .mockResolvedValueOnce({
        uploadId: 'upl-1',
        memoryId: 'mem-1',
        filename: 'photo.jpg',
        mimeType: 'image/jpeg',
        sizeBytes: file.size,
        chunkSizeBytes: 5,
        uploadedChunks: [],
        uploadedBytes: 0,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z'
      })
      .mockResolvedValueOnce({
        id: 'media-1',
        memoryId: 'mem-1',
        title: 'Photo',
        uploader: { type: 'USER', displayName: 'Kevin', userId: 1 },
        createdAt: '2026-01-01T00:00:00Z',
        capturedAtOverride: false,
        mimeType: 'image/jpeg',
        sizeBytes: 12,
        contentUrl: '/content'
      })

    await uploadMemoryMedia('tenant', 'mem-1', { file, title: null })

    expect(mocks.callApi).toHaveBeenNthCalledWith(
      1,
      '/api/tenant/memories/mem-1/media/uploads',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          filename: 'photo.jpg',
          mimeType: 'image/jpeg',
          sizeBytes: file.size
        })
      })
    )
    expect(mocks.uploadBlob).toHaveBeenNthCalledWith(
      1,
      '/api/tenant/memories/mem-1/media/uploads/upl-1/chunks/0',
      expect.any(Blob),
      'application/octet-stream',
      expect.any(Function),
      undefined
    )
    expect(mocks.uploadBlob).toHaveBeenNthCalledWith(
      2,
      '/api/tenant/memories/mem-1/media/uploads/upl-1/chunks/1',
      expect.any(Blob),
      'application/octet-stream',
      expect.any(Function),
      undefined
    )
    expect(mocks.uploadBlob).toHaveBeenNthCalledWith(
      3,
      '/api/tenant/memories/mem-1/media/uploads/upl-1/chunks/2',
      expect.any(Blob),
      'application/octet-stream',
      expect.any(Function),
      undefined
    )
    expect(mocks.callApi).toHaveBeenLastCalledWith(
      '/api/tenant/memories/mem-1/media/uploads/upl-1/complete',
      { method: 'POST' }
    )
  })

  it('updates memory media metadata in batches and normalizes returned media', async () => {
    mocks.updateMemoryMediaMetadataBatch.mockResolvedValue({
      media: [
        {
          id: 'media-1',
          memoryId: 'mem-1',
          title: 'Photo',
          uploader: { type: 'USER', displayName: 'Kevin', userId: 1 },
          createdAt: '2026-01-01T00:00:00Z',
          capturedAt: '2026-01-01T01:00:00Z',
          capturedAtOverride: true,
          mimeType: 'image/jpeg',
          sizeBytes: 12,
          contentUrl: '/content'
        }
      ]
    })

    const media = await updateMemoryMediaMetadataBatch('tenant', 'mem-1', {
      mediaIds: ['media-1'],
      capturedAtOffsetSeconds: 3600
    })

    expect(media[0]).toMatchObject({
      id: 'media-1',
      capturedAt: '2026-01-01T01:00:00Z',
      previewUrl: null,
      thumbnailUrl: null
    })
    expect(mocks.updateMemoryMediaMetadataBatch).toHaveBeenCalledWith({
      tenantName: 'tenant',
      memoryId: 'mem-1',
      updateMemoryMediaMetadataBatchRequest: {
        mediaIds: ['media-1'],
        capturedAtOffsetSeconds: 3600
      }
    })
  })

  it('maps memory delete and cover operations through generated clients', async () => {
    const file = new File(['cover'], 'cover.jpg', { type: 'image/jpeg' })
    mocks.deleteMemory.mockResolvedValue(undefined)
    mocks.setMemoryCover.mockResolvedValue({
      ...memoryResponse('updated'),
      coverUrl: '/api/tenant/memories/mem-1/cover/content'
    })
    mocks.removeMemoryCover.mockResolvedValue(memoryResponse('updated'))

    await deleteMemory('tenant', 'mem-1')
    const covered = await setMemoryCover('tenant', 'mem-1', {
      file,
      cropX: 0,
      cropY: 0,
      cropWidth: 1,
      cropHeight: 0.5625
    })
    const uncovered = await removeMemoryCover('tenant', 'mem-1')

    expect(mocks.deleteMemory).toHaveBeenCalledWith({ tenantName: 'tenant', memoryId: 'mem-1' })
    expect(mocks.setMemoryCover).toHaveBeenCalledWith({
      tenantName: 'tenant',
      memoryId: 'mem-1',
      file,
      cropX: 0,
      cropY: 0,
      cropWidth: 1,
      cropHeight: 0.5625,
      mediaId: undefined
    })
    expect(mocks.removeMemoryCover).toHaveBeenCalledWith({
      tenantName: 'tenant',
      memoryId: 'mem-1'
    })
    expect(covered.coverUrl).toBe('/api/tenant/memories/mem-1/cover/content')
    expect(uncovered.coverMediaId).toBeNull()
    expect(uncovered.coverUrl).toBeNull()
  })
})

function memoryResponse(name: string) {
  return {
    id: `mem-${name}`,
    name,
    displayName: name,
    path: `/${name}`,
    startDate: '2026-05-03',
    endDate: '2026-05-05',
    childCount: 0,
    mediaCount: 0,
    favorite: false
  }
}
