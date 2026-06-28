import { computed, ref, toValue, type MaybeRefOrGetter } from 'vue'
import type { MemoryMedia, MemoryMediaBucket } from '../api'

type MemoryDetailMediaStateOptions = {
  memoryId: MaybeRefOrGetter<string>
  media: MaybeRefOrGetter<MemoryMedia[]>
  mediaBuckets: MaybeRefOrGetter<MemoryMediaBucket[] | undefined>
  totalMediaCount: MaybeRefOrGetter<number>
}

export function useMemoryDetailMediaState(options: MemoryDetailMediaStateOptions) {
  const mediaOverrides = ref<Record<string, MemoryMedia>>({})
  const uploadedMedia = ref<Record<string, MemoryMedia>>({})
  const deletedMediaIds = ref<Set<string>>(new Set())
  const mediaBucketOverrides = ref<Record<string, MemoryMediaBucket>>({})
  const createdMediaBuckets = ref<Record<string, MemoryMediaBucket>>({})
  const deletedMediaBucketIds = ref<Set<string>>(new Set())

  const uploadedMediaList = computed(() =>
    Object.values(uploadedMedia.value).filter((media) =>
      !toValue(options.media).some((item) => item.id === media.id) && !deletedMediaIds.value.has(media.id)
    )
  )
  const deletedVisibleMediaCount = computed(() =>
    toValue(options.media).filter((media) => deletedMediaIds.value.has(media.id)).length
  )
  const localTotalMediaCount = computed(() =>
    Math.max(0, toValue(options.totalMediaCount) - deletedVisibleMediaCount.value + uploadedMediaList.value.length)
  )
  const visibleMedia = computed(() => {
    const merged = [...toValue(options.media), ...uploadedMediaList.value].filter((media) => !deletedMediaIds.value.has(media.id))
    const mediaById = new Map<string, MemoryMedia>()
    merged.forEach((media) => {
      mediaById.set(media.id, mediaOverrides.value[media.id] || media)
    })
    return [...mediaById.values()]
  })
  const localMediaCountDeltaByBucket = computed(() => {
    const deltas: Record<string, number> = {}
    const addDelta = (bucketId: string | null | undefined, delta: number) => {
      if (!bucketId) return
      deltas[bucketId] = (deltas[bucketId] ?? 0) + delta
    }

    toValue(options.media).forEach((media) => {
      if (deletedMediaIds.value.has(media.id)) {
        addDelta(media.bucketId, -1)
        return
      }

      const override = mediaOverrides.value[media.id]
      if (override && override.bucketId !== media.bucketId) {
        addDelta(media.bucketId, -1)
        addDelta(override.bucketId, 1)
      }
    })

    uploadedMediaList.value.forEach((media) => {
      addDelta((mediaOverrides.value[media.id] || media).bucketId, 1)
    })

    return deltas
  })
  const visibleMediaBuckets = computed(() => {
    const merged = [...(toValue(options.mediaBuckets) ?? []), ...Object.values(createdMediaBuckets.value)]
      .filter((bucket, index, buckets) => buckets.findIndex((item) => item.id === bucket.id) === index)
      .filter((bucket) => !deletedMediaBucketIds.value.has(bucket.id))
    return merged.map((bucket) => {
      const next = mediaBucketOverrides.value[bucket.id] || bucket
      return { ...next, mediaCount: Math.max(0, next.mediaCount + (localMediaCountDeltaByBucket.value[bucket.id] ?? 0)) }
    })
  })

  function reset() {
    uploadedMedia.value = {}
    mediaOverrides.value = {}
    deletedMediaIds.value = new Set()
    mediaBucketOverrides.value = {}
    createdMediaBuckets.value = {}
    deletedMediaBucketIds.value = new Set()
  }

  function mergeUpdatedMedia(updated: MemoryMedia[]) {
    mediaOverrides.value = {
      ...mediaOverrides.value,
      ...Object.fromEntries(updated.map((media) => [media.id, media]))
    }
  }

  function addUploadedMedia(media: MemoryMedia) {
    const uploadedIntoVisibleBucket = Boolean(
      media.bucketId && visibleMediaBuckets.value.some((bucket) => bucket.id === media.bucketId)
    )
    if (media.memoryId !== toValue(options.memoryId) && !uploadedIntoVisibleBucket) return
    deletedMediaIds.value = new Set([...deletedMediaIds.value].filter((mediaId) => mediaId !== media.id))
    uploadedMedia.value = { ...uploadedMedia.value, [media.id]: media }
  }

  function markMediaDeleted(media: MemoryMedia) {
    deletedMediaIds.value = new Set([...deletedMediaIds.value, media.id])
    uploadedMedia.value = Object.fromEntries(
      Object.entries(uploadedMedia.value).filter(([mediaId]) => mediaId !== media.id)
    )
    mediaOverrides.value = Object.fromEntries(
      Object.entries(mediaOverrides.value).filter(([mediaId]) => mediaId !== media.id)
    )
  }

  function addCreatedBucket(bucket: MemoryMediaBucket) {
    createdMediaBuckets.value = { ...createdMediaBuckets.value, [bucket.id]: bucket }
  }

  function mergeUpdatedBucket(bucket: MemoryMediaBucket) {
    mediaBucketOverrides.value = { ...mediaBucketOverrides.value, [bucket.id]: bucket }
  }

  function applyDeletedBucket(bucketId: string) {
    const bucket = visibleMediaBuckets.value.find((candidate) => candidate.id === bucketId)
    const keepDeletedPrimaryBucket = Boolean(bucket?.primary && bucket.uploader.type !== 'ANONYMOUS')

    const deletedBucketIds = new Set([bucketId])
    let changed = true
    while (changed) {
      changed = false
      visibleMediaBuckets.value.forEach((candidate) => {
        if (candidate.parentBucketId && deletedBucketIds.has(candidate.parentBucketId) && !deletedBucketIds.has(candidate.id)) {
          deletedBucketIds.add(candidate.id)
          changed = true
        }
      })
    }
    const bucketsById = new Map(visibleMediaBuckets.value.map((candidate) => [candidate.id, candidate]))
    deletedMediaBucketIds.value = new Set([
      ...deletedMediaBucketIds.value,
      ...[...deletedBucketIds].filter((deletedBucketId) => {
        const deletedBucket = bucketsById.get(deletedBucketId)
        return !deletedBucket?.primary || deletedBucket.uploader.type === 'ANONYMOUS'
      })
    ])

    deletedMediaIds.value = new Set([
      ...deletedMediaIds.value,
      ...visibleMedia.value
        .filter((media) => media.bucketId && deletedBucketIds.has(media.bucketId))
        .map((media) => media.id)
    ])

    if (keepDeletedPrimaryBucket && bucket) {
      mediaBucketOverrides.value = {
        ...mediaBucketOverrides.value,
        [bucket.id]: { ...bucket, mediaCount: 0 }
      }
    }
  }

  return {
    localTotalMediaCount,
    visibleMedia,
    visibleMediaBuckets,
    reset,
    mergeUpdatedMedia,
    addUploadedMedia,
    markMediaDeleted,
    addCreatedBucket,
    mergeUpdatedBucket,
    applyDeletedBucket
  }
}
