import { computed, onBeforeUnmount, ref, watch, type ComputedRef } from 'vue'
import { getExplorer, streamExplorerMedia, type ExplorerMedia, type ExplorerResponse } from '../api'
import { getErrorMessage } from '../../../shared/utils/errors'

export type ExplorerViewMode = 'structure' | 'media'

export function useExplorerData(
  tenantName: ComputedRef<string>,
  explorerPath: ComputedRef<string>
) {
  const explorer = ref<ExplorerResponse | null>(null)
  const media = ref<ExplorerMedia[]>([])
  const loading = ref(false)
  const streaming = ref(false)
  const error = ref('')
  const mediaError = ref('')
  const mediaLoaded = ref(false)
  const streamController = ref<AbortController | null>(null)
  const viewMode = ref<ExplorerViewMode>('structure')
  let loadSequence = 0

  const contextType = computed(() => String(explorer.value?.context.type || '').toLowerCase())
  const isAlbumContext = computed(() => contextType.value === 'album')
  const isLibraryContext = computed(
    () => contextType.value === 'folder' || contextType.value === 'album'
  )
  const canSwitchViews = computed(() => !isAlbumContext.value)
  const activeView = computed<ExplorerViewMode>(() =>
    isAlbumContext.value ? 'media' : viewMode.value
  )

  watch(
    [tenantName, explorerPath],
    () => {
      viewMode.value = 'structure'
      void loadExplorer()
    },
    { immediate: true }
  )

  watch(activeView, updateMediaStreaming)

  onBeforeUnmount(() => {
    abortStream()
  })

  async function loadExplorer() {
    if (!tenantName.value) return
    const sequence = ++loadSequence
    abortStream()
    loading.value = true
    streaming.value = false
    error.value = ''
    mediaError.value = ''
    media.value = []
    mediaLoaded.value = false

    try {
      const response = await getExplorer(tenantName.value, explorerPath.value)
      if (sequence !== loadSequence) return
      explorer.value = response
      updateMediaStreaming()
    } catch (unknownError) {
      if (sequence !== loadSequence) return
      explorer.value = null
      error.value = getErrorMessage(unknownError, 'Explorer konnte nicht geladen werden')
    } finally {
      if (sequence === loadSequence) loading.value = false
    }
  }

  function startMediaStream(sequence: number) {
    if (streaming.value || mediaLoaded.value) return
    const controller = new AbortController()
    streamController.value = controller
    streaming.value = true

    streamExplorerMedia(
      tenantName.value,
      explorerPath.value,
      (item) => {
        if (sequence === loadSequence) media.value = [...media.value, item]
      },
      { signal: controller.signal }
    )
      .catch((unknownError: unknown) => {
        if (controller.signal.aborted || sequence !== loadSequence) return
        mediaError.value = getErrorMessage(unknownError, 'Medien konnten nicht geladen werden')
      })
      .finally(() => {
        if (sequence === loadSequence) {
          streaming.value = false
          mediaLoaded.value = !controller.signal.aborted
        }
      })
  }

  function abortStream() {
    streamController.value?.abort()
    streamController.value = null
  }

  function updateMediaStreaming() {
    if (!explorer.value) return
    if (activeView.value === 'media') {
      startMediaStream(loadSequence)
    } else {
      abortStream()
      streaming.value = false
    }
  }

  return {
    activeView,
    canSwitchViews,
    contextType,
    error,
    explorer,
    isAlbumContext,
    isLibraryContext,
    loadExplorer,
    loading,
    media,
    mediaError,
    streaming,
    viewMode
  }
}
