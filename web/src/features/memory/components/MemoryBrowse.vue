<script setup lang="ts">
// The current tree level (cr-0030, port of legacy MemoryFolderOverview): a Memories section + a Folders
// section, each a card grid with a "create" card. Styling reuses the design source's shared classes
// (.veyq-section / .veyq-card / .memory-card-grid / .memory-folder-card / …) — see src/style.css.
import { useI18n } from 'vue-i18n'
import type { TreeNode } from '../tree'
import MemoryCard from './MemoryCard.vue'

defineProps<{ folders: TreeNode[]; memories: TreeNode[] }>()
defineEmits<{
  openFolder: [folder: TreeNode]
  openMemory: [memory: TreeNode]
  createFolder: []
  createMemory: []
}>()

const { t } = useI18n()
</script>

<template>
  <div class="memory-route-surface">
    <section class="veyq-section">
      <div class="veyq-section-header">
        <h2>{{ t('memory.memories') }}</h2>
        <span>{{ memories.length }}</span>
      </div>
      <div class="memory-card-grid memory-card-grid-memories">
        <MemoryCard v-for="memory in memories" :key="memory.id" :memory="memory" @open="$emit('openMemory', $event)" />
        <button class="memory-create-card veyq-card veyq-card-interactive" type="button" @click="$emit('createMemory')">
          <i class="pi pi-plus" aria-hidden="true" />
          <strong>{{ t('memory.create') }}</strong>
        </button>
      </div>
      <p v-if="!memories.length" class="veyq-empty-inline">{{ t('memory.memoryEmpty') }}</p>
    </section>

    <section class="veyq-section">
      <div class="veyq-section-header">
        <h2>{{ t('memory.folders') }}</h2>
        <span>{{ folders.length }}</span>
      </div>
      <div class="memory-card-grid">
        <button
          v-for="folder in folders"
          :key="folder.id"
          class="memory-folder-card veyq-card veyq-card-interactive"
          type="button"
          @click="$emit('openFolder', folder)"
        >
          <i class="pi pi-folder" aria-hidden="true" />
          <strong>{{ folder.name }}</strong>
        </button>
        <button class="memory-create-card veyq-card veyq-card-interactive" type="button" @click="$emit('createFolder')">
          <i class="pi pi-plus" aria-hidden="true" />
          <strong>{{ t('memory.folderCreate') }}</strong>
        </button>
      </div>
      <p v-if="!folders.length" class="veyq-empty-inline">{{ t('memory.folderEmpty') }}</p>
    </section>
  </div>
</template>
