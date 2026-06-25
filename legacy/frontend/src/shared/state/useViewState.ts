import { readonly, ref } from 'vue'

// Shared browse-grid density preference. The topbar toggles it; TenantView reads it to switch the
// folder/memory grids between roomy (default) and compact. Persisted so the choice survives reloads.
const VIEW_STORAGE_KEY = 'veyq:compact-view'
const compactView = ref<boolean>(readStored())

export function useViewState() {
  return { compactView: readonly(compactView), toggleCompactView }
}

export function toggleCompactView() {
  compactView.value = !compactView.value
  storage()?.setItem(VIEW_STORAGE_KEY, compactView.value ? '1' : '0')
}

function readStored(): boolean {
  return storage()?.getItem(VIEW_STORAGE_KEY) === '1'
}

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null
  }
}
