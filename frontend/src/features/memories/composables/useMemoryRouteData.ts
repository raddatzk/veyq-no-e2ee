import { computed, onScopeDispose, ref, watch, type ComputedRef } from 'vue'
import {
  getMemoryNavigationContext,
  getMemoryHeader,
  getMemoryJournal,
  listChildMemories,
  listMemoryMedia,
  listMemoryMediaBuckets,
  listMemoryPlaylists,
  listMemoryTracks,
  waitForNewMemoryMedia,
  type Memory,
  type MemoryHeader,
  type MemoryJournal,
  type MemoryMedia,
  type MemoryMediaBucket,
  type MemoryNavigationContextResponse,
  type MemoryPlaylist,
  type MemoryTrack
} from '../api'
import { getErrorMessage } from '../../../shared/utils/errors'
import { mediaSetHash } from '../utils/mediaSetHash'

export type MemoryRouteKind = 'folder' | 'memory'
export type MemoryRouteBreadcrumb = {
  path: string
  label: string
}

type MediaUpdatedCursor = {
  updatedAt: string
  mediaId: string
}

export function useMemoryRouteData(
  tenantName: ComputedRef<string>,
  routePath: ComputedRef<string>
) {
  const kind = ref<MemoryRouteKind>('folder')
  const navigation = ref<MemoryNavigationContextResponse | null>(null)
  const memories = ref<Memory[]>([])
  const memory = ref<Memory | null>(null)
  const memoryHeader = ref<MemoryHeader | null>(null)
  const memoryJournal = ref<MemoryJournal | null>(null)
  const memoryBreadcrumbs = ref<MemoryRouteBreadcrumb[]>([])
  const childMemories = ref<Memory[]>([])
  const media = ref<MemoryMedia[]>([])
  const mediaBuckets = ref<MemoryMediaBucket[]>([])
  const mediaLoading = ref(false)
  const playlists = ref<MemoryPlaylist[]>([])
  const tracks = ref<MemoryTrack[]>([])
  const loading = ref(false)
  const error = ref('')
  let loadSequence = 0
  let mediaUpdatesController: AbortController | null = null

  const isMemoryRoute = computed(() => kind.value === 'memory' && memory.value !== null)
  const currentFolderPath = computed(() => navigation.value?.location.path || routePath.value || '/')

  watch(
    [tenantName, routePath],
    () => {
      void load()
    },
    { immediate: true }
  )

  onScopeDispose(stopMemoryMediaUpdates)

  async function load() {
    if (!tenantName.value) return
    const sequence = ++loadSequence
    stopMemoryMediaUpdates()
    loading.value = true
    error.value = ''
    navigation.value = null
    memories.value = []
    memory.value = null
    memoryHeader.value = null
    memoryJournal.value = null
    memoryBreadcrumbs.value = []
    childMemories.value = []
    media.value = []
    mediaBuckets.value = []
    mediaLoading.value = false
    playlists.value = []
    tracks.value = []

    try {
      await loadRouteContext(sequence)
    } catch (unknownError) {
      if (sequence !== loadSequence) return
      error.value = getErrorMessage(unknownError, 'Ansicht konnte nicht geladen werden')
    } finally {
      if (sequence === loadSequence) loading.value = false
    }
  }

  async function loadRouteContext(sequence: number) {
    const loadedNavigationContext = await getMemoryNavigationContext(tenantName.value, routePath.value)
    if (sequence !== loadSequence) return
    if (loadedNavigationContext.location.kind === 'MEMORY' && loadedNavigationContext.memory) {
      const loadedMemory = loadedNavigationContext.memory
      kind.value = 'memory'
      memory.value = loadedMemory
      memoryBreadcrumbs.value = loadedNavigationContext.breadcrumbs.map((breadcrumb) => ({
        path: breadcrumb.path,
        label: breadcrumb.label
      }))
      mediaLoading.value = true
      void loadMemoryRouteDetails(sequence, loadedMemory)
      return
    }
    kind.value = 'folder'
    navigation.value = loadedNavigationContext
    memories.value = loadedNavigationContext.memories
  }

  async function loadMemoryRouteDetails(sequence: number, loadedMemory: Memory) {
    await Promise.all([
      loadMemoryDetailPart(sequence, () => getMemoryHeader(tenantName.value, loadedMemory.id), (header) => {
        memoryHeader.value = header
      }),
      loadMemoryDetailPart(sequence, () => getMemoryJournal(tenantName.value, loadedMemory.id), (journal) => {
        memoryJournal.value = journal
      }),
      loadMemoryDetailPart(sequence, () => listChildMemories(tenantName.value, loadedMemory.id), (children) => {
        childMemories.value = children
      }),
      loadMemoryDetailPart(sequence, () => listMemoryMedia(tenantName.value, loadedMemory.id, true), (memoryMedia) => {
        media.value = memoryMedia
        mediaLoading.value = false
        startMemoryMediaUpdates(sequence, loadedMemory)
      }, () => {
        mediaLoading.value = false
      }),
      loadMemoryDetailPart(sequence, () => listMemoryMediaBuckets(tenantName.value, loadedMemory.id, true), (buckets) => {
        mediaBuckets.value = buckets
      }),
      loadMemoryDetailPart(sequence, () => listMemoryTracks(tenantName.value, loadedMemory.id), (memoryTracks) => {
        tracks.value = memoryTracks
      }),
      loadMemoryDetailPart(sequence, () => listMemoryPlaylists(tenantName.value, loadedMemory.id), (memoryPlaylists) => {
        playlists.value = memoryPlaylists
      })
    ])
  }

  async function loadMemoryDetailPart<T>(
    sequence: number,
    loadPart: () => Promise<T>,
    applyPart: (value: T) => void,
    finalize?: () => void
  ) {
    try {
      const value = await loadPart()
      if (sequence === loadSequence) applyPart(value)
    } catch (unknownError) {
      if (sequence === loadSequence) {
        error.value = getErrorMessage(unknownError, 'Ansicht konnte nicht vollständig geladen werden')
      }
    } finally {
      if (sequence === loadSequence) finalize?.()
    }
  }

  function startMemoryMediaUpdates(sequence: number, current: Memory) {
    stopMemoryMediaUpdates()
    const controller = new AbortController()
    mediaUpdatesController = controller
    void watchMemoryMediaUpdates(sequence, current, controller)
  }

  function stopMemoryMediaUpdates() {
    mediaUpdatesController?.abort()
    mediaUpdatesController = null
  }

  async function watchMemoryMediaUpdates(
    sequence: number,
    current: Memory,
    controller: AbortController
  ) {
    while (!controller.signal.aborted && sequence === loadSequence && memory.value?.id === current.id) {
      const cursor = newestUpdatedAtCursor(media.value)
      try {
        const knownMediaSetHash = await mediaSetHash(media.value.map((item) => item.id))
        if (controller.signal.aborted || sequence !== loadSequence || memory.value?.id !== current.id) return
        const updates = await waitForNewMemoryMedia(
          tenantName.value,
          current.id,
          true,
          cursor?.updatedAt,
          cursor?.mediaId,
          knownMediaSetHash,
          controller.signal
        )
        if (controller.signal.aborted || sequence !== loadSequence || memory.value?.id !== current.id) return
        const mergedMedia = updates.media.length > 0 ? mergeMediaById(media.value, updates.media) : media.value
        const nextMedia = removeMissingMedia(mergedMedia, updates.currentMediaIds)
        const removedMedia = nextMedia.length !== mergedMedia.length
        if (updates.media.length > 0 || removedMedia) {
          media.value = chronologicallySortedMedia(nextMedia)
          const [header, buckets] = await Promise.all([
            getMemoryHeader(tenantName.value, current.id),
            listMemoryMediaBuckets(tenantName.value, current.id, true)
          ])
          if (controller.signal.aborted || sequence !== loadSequence || memory.value?.id !== current.id) return
          memoryHeader.value = header
          mediaBuckets.value = buckets
        }
      } catch (unknownError) {
        if (controller.signal.aborted) return
        if (sequence === loadSequence) {
          error.value = getErrorMessage(unknownError, 'Ansicht konnte nicht vollständig geladen werden')
        }
        await delay(5_000, controller.signal)
      }
    }
  }

  return {
    childMemories,
    currentFolderPath,
    error,
    navigation,
    isMemoryRoute,
    kind,
    load,
    loading,
    media,
    mediaBuckets,
    mediaLoading,
    playlists,
    tracks,
    memories,
    memory,
    memoryBreadcrumbs,
    memoryJournal,
    memoryHeader
  }
}

