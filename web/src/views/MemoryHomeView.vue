<script setup lang="ts">
// The personal-space browse (cr-0030, uc.memory.browse). Mounted at `/` (root) and `/n/:nodeId` (a folder).
// Opens the space, loads the decrypted tree (once, via treeStore), renders the current level + a client-side
// breadcrumb, and owns the create / rename / delete dialogs. Operator-blind: names + navigation on device.
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { activeSpace, activeScopeKey, enterPersonalSpace } from '../app/activeSpace'
import { setBreadcrumb } from '../app/breadcrumb'
import { treeStore, loadTree } from '../features/memory/treeStore'
import { childrenOf, breadcrumbOf, nodeById } from '../features/memory/browse'
import { NodeKind } from '../api/generated/content'
import type { TreeNode } from '../features/memory/tree'
import MemoryBrowse from '../features/memory/components/MemoryBrowse.vue'
import NodeMutationDialog from '../features/memory/components/NodeMutationDialog.vue'
import DeleteNodeDialog from '../features/memory/components/DeleteNodeDialog.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const currentNodeId = computed(() => (typeof route.params.nodeId === 'string' ? route.params.nodeId : null))
const level = computed(() => childrenOf(treeStore.nodes, currentNodeId.value))
const scopeKey = computed(() => activeScopeKey())
// The folder we are currently inside (null at the root) — gives it a rename/delete header.
const currentFolder = computed(() => {
  const node = nodeById(treeStore.nodes, currentNodeId.value)
  return node && node.kind === NodeKind.Folder ? node : null
})

type Mode = 'create-folder' | 'create-memory' | 'rename'
const mutationVisible = ref(false)
const mutationMode = ref<Mode>('create-folder')
const mutationNode = ref<TreeNode | null>(null)
const deleteVisible = ref(false)
const deleteTarget = ref<TreeNode | null>(null)

watch(
  [currentNodeId, () => treeStore.nodes],
  () => setBreadcrumb(breadcrumbOf(treeStore.nodes, currentNodeId.value, t('shell.personal'))),
  { immediate: true },
)

async function ready() {
  if (activeSpace.status !== 'ready') await enterPersonalSpace()
  if (activeSpace.status === 'ready' && !treeStore.loaded) await loadTree()
}
void ready()

function openFolder(folder: TreeNode) {
  void router.push({ name: 'node', params: { nodeId: folder.id } })
}
function openMemory(memory: TreeNode) {
  void router.push({ name: 'memory', params: { nodeId: memory.id } })
}
function startCreate(mode: 'create-folder' | 'create-memory') {
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
  void router.push(parentId ? { name: 'node', params: { nodeId: parentId } } : { name: 'home' })
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
      <header v-if="currentFolder" class="node-header">
        <span class="pi pi-folder node-header-icon" aria-hidden="true" />
        <h1 class="node-header-title">{{ currentFolder.name }}</h1>
        <Button
          type="button"
          :label="t('memory.rename')"
          icon="pi pi-pencil"
          severity="secondary"
          text
          @click="startRename(currentFolder)"
        />
        <Button
          type="button"
          :label="t('memory.delete')"
          icon="pi pi-trash"
          severity="danger"
          text
          @click="startDelete(currentFolder)"
        />
      </header>
      <MemoryBrowse
        :folders="level.folders"
        :memories="level.memories"
        @open-folder="openFolder"
        @open-memory="openMemory"
        @create-folder="startCreate('create-folder')"
        @create-memory="startCreate('create-memory')"
      />
    </template>

    <NodeMutationDialog
      v-if="scopeKey && activeSpace.scopeId"
      v-model:visible="mutationVisible"
      :mode="mutationMode"
      :scope-id="activeSpace.scopeId"
      :scope-key="scopeKey"
      :parent-id="currentNodeId"
      :node="mutationNode"
      @done="onMutationDone"
    />
    <DeleteNodeDialog
      v-if="scopeKey && activeSpace.scopeId && deleteTarget"
      v-model:visible="deleteVisible"
      :scope-id="activeSpace.scopeId"
      :node="deleteTarget"
      @done="onDeleteDone"
    />
  </div>
</template>

<style scoped>
.memory-home {
  max-width: 1100px;
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
.node-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}
.node-header-icon {
  color: var(--app-muted);
}
.node-header-title {
  margin: 0;
  font-size: 1.4rem;
  flex: 1;
}
</style>
