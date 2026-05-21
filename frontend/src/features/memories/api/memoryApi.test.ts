import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createMemory,
  listMemories,
  listMemoryMedia,
  updateMemory,
  uploadMemoryMedia
} from './memoryApi'

const mocks = vi.hoisted(() => ({
  generatedCall: vi.fn((request: Promise<unknown>) => request),
  listMemories: vi.fn(),
  createMemory: vi.fn(),
  updateMemory: vi.fn(),
  listMemoryMedia: vi.fn(),
  uploadMemoryMedia: vi.fn()
}))

vi.mock('../../../api/generatedClient', () => ({
  generatedCall: mocks.generatedCall,
  memoriesApi: {
    listMemories: mocks.listMemories,
    createMemory: mocks.createMemory,
    updateMemory: mocks.updateMemory
  },
  memoryMediaApi: {
    listMemoryMedia: mocks.listMemoryMedia,
    uploadMemoryMedia: mocks.uploadMemoryMedia
  }
}))

describe('memoryApi facade', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('normalizes optional memory fields to null', async () => {
    mocks.listMemories.mockResolvedValue([
      {
        id: 'mem-1',
        name: 'trip',
        displayName: 'Trip',
        path: '/trip',
        childCount: 0,
        mediaCount: 0
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
        childCount: 0,
        mediaCount: 0,
        scope: null
      }
    ])
    expect(mocks.listMemories).toHaveBeenCalledWith({ tenantName: 'tenant' })
    expect(mocks.generatedCall).toHaveBeenCalledTimes(1)
  })

  it('maps create and update request null values to generated undefined fields', async () => {
    mocks.createMemory.mockResolvedValue(memoryResponse('created'))
    mocks.updateMemory.mockResolvedValue(memoryResponse('updated'))

    await createMemory('tenant', {
      name: 'created',
      displayName: 'Created',
      parentId: null,
      scope: null
    })
    await updateMemory('tenant', 'mem-1', {
      parentId: null,
      name: null,
      displayName: 'Updated'
    })

    expect(mocks.createMemory).toHaveBeenCalledWith({
      tenantName: 'tenant',
      createMemoryRequest: {
        name: 'created',
        displayName: 'Created',
        parentId: undefined,
        scope: undefined
      }
    })
    expect(mocks.updateMemory).toHaveBeenCalledWith({
      tenantName: 'tenant',
      memoryId: 'mem-1',
      updateMemoryRequest: {
        parentId: undefined,
        name: undefined,
        displayName: 'Updated'
      }
    })
  })

  it('normalizes memory media fields and forwards includeChildren', async () => {
    mocks.listMemoryMedia.mockResolvedValue([
      {
        id: 'media-1',
        memoryId: 'mem-1',
        title: 'Photo',
        ownerUserId: 1,
        uploaderDisplayName: 'Kevin',
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

  it('uploads memory media using undefined for an empty optional title', async () => {
    const file = new File(['image'], 'photo.jpg', { type: 'image/jpeg' })
    mocks.uploadMemoryMedia.mockResolvedValue({
      id: 'media-1',
      memoryId: 'mem-1',
      title: 'Photo',
      ownerUserId: 1,
      uploaderDisplayName: 'Kevin',
      createdAt: '2026-01-01T00:00:00Z',
      capturedAtOverride: false,
      mimeType: 'image/jpeg',
      sizeBytes: 12,
      contentUrl: '/content'
    })

    await uploadMemoryMedia('tenant', 'mem-1', { file, title: null })

    expect(mocks.uploadMemoryMedia).toHaveBeenCalledWith({
      tenantName: 'tenant',
      memoryId: 'mem-1',
      file,
      title: undefined
    })
  })
})

function memoryResponse(name: string) {
  return {
    id: `mem-${name}`,
    name,
    displayName: name,
    path: `/${name}`,
    childCount: 0,
    mediaCount: 0
  }
}
