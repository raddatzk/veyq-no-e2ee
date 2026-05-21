import { computed, ref, watch, type ComputedRef } from 'vue'
import {
  getMemoryByPath,
  getMemoryNavigation,
  listChildMemories,
  listMemoriesByFolder,
  listMemoryMedia,
  type Memory,
  type MemoryMedia,
  type MemoryNavigationResponse
} from '../api'
import { getErrorMessage, isApiStatus } from '../../../shared/utils/errors'

export type MemoryRouteKind = 'folder' | 'memory'

export function useMemoryRouteData(
  tenantName: ComputedRef<string>,
  routePath: ComputedRef<string>
) {
  const kind = ref<MemoryRouteKind>('folder')
  const navigation = ref<MemoryNavigationResponse | null>(null)
  const memories = ref<Memory[]>([])
  const memory = ref<Memory | null>(null)
  const childMemories = ref<Memory[]>([])
  const media = ref<MemoryMedia[]>([])
  const loading = ref(false)
  const error = ref('')
  let loadSequence = 0

  const isMemoryRoute = computed(() => kind.value === 'memory' && memory.value !== null)
  const currentFolderPath = computed(() => navigation.value?.location.path || routePath.value || '/')

  watch(
    [tenantName, routePath],
    () => {
      void load()
    },
    { immediate: true }
  )

  async function load() {
    if (!tenantName.value) return
    const sequence = ++loadSequence
    loading.value = true
    error.value = ''
    navigation.value = null
    memories.value = []
    memory.value = null
    childMemories.value = []
    media.value = []

    try {
      if (routePath.value !== '/') {
        const loadedMemory = await tryLoadMemoryRoute(sequence)
        if (loadedMemory) return
      }
      await loadFolderRoute(sequence)
    } catch (unknownError) {
      if (sequence !== loadSequence) return
      error.value = getErrorMessage(unknownError, 'Ansicht konnte nicht geladen werden')
    } finally {
      if (sequence === loadSequence) loading.value = false
    }
  }

  async function tryLoadMemoryRoute(sequence: number): Promise<boolean> {
    try {
      const loadedMemory = await getMemoryByPath(tenantName.value, routePath.value)
      if (sequence !== loadSequence) return true
      kind.value = 'memory'
      memory.value = loadedMemory
      const [children, memoryMedia] = await Promise.all([
        listChildMemories(tenantName.value, loadedMemory.id),
        listMemoryMedia(tenantName.value, loadedMemory.id, false)
      ])
      if (sequence !== loadSequence) return true
      childMemories.value = children
      media.value = memoryMedia
      return true
    } catch (unknownError) {
      if (isApiStatus(unknownError, 404)) return false
      throw unknownError
    }
  }

  async function loadFolderRoute(sequence: number) {
    const [loadedNavigation, loadedMemories] = await Promise.all([
      getMemoryNavigation(tenantName.value, routePath.value),
      listMemoriesByFolder(tenantName.value, routePath.value)
    ])
    if (sequence !== loadSequence) return
    kind.value = 'folder'
    navigation.value = loadedNavigation
    memories.value = loadedMemories
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
    memories,
    memory
  }
}
