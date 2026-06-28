<script setup lang="ts">
// Minimal Memory view (cr-0030, `/m/:nodeId`). This slice only confirms navigation works end-to-end — the
// rich detail (gallery / journal / chapters) is the next slice. Shows the decrypted Memory name + breadcrumb.
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { activeSpace, enterPersonalSpace } from '../app/activeSpace'
import { setBreadcrumb } from '../app/breadcrumb'
import { treeStore, loadTree } from '../features/memory/treeStore'
import { breadcrumbOf, nodeById } from '../features/memory/browse'

const route = useRoute()
const { t } = useI18n()

const nodeId = computed(() => (typeof route.params.nodeId === 'string' ? route.params.nodeId : null))
const memory = computed(() => nodeById(treeStore.nodes, nodeId.value))

async function ready() {
  if (activeSpace.status !== 'ready') await enterPersonalSpace()
  if (activeSpace.status === 'ready' && !treeStore.loaded) await loadTree()
  setBreadcrumb(breadcrumbOf(treeStore.nodes, nodeId.value, t('shell.personal')))
}
void ready()
</script>

<template>
  <div class="memory-view">
    <h1 class="memory-view-title">{{ memory?.name ?? '' }}</h1>
    <p class="memory-view-note">{{ t('memory.detailComing') }}</p>
  </div>
</template>

<style scoped>
.memory-view {
  max-width: 1100px;
}
.memory-view-title {
  margin: 0 0 8px;
}
.memory-view-note {
  color: var(--app-muted);
}
</style>
