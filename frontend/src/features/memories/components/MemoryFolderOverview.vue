<script setup lang="ts">
import type { Memory, MemoryFolder, MemoryNavigationResponse } from '../api'
import MemoryCard from './MemoryCard.vue'

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
        <h2>Memories</h2>
        <span>{{ memories.length }}</span>
      </div>
      <div class="memory-card-grid">
        <MemoryCard
          v-for="memory in memories"
          :key="memory.id"
          :memory="memory"
          @open="$emit('openMemory', $event)"
        />
        <button class="memory-create-card" type="button" @click="$emit('createMemory')">
          <span class="pi pi-plus" />
          <strong>Memory erstellen</strong>
        </button>
      </div>
    </section>

    <section class="memory-section">
      <div class="memory-section-header">
        <h2>Ordner</h2>
        <span>{{ navigation.folders.length }}</span>
      </div>
      <div class="memory-card-grid">
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
          <strong>Ordner erstellen</strong>
        </button>
      </div>
    </section>
  </div>
</template>
