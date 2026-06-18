<script setup lang="ts">
import { t } from '../../i18n/i18n'
import type { LevelEntry } from '../../features/home/home'
import type { NodeType } from '../../features/memory/createTree/memoryTree'

type Section = 'M' | 'F'

defineProps<{
  error?: string
  memories: LevelEntry[]
  folders: LevelEntry[]
  covers: Record<string, string | null>
}>()

const emit = defineEmits<{
  openMemory: [entry: LevelEntry]
  openFolder: [entry: LevelEntry]
  create: [type: NodeType]
  openMenu: [entry: LevelEntry]
  dragStart: [entry: LevelEntry, section: Section]
  reorderDrop: [entry: LevelEntry, section: Section]
}>()
</script>

<template>
  <div v-if="error" class="home-empty">{{ error }}</div>

  <div class="content-head">
    <span class="content-title">{{ t('home.memories') }}</span>
    <span class="content-count">{{ memories.length }}</span>
  </div>
  <div class="card-grid card-grid-memories">
    <button
      v-for="m in memories"
      :key="m.node.id"
      class="memory-card"
      draggable="true"
      @click="emit('openMemory', m)"
      @dragstart="emit('dragStart', m, 'M')"
      @dragover.prevent
      @drop="emit('reorderDrop', m, 'M')"
    >
      <img v-if="covers[m.node.id]" class="memory-cover" :src="covers[m.node.id]!" alt="" />
      <div v-else class="memory-cover placeholder">...</div>
      <div class="memory-label"><div class="memory-name">{{ m.name }}</div></div>
      <span class="card-more" @click.stop="emit('openMenu', m)">...</span>
    </button>
    <button class="create-card create-card-memory" @click="emit('create', 'MEMORY')"><span class="create-plus">+</span>{{ t('home.createMemory') }}</button>
  </div>

  <div class="content-head">
    <span class="content-title">{{ t('home.folders') }}</span>
    <span class="content-count">{{ folders.length }}</span>
  </div>
  <div class="card-grid">
    <button
      v-for="f in folders"
      :key="f.node.id"
      class="folder-card"
      draggable="true"
      @click="emit('openFolder', f)"
      @dragstart="emit('dragStart', f, 'F')"
      @dragover.prevent
      @drop="emit('reorderDrop', f, 'F')"
    >
      <span class="folder-icon" aria-hidden="true"></span><span class="folder-name">{{ f.name }}</span>
      <span class="card-more" @click.stop="emit('openMenu', f)">...</span>
    </button>
    <button class="create-card create-card-row" @click="emit('create', 'FOLDER')">
      <span class="create-plus">+</span>{{ t('home.createFolder') }}
    </button>
  </div>
</template>
