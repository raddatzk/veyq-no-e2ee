// Reactive favorites state for the shell (cr-0031). Holds the caller's pinned-Memory id set for the active
// scope, decrypted on device (see features/memory/favorites.ts). Toggling is optimistic — the UI updates at
// once and the whole set is re-sealed + PUT; a failed write reverts. Cleared on sign-out / re-lock.
import { reactive } from 'vue'
import { loadFavorites, saveFavorites } from '../features/memory/favorites'

export const favoritesStore = reactive({
  ids: new Set<string>(),
  loaded: false,
})

export function isFavorite(nodeId: string): boolean {
  return favoritesStore.ids.has(nodeId)
}

/** Load + decrypt the pinned set for the active scope (once per space open). */
export async function loadFavoritesFor(scopeId: string, scopeKey: Uint8Array): Promise<void> {
  favoritesStore.ids = await loadFavorites(scopeId, scopeKey)
  favoritesStore.loaded = true
}

/** Toggle a Memory's pinned state — optimistic, reverts on a failed write. */
export async function toggleFavorite(nodeId: string, scopeId: string, scopeKey: Uint8Array): Promise<void> {
  const next = new Set(favoritesStore.ids)
  next.has(nodeId) ? next.delete(nodeId) : next.add(nodeId)
  favoritesStore.ids = next
  try {
    await saveFavorites(scopeId, scopeKey, next)
  } catch {
    const reverted = new Set(next)
    reverted.has(nodeId) ? reverted.delete(nodeId) : reverted.add(nodeId)
    favoritesStore.ids = reverted
  }
}

export function clearFavorites(): void {
  favoritesStore.ids = new Set()
  favoritesStore.loaded = false
}
