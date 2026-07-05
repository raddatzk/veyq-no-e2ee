<script setup lang="ts">
// The personal-space content host (cr-0030/0031, uc.memory.browse). Mounted at `/`; the current tree position
// lives in the URL **#fragment** (`#~me/reisen-2026/rom`), resolved against the locally-decrypted tree — the
// operator-blind server computes no navigation and never sees the path. Renders a folder/root level (browse)
// or a Memory leaf (detail) by the resolved node's kind, and owns the create / rename / delete dialogs.
import { computed, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { activeSpace, activeScopeKey, enterScope } from '../app/activeSpace'
import { setBreadcrumb } from '../app/breadcrumb'
import { isFavorite, toggleFavorite, loadFavoritesFor, favoritesStore } from '../app/favoritesStore'
import { treeStore, loadTree, patchNodeMeta } from '../features/memory/treeStore'
import { childrenOf, breadcrumbOf, nodeById } from '../features/memory/browse'
import { nodeContentKey } from '../features/memory/tree'
import { parseHash, resolveSlugPath, hashForNode } from '../features/memory/location'
import { listMyTenants, listMyGroups } from '../features/spaces/scopes'
import { NodeKind } from '../api/generated/content'
import { ScopeKind, type MyTenant } from '../api/generated/scopes'
import type { TreeNode } from '../features/memory/tree'
import MemoryBrowse from '../features/memory/components/MemoryBrowse.vue'
import MemoryDetail from '../features/memory/components/MemoryDetail.vue'
import NodeMutationDialog from '../features/memory/components/NodeMutationDialog.vue'
import DeleteNodeDialog from '../features/memory/components/DeleteNodeDialog.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

// Position from the #fragment. The leading token selects the scope (token-less = tenant, `~me` = personal —
// ADR 0019); a slug segment that doesn't resolve (`false`) collapses to the scope root.
const currentScope = computed(() => parseHash(route.hash).scope)
const segments = computed(() => parseHash(route.hash).segments)
const resolved = computed(() => resolveSlugPath(treeStore.nodes, segments.value))
const currentNodeId = computed(() => (typeof resolved.value === 'string' ? resolved.value : null))
const currentNode = computed(() => nodeById(treeStore.nodes, currentNodeId.value))
// A MEMORY or a CHAPTER opens to the gallery (a chapter is itself a Memory, cr-0060); folders/root browse.
const isOpenable = computed(
  () => currentNode.value?.kind === NodeKind.Memory || currentNode.value?.kind === NodeKind.Chapter,
)
const currentFolder = computed(() =>
  currentNode.value && currentNode.value.kind === NodeKind.Folder ? currentNode.value : null,
)
const level = computed(() => childrenOf(treeStore.nodes, currentNodeId.value))
// Depend on the reactive status so the computed re-evaluates once the key is set (the key fn isn't reactive).
const scopeKey = computed(() => (activeSpace.status === 'ready' ? activeScopeKey() : null))
// The open node's K_mem, resolved by walking the content-key chain from K_SC (a chapter's key is wrapped under
// its parent Memory's K_mem, recursive K_mem; held on device — the server is blind).
const memoryKey = computed(() =>
  isOpenable.value && currentNodeId.value && scopeKey.value
    ? nodeContentKey(scopeKey.value, treeStore.nodes, currentNodeId.value)
    : null,
)

type Mode = 'create-folder' | 'create-memory' | 'create-chapter' | 'rename'
const mutationVisible = ref(false)
const mutationMode = ref<Mode>('create-folder')
const mutationNode = ref<TreeNode | null>(null)
const deleteVisible = ref(false)
const deleteTarget = ref<TreeNode | null>(null)

// The breadcrumb root label reflects the **active** scope (the tenant's name vs. Personal), not the URL token —
// a no-grant member sees `Personal` at the token-less `/` because the tenant home fell back to Personal.
const tenants = ref<MyTenant[]>([])
onMounted(async () => {
  try {
    tenants.value = await listMyTenants()
  } catch {
    /* the label tolerates an unknown tenant name */
  }
})
const activeTenantName = computed(
  () => tenants.value.find((tn) => tn.tenantId === activeSpace.tenantId)?.name ?? '',
)
// A GROUP scope's root label is its **decrypted** name (en.spaces.group-scope, cr-0032) — re-resolved on every
// scope switch (so a freshly-created group labels correctly too); `Gruppen` shows until the name decrypts.
const groupName = ref('')
watch(
  () => [activeSpace.kind, activeSpace.scopeId],
  async () => {
    if (activeSpace.kind !== ScopeKind.Group || !activeSpace.scopeId) {
      groupName.value = ''
      return
    }
    try {
      groupName.value = (await listMyGroups()).find((g) => g.scopeId === activeSpace.scopeId)?.name ?? ''
    } catch {
      groupName.value = ''
    }
  },
  { immediate: true },
)
const rootLabel = computed(() =>
  activeSpace.kind === ScopeKind.Tenant
    ? activeTenantName.value || t('shell.shared')
    : activeSpace.kind === ScopeKind.Group
      ? groupName.value || t('shell.groups')
      : t('shell.personal'),
)

watch(
  [() => route.hash, () => treeStore.nodes, rootLabel],
  () => setBreadcrumb(breadcrumbOf(treeStore.nodes, currentNodeId.value, rootLabel.value, currentScope.value)),
  { immediate: true },
)

// Re-enter the scope when the leading fragment token flips (segment-only moves keep the same token → no
// re-entry). Entering opens the scope key + resets the tree/favorites (activeSpace.enterScope); we then reload
// both for the newly active scope.
async function reenter(scope: string) {
  await enterScope(scope)
  if (activeSpace.status === 'ready' && !treeStore.loaded) await loadTree()
  const key = activeScopeKey()
  if (activeSpace.status === 'ready' && activeSpace.scopeId && key && !favoritesStore.loaded) {
    await loadFavoritesFor(activeSpace.scopeId, key)
  }
}
watch(currentScope, (scope) => void reenter(scope), { immediate: true })

function onToggleFavorite(node: TreeNode) {
  const key = activeScopeKey()
  if (activeSpace.scopeId && key) void toggleFavorite(node.id, activeSpace.scopeId, key)
}

function openFolder(folder: TreeNode) {
  void router.push({ hash: hashForNode(treeStore.nodes, folder.id, currentScope.value) })
}
function openMemory(memory: TreeNode) {
  void router.push({ hash: hashForNode(treeStore.nodes, memory.id, currentScope.value) })
}
function openChapter(chapter: TreeNode) {
  void router.push({ hash: hashForNode(treeStore.nodes, chapter.id, currentScope.value) })
}
function startCreate(mode: 'create-folder' | 'create-memory' | 'create-chapter') {
  mutationMode.value = mode
  mutationNode.value = null
  mutationVisible.value = true
}
function startRename(node: TreeNode) {
  mutationMode.value = 'rename'
  mutationNode.value = node
  mutationVisible.value = true
}
function startDelete(node: TreeNode) {
  deleteTarget.value = node
  deleteVisible.value = true
}
async function onMutationDone() {
  await loadTree()
}
async function onDeleteDone() {
  const parentId = deleteTarget.value?.parentId ?? null
  await loadTree()
  void router.push({ hash: hashForNode(treeStore.nodes, parentId, currentScope.value) })
}
</script>

<template>
  <div class="memory-home">
    <p v-if="activeSpace.status === 'loading' || activeSpace.status === 'idle'" class="home-loading">
      {{ t('home.loading') }}
    </p>
    <div v-else-if="activeSpace.status === 'no-tenant'" class="home-state">
      <h2>{{ t('home.noTenant') }}</h2>
      <p>{{ t('home.noTenantHint') }}</p>
    </div>
    <div v-else-if="activeSpace.status === 'error' || treeStore.error" class="home-state">
      <p>{{ t('home.error') }}</p>
    </div>
    <template v-else>
      <MemoryDetail
        v-if="isOpenable && currentNode && activeSpace.scopeId"
        :node="currentNode"
        :scope-id="activeSpace.scopeId"
        :memory-key="memoryKey"
        :favorite="isFavorite(currentNode.id)"
        :chapters="level.chapters"
        @toggle-favorite="currentNode && onToggleFavorite(currentNode)"
        @rename="currentNode && startRename(currentNode)"
        @delete="currentNode && startDelete(currentNode)"
        @create-chapter="startCreate('create-chapter')"
        @open-chapter="openChapter"
        @meta-updated="patchNodeMeta($event.nodeId, $event.wrappedMeta)"
      />
      <MemoryBrowse
        v-else
        :folder="currentFolder"
        :folders="level.folders"
        :memories="level.memories"
        :scope-key="scopeKey"
        @open-folder="openFolder"
        @open-memory="openMemory"
        @toggle-favorite="onToggleFavorite"
        @create-folder="startCreate('create-folder')"
        @create-memory="startCreate('create-memory')"
        @rename-folder="currentFolder && startRename(currentFolder)"
        @delete-folder="currentFolder && startDelete(currentFolder)"
      />
    </template>

    <NodeMutationDialog
      v-if="mutationVisible && scopeKey && activeSpace.scopeId"
      :mode="mutationMode"
      :scope-id="activeSpace.scopeId"
      :scope-key="scopeKey"
      :parent-id="currentNodeId"
      :parent-key="memoryKey"
      :node="mutationNode"
      @done="onMutationDone"
      @close="mutationVisible = false"
    />
    <DeleteNodeDialog
      v-if="deleteVisible && scopeKey && activeSpace.scopeId && deleteTarget"
      :scope-id="activeSpace.scopeId"
      :node="deleteTarget"
      @done="onDeleteDone"
      @close="deleteVisible = false"
    />
  </div>
</template>

<style scoped>
/* Fill the full workspace width — the outer .content already applies the page padding, and the gallery's
   justified layout / the browse card grid both reflow to whatever width they're given (no readable-line-length
   concern here, unlike prose). */
.memory-home {
  width: 100%;
}
.home-loading {
  color: var(--app-muted);
}
.home-state h2 {
  margin: 0 0 6px;
}
.home-state p {
  color: var(--app-muted);
  margin: 0;
}
</style>
