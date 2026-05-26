<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { Memory, MemoryFolder, MemoryNavigationResponse } from '../api'
import MemoryCard from './MemoryCard.vue'

const { t } = useI18n()

defineProps<{
  navigation: MemoryNavigationResponse
  memories: Memory[]
}>()

defineEmits<{
  openFolder: [folder: MemoryFolder]
  openMemory: [memory: Memory]
  createFolder: []
  createMemory: []
}>()
</script>

<template>
  <div class="memory-route-surface">
    <section class="memory-section">
      <div class="memory-section-header">
        <h2>{{ t('memory.memories') }}</h2>
        <span>{{ memories.length }}</span>
      </div>
      <div class="memory-card-grid memory-card-grid-memories">
        <p v-if="!memories.length" class="memory-empty memory-empty-inline">
          {{ t('memory.memoryEmpty') }}
        </p>
        <MemoryCard
          v-for="memory in memories"
          :key="memory.id"
          :memory="memory"
          @open="$emit('openMemory', $event)"
        />
        <button
          class="memory-create-card memory-create-card-memory"
          type="button"
          @click="$emit('createMemory')"
        >
          <span class="pi pi-plus" />
          <strong>{{ t('memory.create') }}</strong>
        </button>
      </div>
    </section>

    <section class="memory-section">
      <div class="memory-section-header">
        <h2>{{ t('memory.folders') }}</h2>
        <span>{{ navigation.folders.length }}</span>
      </div>
      <div class="memory-card-grid">
        <p v-if="!navigation.folders.length" class="memory-empty memory-empty-inline">
          {{ t('memory.folderEmpty') }}
        </p>
        <button
          v-for="folder in navigation.folders"
          :key="folder.path"
          class="memory-folder-card"
          type="button"
          @click="$emit('openFolder', folder)"
        >
          <span class="pi pi-folder" />
          <strong>{{ folder.displayName }}</strong>
        </button>
        <button class="memory-create-card" type="button" @click="$emit('createFolder')">
          <span class="pi pi-plus" />
          <strong>{{ t('memory.folderCreate') }}</strong>
        </button>
      </div>
    </section>
  </div>
</template>
