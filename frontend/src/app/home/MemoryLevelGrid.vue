<script setup lang="ts">
import { t } from '../../i18n/i18n'
import type { LevelEntry } from '../../features/home/home'
import type { NodeType } from '../../features/memory/createTree/memoryTree'
import CreateNodeCard from './CreateNodeCard.vue'
import FolderCard from './FolderCard.vue'
import MemoryObjectCard from './MemoryObjectCard.vue'
import VeyqEmptyState from '../ui/VeyqEmptyState.vue'

type Section = 'M' | 'F'

defineProps<{
  error?: string
  memories: LevelEntry[]
  folders: LevelEntry[]
  covers: Record<string, string | null>
  favoriteIds: Set<string>
  compact?: boolean
}>()

const emit = defineEmits<{
  openMemory: [entry: LevelEntry]
  openFolder: [entry: LevelEntry]
  toggleFavorite: [entry: LevelEntry]
  create: [type: NodeType]
  openMenu: [entry: LevelEntry]
  dragStart: [entry: LevelEntry, section: Section]
  reorderDrop: [entry: LevelEntry, section: Section]
}>()
</script>

<template>
  <VeyqEmptyState v-if="error" class="home-empty">{{ error }}</VeyqEmptyState>

  <div class="veyq-section-header content-head">
    <span class="content-title">{{ t('home.memories') }}</span>
    <span class="content-count">{{ memories.length }}</span>
  </div>
  <div class="card-grid card-grid-memories" :class="{ 'card-grid-compact': compact }">
    <MemoryObjectCard
      v-for="m in memories"
      :key="m.node.id"
      :name="m.name"
      :cover="covers[m.node.id]"
      :favorite="favoriteIds.has(m.node.id)"
      :favorite-label="favoriteIds.has(m.node.id) ? t('memory.unfavorite') : t('memory.favorite')"
      :menu-label="`${t('common.rename')} / ${t('common.move')} / ${t('common.delete')}`"
      :draggable="true"
      @open="emit('openMemory', m)"
      @toggle-favorite="emit('toggleFavorite', m)"
      @menu="emit('openMenu', m)"
      @drag-start="emit('dragStart', m, 'M')"
      @reorder-drop="emit('reorderDrop', m, 'M')"
    />
    <CreateNodeCard @create="emit('create', 'MEMORY')">{{ t('home.createMemory') }}</CreateNodeCard>
  </div>

  <div class="veyq-section-header content-head">
    <span class="content-title">{{ t('home.folders') }}</span>
    <span class="content-count">{{ folders.length }}</span>
  </div>
  <div class="card-grid" :class="{ 'card-grid-compact': compact }">
    <FolderCard
      v-for="f in folders"
      :key="f.node.id"
      :name="f.name"
      :menu-label="`${t('common.rename')} / ${t('common.move')} / ${t('common.delete')}`"
      :draggable="true"
      @open="emit('openFolder', f)"
      @menu="emit('openMenu', f)"
      @drag-start="emit('dragStart', f, 'F')"
      @reorder-drop="emit('reorderDrop', f, 'F')"
    />
    <CreateNodeCard row @create="emit('create', 'FOLDER')">{{ t('home.createFolder') }}</CreateNodeCard>
  </div>
</template>