function newestUpdatedAtCursor(media: MemoryMedia[]): MediaUpdatedCursor | null {
  const newest = [...media]
    .filter((item) => item.updatedAt)
    .sort((left, right) => {
      const updatedAtOrder = left.updatedAt.localeCompare(right.updatedAt)
      return updatedAtOrder || left.id.localeCompare(right.id)
    })
    .at(-1)
  return newest ? { updatedAt: newest.updatedAt, mediaId: newest.id } : null
}

function mergeMediaById(current: MemoryMedia[], updates: MemoryMedia[]): MemoryMedia[] {
  const byId = new Map<string, MemoryMedia>()
  current.forEach((item) => byId.set(item.id, item))
  updates.forEach((item) => byId.set(item.id, item))
  return [...byId.values()]
}

function removeMissingMedia(current: MemoryMedia[], currentMediaIds?: string[]): MemoryMedia[] {
  if (!currentMediaIds) return current
  const currentIdSet = new Set(currentMediaIds)
  return current.filter((item) => currentIdSet.has(item.id))
}

function chronologicallySortedMedia(media: MemoryMedia[]): MemoryMedia[] {
  return [...media].sort((left, right) => {
    const leftSortTime = left.capturedAt || left.createdAt
    const rightSortTime = right.capturedAt || right.createdAt
    const sortTimeOrder = leftSortTime.localeCompare(rightSortTime)
    if (sortTimeOrder !== 0) return sortTimeOrder
    const createdAtOrder = left.createdAt.localeCompare(right.createdAt)
    return createdAtOrder || left.id.localeCompare(right.id)
  })
}

function delay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timeout = window.setTimeout(resolve, milliseconds)
    signal.addEventListener('abort', () => {
      window.clearTimeout(timeout)
      resolve()
    }, { once: true })
  })
}
