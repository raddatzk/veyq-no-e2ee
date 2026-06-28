<script setup lang="ts">
// The personal-space browse (cr-0030, uc.memory.browse). Mounted at `/` (root) and `/n/:nodeId` (a folder).
// Opens the space, loads the decrypted tree (once, via treeStore), and renders the current level + a
// client-side breadcrumb. Operator-blind: names + navigation are derived on device.
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { activeSpace, enterPersonalSpace } from '../app/activeSpace'
import { setBreadcrumb } from '../app/breadcrumb'
import { treeStore, loadTree } from '../features/memory/treeStore'
import { childrenOf, breadcrumbOf } from '../features/memory/browse'
import type { TreeNode } from '../features/memory/tree'
import MemoryBrowse from '../features/memory/components/MemoryBrowse.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const currentNodeId = computed(() => (typeof route.params.nodeId === 'string' ? route.params.nodeId : null))
const level = computed(() => childrenOf(treeStore.nodes, currentNodeId.value))

// Keep the breadcrumb in sync with the level + the (possibly still-loading) tree.
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
    <MemoryBrowse
      v-else
      :folders="level.folders"
      :memories="level.memories"
      @open-folder="openFolder"
      @open-memory="openMemory"
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
</style>
