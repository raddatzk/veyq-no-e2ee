import { computed, ref } from 'vue'
import type { ExplorerAlbum, ExplorerContext, ExplorerFolder, LibraryItemType } from '../api'

export function useExplorerDialogs() {
  const createType = ref<LibraryItemType>('folder')
  const createOpen = ref(false)
  const editOpen = ref(false)
  const moveOpen = ref(false)
  const shareOpen = ref(false)
  const deleteOpen = ref(false)
  const coverOpen = ref(false)
  const uploadOpen = ref(false)
  const selectedType = ref<LibraryItemType>('folder')
  const selectedItem = ref<ExplorerFolder | ExplorerAlbum | null>(null)
  const selectedAlbum = computed(() =>
    selectedType.value === 'album' ? (selectedItem.value as ExplorerAlbum | null) : null
  )

  function openCreate(type: LibraryItemType) {
    createType.value = type
    createOpen.value = true
  }

  function openEdit(type: LibraryItemType, item: ExplorerFolder | ExplorerAlbum) {
    selectedType.value = type
    selectedItem.value = item
    editOpen.value = true
  }

  function openMove(type: LibraryItemType, item: ExplorerFolder | ExplorerAlbum) {
    selectedType.value = type
    selectedItem.value = item
    moveOpen.value = true
  }

  function openShare(type: LibraryItemType, item: ExplorerFolder | ExplorerAlbum) {
    selectedType.value = type
    selectedItem.value = item
    shareOpen.value = true
  }

  function openDelete(type: LibraryItemType, item: ExplorerFolder | ExplorerAlbum) {
    selectedType.value = type
    selectedItem.value = item
    deleteOpen.value = true
  }

  function openCover(album: ExplorerAlbum) {
    selectedType.value = 'album'
    selectedItem.value = album
    coverOpen.value = true
  }

  function itemFromContext(
    context: ExplorerContext,
    type: LibraryItemType
  ): ExplorerFolder | ExplorerAlbum {
    const name = context.path.split('/').filter(Boolean).at(-1) || ''
    if (type === 'album') {
      return {
        id: context.path,
        name,
        displayName: context.displayName,
        path: context.path,
        coverMediaId: null
      }
    }
    return { id: context.path, name, displayName: context.displayName, path: context.path }
  }

  return {
    coverOpen,
    createOpen,
    createType,
    deleteOpen,
    editOpen,
    itemFromContext,
    moveOpen,
    openCover,
    openCreate,
    openDelete,
    openEdit,
    openMove,
    openShare,
    selectedAlbum,
    selectedItem,
    selectedType,
    shareOpen,
    uploadOpen
  }
}
