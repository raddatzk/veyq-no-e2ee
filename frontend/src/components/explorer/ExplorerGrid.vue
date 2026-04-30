<script setup lang="ts">
import { computed } from 'vue'
import ExplorerAlbumCard from './ExplorerAlbumCard.vue'
import ExplorerFolderCard from './ExplorerFolderCard.vue'
import ExplorerScopeRootCard from './ExplorerScopeRootCard.vue'
import type { ExplorerAlbum, ExplorerFolder, ExplorerScopeRoot } from '../../api/explorerClient'

const props = defineProps<{
  scopeRoots?: ExplorerScopeRoot[]
  folders: ExplorerFolder[]
  albums: ExplorerAlbum[]
}>()

defineEmits<{
  openScopeRoot: [scopeRoot: ExplorerScopeRoot]
  openFolder: [folder: ExplorerFolder]
  openAlbum: [album: ExplorerAlbum]
  editFolder: [folder: ExplorerFolder]
  editAlbum: [album: ExplorerAlbum]
  moveFolder: [folder: ExplorerFolder]
  moveAlbum: [album: ExplorerAlbum]
  coverAlbum: [album: ExplorerAlbum]
  deleteFolder: [folder: ExplorerFolder]
  deleteAlbum: [album: ExplorerAlbum]
}>()

type SortedExplorerItem =
  | { type: 'folder'; item: ExplorerFolder }
  | { type: 'album'; item: ExplorerAlbum }

const sortedItems = computed<SortedExplorerItem[]>(() =>
  [
    ...props.folders.map((item) => ({ type: 'folder' as const, item })),
    ...props.albums.map((item) => ({ type: 'album' as const, item }))
  ].sort(compareItems)
)

function compareItems(left: SortedExplorerItem, right: SortedExplorerItem): number {
  return (
    left.item.displayName.localeCompare(right.item.displayName, undefined, { sensitivity: 'base' }) ||
    left.item.name.localeCompare(right.item.name, undefined, { sensitivity: 'base' }) ||
    left.type.localeCompare(right.type)
  )
}
</script>

<template>
  <section v-if="scopeRoots?.length" class="explorer-section">
    <div class="explorer-section-header">
      <h2>Bereiche</h2>
      <span>{{ scopeRoots.length }}</span>
    </div>
    <div class="explorer-scope-root-list">
      <ExplorerScopeRootCard
        v-for="scopeRoot in scopeRoots"
        :key="`${scopeRoot.kind}:${scopeRoot.name}`"
        :scope-root="scopeRoot"
        @open="$emit('openScopeRoot', $event)"
      />
    </div>
  </section>

  <section class="explorer-section">
    <div class="explorer-section-header">
      <h2>Ordner und Alben</h2>
      <span>{{ folders.length + albums.length }}</span>
    </div>
    <div v-if="sortedItems.length" class="explorer-list">
      <template
        v-for="entry in sortedItems"
        :key="`${entry.type}:${entry.item.id}`"
      >
        <ExplorerFolderCard
          v-if="entry.type === 'folder'"
          :folder="entry.item"
          @open="$emit('openFolder', $event)"
          @edit="$emit('editFolder', $event)"
          @move="$emit('moveFolder', $event)"
          @delete="$emit('deleteFolder', $event)"
        />
        <ExplorerAlbumCard
          v-else
          :album="entry.item"
          @open="$emit('openAlbum', $event)"
          @edit="$emit('editAlbum', $event)"
          @move="$emit('moveAlbum', $event)"
          @cover="$emit('coverAlbum', $event)"
          @delete="$emit('deleteAlbum', $event)"
        />
      </template>
    </div>
    <div v-else class="explorer-empty">Noch keine Ordner oder Alben in diesem Bereich.</div>
  </section>
</template>
