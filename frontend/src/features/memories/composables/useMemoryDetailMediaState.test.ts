import { describe, expect, it } from 'vitest'
import { computed, ref } from 'vue'
import { useMemoryDetailMediaState } from './useMemoryDetailMediaState'
import type { MemoryMedia, MemoryMediaBucket } from '../api'

const uploader = { type: 'USER' as const, displayName: 'Kevin', userId: 1 }

function media(id: string, bucketId?: string, memoryId = 'memory-1'): MemoryMedia {
  return {
    id,
    memoryId,
    bucketId,
    title: id,
    uploader,
    createdAt: '2026-05-31T20:00:00Z',
    capturedAt: null,
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 10,
    contentUrl: `/media/${id}`,
    thumbnailUrl: null,
    previewUrl: null,
    posterUrl: null,
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    imageWidth: null,
    imageHeight: null,
    gpsLatitude: null,
    gpsLongitude: null
  }
}

function bucket(id: string, mediaCount: number, options: Partial<MemoryMediaBucket> = {}): MemoryMediaBucket {
  return {
    id,
    memoryId: 'memory-1',
    displayName: id,
    primary: false,
    uploader,
    mediaCount,
    createdAt: '2026-05-31T20:00:00Z',
    updatedAt: '2026-05-31T20:00:00Z',
    ...options
  }
}

describe('useMemoryDetailMediaState', () => {
  it('deduplicates visible media and updates local counts for uploads and deletes', () => {
    const sourceMedia = ref([media('one', 'bucket-a'), media('one', 'bucket-a')])
    const sourceBuckets = ref([bucket('bucket-a', 1)])
    const state = useMemoryDetailMediaState({
      memoryId: 'memory-1',
      media: sourceMedia,
      mediaBuckets: sourceBuckets,
      totalMediaCount: computed(() => sourceMedia.value.length)
    })

    expect(state.visibleMedia.value.map((item) => item.id)).toEqual(['one'])

    state.addUploadedMedia(media('two', 'bucket-a'))
    expect(state.visibleMedia.value.map((item) => item.id)).toEqual(['one', 'two'])
    expect(state.localTotalMediaCount.value).toBe(3)
    expect(state.visibleMediaBuckets.value.find((item) => item.id === 'bucket-a')?.mediaCount).toBe(2)

    state.markMediaDeleted(media('one', 'bucket-a'))
    expect(state.visibleMedia.value.map((item) => item.id)).toEqual(['two'])
    expect(state.localTotalMediaCount.value).toBe(1)
    expect(state.visibleMediaBuckets.value.find((item) => item.id === 'bucket-a')?.mediaCount).toBe(0)
  })

  it('accepts uploads into visible child buckets even when the media belongs to a child memory', () => {
    const state = useMemoryDetailMediaState({
      memoryId: 'memory-1',
      media: [],
      mediaBuckets: [bucket('child-bucket', 0, { memoryId: 'child-memory' })],
      totalMediaCount: 0
    })

    state.addUploadedMedia(media('child-upload', 'child-bucket', 'child-memory'))

    expect(state.visibleMedia.value.map((item) => item.id)).toEqual(['child-upload'])
    expect(state.visibleMediaBuckets.value.find((item) => item.id === 'child-bucket')?.mediaCount).toBe(1)
  })

  it('applies moves and bucket deletion effects without mutating source data', () => {
    const sourceMedia = ref([media('one', 'bucket-a'), media('two', 'child-bucket')])
    const sourceBuckets = ref([
      bucket('bucket-a', 1, { primary: true }),
      bucket('child-bucket', 1, { parentBucketId: 'bucket-a' }),
      bucket('bucket-b', 0)
    ])
    const state = useMemoryDetailMediaState({
      memoryId: 'memory-1',
      media: sourceMedia,
      mediaBuckets: sourceBuckets,
      totalMediaCount: 2
    })

    state.mergeUpdatedMedia([{ ...sourceMedia.value[0], bucketId: 'bucket-b' }])
    expect(state.visibleMediaBuckets.value.map((item) => [item.id, item.mediaCount])).toEqual([
      ['bucket-a', 0],
      ['child-bucket', 1],
      ['bucket-b', 1]
    ])

    state.applyDeletedBucket('bucket-a')
    expect(state.visibleMedia.value.map((item) => item.id)).toEqual(['one'])
    expect(state.visibleMediaBuckets.value.map((item) => [item.id, item.mediaCount])).toEqual([
      ['bucket-a', 0],
      ['bucket-b', 1]
    ])
    expect(sourceMedia.value).toHaveLength(2)
  })

  it('zeros a deleted primary bucket count even when counted media is not loaded', () => {
    const state = useMemoryDetailMediaState({
      memoryId: 'memory-1',
      media: [],
      mediaBuckets: [bucket('bucket-a', 1, { primary: true })],
      totalMediaCount: 0
    })

    state.applyDeletedBucket('bucket-a')

    expect(state.visibleMedia.value).toEqual([])
    expect(state.visibleMediaBuckets.value.map((item) => [item.id, item.mediaCount])).toEqual([
      ['bucket-a', 0]
    ])
  })

  it('removes a deleted anonymous primary bucket because later uploads recreate it', () => {
    const state = useMemoryDetailMediaState({
      memoryId: 'memory-1',
      media: [],
      mediaBuckets: [
        bucket('bucket-a', 1, {
          primary: true,
          uploader: { type: 'ANONYMOUS', displayName: 'Alex' }
        })
      ],
      totalMediaCount: 0
    })

    state.applyDeletedBucket('bucket-a')

    expect(state.visibleMedia.value).toEqual([])
    expect(state.visibleMediaBuckets.value).toEqual([])
  })

  it('resets optimistic state when the active memory changes', () => {
    const state = useMemoryDetailMediaState({
      memoryId: 'memory-1',
      media: [media('one', 'bucket-a')],
      mediaBuckets: [bucket('bucket-a', 1)],
      totalMediaCount: 1
    })

    state.addUploadedMedia(media('two', 'bucket-a'))
    state.markMediaDeleted(media('one', 'bucket-a'))
    expect(state.visibleMedia.value.map((item) => item.id)).toEqual(['two'])

    state.reset()
    expect(state.visibleMedia.value.map((item) => item.id)).toEqual(['one'])
    expect(state.localTotalMediaCount.value).toBe(1)
  })
})
