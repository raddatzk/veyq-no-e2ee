<script setup lang="ts">
// The current tree level (cr-0030, port of legacy MemoryFolderOverview): a Memories section + a Folders
// section, each a card grid. Drill into a folder / open a Memory via events. Create cards + the create/
// rename/delete dialogs land in P4; this component is browse + navigate only.
import { useI18n } from 'vue-i18n'
import type { TreeNode } from '../tree'
import MemoryCard from './MemoryCard.vue'

defineProps<{ folders: TreeNode[]; memories: TreeNode[] }>()
defineEmits<{ openFolder: [folder: TreeNode]; openMemory: [memory: TreeNode] }>()

const { t } = useI18n()
</script>

<template>
  <div class="memory-browse">
    <section class="node-section">
      <div class="node-section-header">
        <h2>{{ t('memory.memories') }}</h2>
        <span class="node-count">{{ memories.length }}</span>
      </div>
      <p v-if="!memories.length" class="node-empty">{{ t('memory.memoryEmpty') }}</p>
      <div v-else class="node-grid">
        <MemoryCard v-for="memory in memories" :key="memory.id" :memory="memory" @open="$emit('openMemory', $event)" />
      </div>
    </section>

    <section class="node-section">
      <div class="node-section-header">
        <h2>{{ t('memory.folders') }}</h2>
        <span class="node-count">{{ folders.length }}</span>
      </div>
      <p v-if="!folders.length" class="node-empty">{{ t('memory.folderEmpty') }}</p>
      <div v-else class="node-grid">
        <button
          v-for="folder in folders"
          :key="folder.id"
          class="node-card"
          type="button"
          @click="$emit('openFolder', folder)"
        >
          <span class="pi pi-folder node-card-icon" aria-hidden="true" />
          <strong class="node-card-name">{{ folder.name }}</strong>
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.memory-browse {
  display: flex;
  flex-direction: column;
  gap: 28px;
}
.node-section-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}
.node-section-header h2 {
  margin: 0;
  font-size: 1.15rem;
}
.node-count {
  color: var(--app-muted);
  font-size: 0.9rem;
}
.node-empty {
  color: var(--app-muted);
  margin: 0;
}
.node-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 14px;
}
.node-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
  padding: 16px;
  min-height: 96px;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: #fff;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: var(--app-text);
}
.node-card:hover {
  border-color: var(--app-muted);
  box-shadow: var(--app-shadow-md);
}
.node-card-icon {
  font-size: 1.4rem;
  color: var(--app-muted);
}
.node-card-name {
  font-weight: 600;
  word-break: break-word;
}
</style>
