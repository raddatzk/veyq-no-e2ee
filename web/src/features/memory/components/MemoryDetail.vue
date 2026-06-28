<script setup lang="ts">
// A Memory's detail surface (cr-0030/0031) — the leaf of the tree. For now just the title + rename/delete;
// the rich gallery / journal / chapters are the next slice. Presentational: the host view owns the dialogs.
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import type { TreeNode } from '../tree'

defineProps<{ node: TreeNode }>()
defineEmits<{ rename: []; delete: [] }>()

const { t } = useI18n()
</script>

<template>
  <div class="memory-route-surface memory-detail">
    <header class="memory-detail-header">
      <h1 class="memory-detail-title">{{ node.name }}</h1>
      <Button
        type="button"
        :label="t('memory.rename')"
        icon="pi pi-pencil"
        severity="secondary"
        text
        @click="$emit('rename')"
      />
      <Button
        type="button"
        :label="t('memory.delete')"
        icon="pi pi-trash"
        severity="danger"
        text
        @click="$emit('delete')"
      />
    </header>
    <p class="memory-detail-note">{{ t('memory.detailComing') }}</p>
  </div>
</template>

<style scoped>
.memory-detail-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.memory-detail-title {
  margin: 0;
  flex: 1;
}
.memory-detail-note {
  color: var(--app-muted);
}
</style>
