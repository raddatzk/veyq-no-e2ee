import { computed, ref } from 'vue'
import type { ExplorerAlbum, ExplorerFolder, ExplorerLocation, LibraryItemType } from '../api'

export function useExplorerDialogs() {
  const createType = ref<LibraryItemType>('folder')
  const createOpen = ref(false)
  const editOpen = ref(false)
  const moveOpen = ref(false)
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

  function itemFromLocation(
    location: ExplorerLocation,
    type: LibraryItemType
  ): ExplorerFolder | ExplorerAlbum {
    const name = location.path.split('/').filter(Boolean).at(-1) || ''
    if (type === 'album') {
      return {
        id: location.path,
        name,
        displayName: location.displayName,
        path: location.path,
        coverMediaId: null
      }
    }
    return {
      id: location.path,
      name,
      displayName: location.displayName,
      path: location.path
    }
  }

  return {
    coverOpen,
    createOpen,
    createType,
    deleteOpen,
    editOpen,
    itemFromLocation,
    moveOpen,
    openCover,
    openCreate,
    openDelete,
    openEdit,
    openMove,
    selectedAlbum,
    selectedItem,
    selectedType,
    uploadOpen
  }
}
