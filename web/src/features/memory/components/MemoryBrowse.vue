<script setup lang="ts">
// The current tree level (cr-0030). Template + classes are the design source's MemoryFolderOverview verbatim
// (an optional folder-header card, then the Memories section + the Folders section, each a card grid with a
// "create" card) so memory.css styles it identically; only the data is ours — decrypted TreeNodes instead of
// server Memory/MemoryFolder DTOs, and the breadcrumb/navigation are derived on device (the server is blind).
// The folder header carries delete (as in the original) plus rename, added in-style.
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import type { TreeNode } from '../tree'
import MemoryCard from './MemoryCard.vue'

defineProps<{ folder?: TreeNode | null; folders: TreeNode[]; memories: TreeNode[] }>()
defineEmits<{
  openFolder: [folder: TreeNode]
  openMemory: [memory: TreeNode]
  createFolder: []
  createMemory: []
  renameFolder: []
  deleteFolder: []
}>()

const { t } = useI18n()
</script>

<template>
  <div class="memory-route-surface">
    <section v-if="folder" class="veyq-section memory-section">
      <div class="veyq-section-header">
        <div class="veyq-card memory-folder-card memory-folder-overview-card">
          <span class="pi pi-folder" />
          <h2>{{ folder.name }}</h2>
          <div class="memory-folder-overview-actions">
            <Button
              type="button"
              class="veyq-icon-button veyq-prime-icon-button memory-folder-overview-action memory-action-inline memory-action-collapsible"
              icon="pi pi-pencil"
              :aria-label="t('memory.rename')"
              severity="secondary"
              outlined
              size="small"
              @click="$emit('renameFolder')"
            />
            <Button
              type="button"
              class="veyq-icon-button veyq-prime-icon-button memory-folder-overview-action memory-danger-action memory-action-inline memory-action-collapsible"
              icon="pi pi-trash"
              :aria-label="t('memory.delete')"
              severity="danger"
              outlined
              size="small"
              @click="$emit('deleteFolder')"
            />
          </div>
        </div>
      </div>
    </section>

    <section class="veyq-section memory-section">
      <div class="veyq-section-header memory-section-header">
        <h2>{{ t('memory.memories') }}</h2>
        <span>{{ memories.length }}</span>
      </div>
      <div class="memory-card-grid memory-card-grid-memories">
        <p v-if="!memories.length" class="veyq-empty veyq-empty-inline">
          {{ t('memory.memoryEmpty') }}
        </p>
        <MemoryCard
          v-for="memory in memories"
          :key="memory.id"
          :memory="memory"
          @open="$emit('openMemory', $event)"
        />
        <button
          class="veyq-card veyq-card-interactive memory-create-card memory-create-card-memory"
          type="button"
          @click="$emit('createMemory')"
        >
          <span class="pi pi-plus" />
          <strong>{{ t('memory.create') }}</strong>
        </button>
      </div>
    </section>

    <section class="veyq-section memory-section">
      <div class="veyq-section-header memory-section-header">
        <h2>{{ t('memory.folders') }}</h2>
        <span>{{ folders.length }}</span>
      </div>
      <div class="memory-card-grid">
        <p v-if="!folders.length" class="veyq-empty veyq-empty-inline">
          {{ t('memory.folderEmpty') }}
        </p>
        <button
          v-for="child in folders"
          :key="child.id"
          class="veyq-card veyq-card-interactive memory-folder-card"
          type="button"
          @click="$emit('openFolder', child)"
        >
          <span class="pi pi-folder" />
          <strong>{{ child.name }}</strong>
        </button>
        <button
          class="veyq-card veyq-card-interactive memory-create-card"
          type="button"
          @click="$emit('createFolder')"
        >
          <span class="pi pi-plus" />
          <strong>{{ t('memory.folderCreate') }}</strong>
        </button>
      </div>
    </section>
  </div>
</template>
