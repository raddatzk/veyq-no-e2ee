<script setup lang="ts">
// The current tree level (cr-0030). Template + classes are the design source's MemoryFolderOverview verbatim
// (an optional folder-header card, then the Memories section + the Folders section, each a card grid with a
// "create" card) so memory.css styles it identically; only the data is ours — decrypted TreeNodes instead of
// server Memory/MemoryFolder DTOs, and the breadcrumb/navigation are derived on device (the server is blind).
// The folder header carries delete (as in the original) plus rename, added in-style.
import { computed, onBeforeUnmount, watch } from 'vue'
import { reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { nodeContentKey, type TreeNode } from '../tree'
import { sortNodesByDate } from '../memoryDate'
import { nodeById } from '../browse'
import { treeStore } from '../treeStore'
import { collectLocatedPins } from '../spaceMap'
import { loadPinCover } from '../pinCover'
import { isFavorite } from '../../../app/favoritesStore'
import { activeSpace } from '../../../app/activeSpace'
import { useViewMode } from '../../../shared/state/useViewMode'
import MemoryCard from './MemoryCard.vue'
import ViewModeToggle from './ViewModeToggle.vue'
import SpaceMap from './SpaceMap.vue'

// `scopeKey` (K_SC) unseals each Memory's sealed date for the card + the date-sort; null (locked) ⇒ no dates.
const props = defineProps<{ folder?: TreeNode | null; folders: TreeNode[]; memories: TreeNode[]; scopeKey?: Uint8Array | null }>()
const emit = defineEmits<{
  openFolder: [folder: TreeNode]
  openMemory: [memory: TreeNode]
  toggleFavorite: [memory: TreeNode]
  createFolder: []
  createMemory: []
  renameFolder: []
  deleteFolder: []
}>()

const { t } = useI18n()
const { viewMode } = useViewMode()

// Memories ordered by their sealed start date (newest first, undated last) — read from wrappedMeta, no media load.
const datedMemories = computed(() => sortNodesByDate(props.scopeKey ?? null, props.memories))

// The Karte pins: every located node in this folder's subtree (or the whole space at root), unsealed on device
// from the wrappedMeta `listNodes` already returned (operator-blind — no locations API). Locked ⇒ no pins.
const pins = computed(() =>
  props.scopeKey ? collectLocatedPins(props.scopeKey, treeStore.nodes, props.folder?.id ?? null) : [],
)

/** A pin click carries a node id — resolve it to the TreeNode and open it, exactly like a card click. */
function onPinOpen(nodeId: string) {
  const node = nodeById(treeStore.nodes, nodeId)
  if (node) emit('openMemory', node)
}

// Pin cover thumbnails (nodeId → object URL), loaded lazily on device only while the map is shown. Kept here (we
// hold `scopeKey` + `scopeId`); passed read-only to SpaceMap. `nodeContentKey` is resolved off the reactive proxy
// so K_mem bytes never enter Vue's reactivity (matching activeSpace's key handling).
const covers = reactive(new Map<string, string>())

function revokeCovers() {
  for (const url of covers.values()) URL.revokeObjectURL(url)
  covers.clear()
}

/** For each map pin without a cover yet, decrypt its thumbnail (best-effort, parallel) and cache the object URL. */
function syncCovers() {
  if (viewMode.value !== 'map' || !activeSpace.scopeId || !props.scopeKey) return
  const scopeId = activeSpace.scopeId
  const scopeKey = props.scopeKey
  for (const pin of pins.value) {
    if (covers.has(pin.node.id)) continue
    const nodeKey = nodeContentKey(scopeKey, treeStore.nodes, pin.node.id)
    if (!nodeKey) continue
    void loadPinCover(scopeId, nodeKey, pin.node.id, pin.location.derived).then((url) => {
      if (url) covers.set(pin.node.id, url)
    })
  }
}

watch([viewMode, pins], syncCovers, { immediate: true })
onBeforeUnmount(revokeCovers)
</script>

<template>
  <div class="memory-route-surface">
    <div class="memory-browse-toolbar">
      <ViewModeToggle show-map />
    </div>
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

    <section v-if="viewMode === 'map'" class="veyq-section memory-section">
      <SpaceMap :pins="pins" :covers="covers" @open="onPinOpen" />
    </section>

    <template v-else>
    <section class="veyq-section memory-section">
      <div class="veyq-section-header memory-section-header">
        <h2>{{ t('memory.memories') }}</h2>
        <span>{{ memories.length }}</span>
      </div>
      <div class="memory-card-grid memory-card-grid-memories" :class="{ 'is-list': viewMode === 'list' }">
        <p v-if="!memories.length" class="veyq-empty veyq-empty-inline">
          {{ t('memory.memoryEmpty') }}
        </p>
        <MemoryCard
          v-for="{ node: memory, date } in datedMemories"
          :key="memory.id"
          :memory="memory"
          :date="date"
          :favorite="isFavorite(memory.id)"
          @open="$emit('openMemory', $event)"
          @toggle-favorite="$emit('toggleFavorite', $event)"
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
      <div class="memory-card-grid" :class="{ 'is-list': viewMode === 'list' }">
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
    </template>
  </div>
</template>

<style scoped>
.memory-browse-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 0.75rem;
}
</style>
