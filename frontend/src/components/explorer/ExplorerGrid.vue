<script setup lang="ts">
import ExplorerAlbumCard from './ExplorerAlbumCard.vue'
import ExplorerFolderCard from './ExplorerFolderCard.vue'
import ExplorerScopeRootCard from './ExplorerScopeRootCard.vue'
import type { ExplorerAlbum, ExplorerFolder, ExplorerScopeRoot } from '../../api/explorerClient'

defineProps<{
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
</script>

<template>
  <section v-if="scopeRoots?.length" class="explorer-section">
    <div class="explorer-section-header">
      <h2>Bereiche</h2>
      <span>{{ scopeRoots.length }}</span>
    </div>
    <div class="explorer-grid">
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
    <div v-if="folders.length || albums.length" class="explorer-grid">
      <ExplorerFolderCard
        v-for="folder in folders"
        :key="folder.id"
        :folder="folder"
        @open="$emit('openFolder', $event)"
        @edit="$emit('editFolder', $event)"
        @move="$emit('moveFolder', $event)"
        @delete="$emit('deleteFolder', $event)"
      />
      <ExplorerAlbumCard
        v-for="album in albums"
        :key="album.id"
        :album="album"
        @open="$emit('openAlbum', $event)"
        @edit="$emit('editAlbum', $event)"
        @move="$emit('moveAlbum', $event)"
        @cover="$emit('coverAlbum', $event)"
        @delete="$emit('deleteAlbum', $event)"
      />
    </div>
    <div v-else class="explorer-empty">Noch keine Ordner oder Alben in diesem Bereich.</div>
  </section>
</template>
