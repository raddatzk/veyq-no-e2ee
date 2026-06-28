// The active scope's decrypted tree, loaded once and shared by the browse + Memory views (cr-0030). Holds
// plaintext node names (decrypted on device) — never key material (K_SC lives in app/activeSpace). Mutations
// (create/rename/delete) call `loadTree()` to refresh.
import { reactive } from 'vue'
import { activeSpace, activeScopeKey } from '../../app/activeSpace'
import { listTree, type TreeNode } from './tree'

export const treeStore = reactive({
  nodes: [] as TreeNode[],
  loaded: false,
  error: false,
})

/** (Re)load the active scope's tree. No-op if no space is open. */
export async function loadTree(): Promise<void> {
  const key = activeScopeKey()
  if (!activeSpace.scopeId || !key) return
  try {
    treeStore.nodes = await listTree(activeSpace.scopeId, key)
    treeStore.loaded = true
    treeStore.error = false
  } catch {
    treeStore.error = true
  }
}

/** Forget the loaded tree (logout / space switch). */
export function resetTree(): void {
  treeStore.nodes = []
  treeStore.loaded = false
  treeStore.error = false
}
