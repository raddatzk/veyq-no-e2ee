<script setup lang="ts">
// Minimal Memory view (cr-0030, `/m/:nodeId`). Confirms navigation works end-to-end and offers rename /
// delete; the rich detail (gallery / journal / chapters) is the next slice. Name decrypted on device.
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { activeSpace, activeScopeKey, enterPersonalSpace } from '../app/activeSpace'
import { setBreadcrumb } from '../app/breadcrumb'
import { treeStore, loadTree } from '../features/memory/treeStore'
import { breadcrumbOf, nodeById } from '../features/memory/browse'
import NodeMutationDialog from '../features/memory/components/NodeMutationDialog.vue'
import DeleteNodeDialog from '../features/memory/components/DeleteNodeDialog.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const nodeId = computed(() => (typeof route.params.nodeId === 'string' ? route.params.nodeId : null))
const memory = computed(() => nodeById(treeStore.nodes, nodeId.value))
// Depend on the reactive status so the computed re-evaluates once the key is set (the key fn isn't reactive).
const scopeKey = computed(() => (activeSpace.status === 'ready' ? activeScopeKey() : null))

const renameVisible = ref(false)
const deleteVisible = ref(false)

async function ready() {
  if (activeSpace.status !== 'ready') await enterPersonalSpace()
  if (activeSpace.status === 'ready' && !treeStore.loaded) await loadTree()
  setBreadcrumb(breadcrumbOf(treeStore.nodes, nodeId.value, t('shell.personal')))
}
void ready()

async function onRenamed() {
  await loadTree()
  setBreadcrumb(breadcrumbOf(treeStore.nodes, nodeId.value, t('shell.personal')))
}
async function onDeleted() {
  const parentId = memory.value?.parentId ?? null
  await loadTree()
  void router.push(parentId ? { name: 'node', params: { nodeId: parentId } } : { name: 'home' })
}
</script>

<template>
  <div class="memory-view">
    <header class="memory-view-header">
      <h1 class="memory-view-title">{{ memory?.name ?? '' }}</h1>
      <Button
        v-if="memory"
        type="button"
        :label="t('memory.rename')"
        icon="pi pi-pencil"
        severity="secondary"
        text
        @click="renameVisible = true"
      />
      <Button
        v-if="memory"
        type="button"
        :label="t('memory.delete')"
        icon="pi pi-trash"
        severity="danger"
        text
        @click="deleteVisible = true"
      />
    </header>
    <p class="memory-view-note">{{ t('memory.detailComing') }}</p>

    <NodeMutationDialog
      v-if="renameVisible && scopeKey && activeSpace.scopeId && memory"
      mode="rename"
      :scope-id="activeSpace.scopeId"
      :scope-key="scopeKey"
      :node="memory"
      @done="onRenamed"
      @close="renameVisible = false"
    />
    <DeleteNodeDialog
      v-if="deleteVisible && scopeKey && activeSpace.scopeId && memory"
      :scope-id="activeSpace.scopeId"
      :node="memory"
      @done="onDeleted"
      @close="deleteVisible = false"
    />
  </div>
</template>

<style scoped>
.memory-view {
  max-width: 1100px;
}
.memory-view-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.memory-view-title {
  margin: 0;
  flex: 1;
}
.memory-view-note {
  color: var(--app-muted);
}
</style>
