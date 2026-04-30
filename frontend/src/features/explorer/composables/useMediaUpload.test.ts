import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  completeMediaUpload,
  createMediaUpload,
  getMediaUpload,
  uploadMediaChunk
} from '../api'
import { uploadMedia } from './useMediaUpload'

vi.mock('../api', () => ({
  completeMediaUpload: vi.fn(),
  createMediaUpload: vi.fn(),
  getMediaUpload: vi.fn(),
  uploadMediaChunk: vi.fn()
}))

const createMediaUploadMock = vi.mocked(createMediaUpload)
const getMediaUploadMock = vi.mocked(getMediaUpload)
const uploadMediaChunkMock = vi.mocked(uploadMediaChunk)
const completeMediaUploadMock = vi.mocked(completeMediaUpload)

describe('uploadMedia', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    completeMediaUploadMock.mockResolvedValue({
      id: 'media-1',
      title: 'Upload',
      albumPath: '/photos',
      ownerUserId: 1,
      createdAt: '2026-01-01T00:00:00Z',
      mimeType: 'video/mp4',
      sizeBytes: 8,
      contentUrl: '/content'
    })
  })

  it('uploads chunks and reports progress', async () => {
    createMediaUploadMock.mockResolvedValue({ uploadId: 'upload-1', chunkSizeBytes: 4 })
    const progress = vi.fn()

    const result = await uploadMedia('friends', {
      albumPath: '/photos',
      file: new File([new Uint8Array([1, 2, 3, 4, 5, 6])], 'clip.mp4'),
      onProgress: progress
    })

    expect(result.id).toBe('media-1')
    expect(uploadMediaChunkMock).toHaveBeenCalledTimes(2)
    expect(uploadMediaChunkMock.mock.calls.map((call) => call[1])).toEqual([0, 1])
    expect(progress).toHaveBeenCalledWith(4, 6)
    expect(progress).toHaveBeenCalledWith(6, 6)
    expect(completeMediaUploadMock).toHaveBeenCalledWith('friends', 'upload-1')
  })

  it('resumes existing sessions without re-uploading completed chunks', async () => {
    getMediaUploadMock.mockResolvedValue({
      uploadId: 'upload-2',
      tenantName: 'friends',
      tenantDisplayName: 'Friends',
      albumPath: '/photos',
      filename: 'clip.mp4',
      mimeType: 'video/mp4',
      sizeBytes: 8,
      title: null,
      chunkSizeBytes: 4,
      uploadedChunks: [0],
      uploadedBytes: 4,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z'
    })

    await uploadMedia('friends', {
      albumPath: '/photos',
      existingUploadId: 'upload-2',
      file: new File([new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8])], 'clip.mp4')
    })

    expect(createMediaUploadMock).not.toHaveBeenCalled()
    expect(uploadMediaChunkMock).toHaveBeenCalledTimes(1)
    expect(uploadMediaChunkMock).toHaveBeenCalledWith('upload-2', 1, expect.any(Blob), { signal: undefined })
  })
})
