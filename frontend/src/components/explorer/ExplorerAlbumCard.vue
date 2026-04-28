<script setup lang="ts">
import Button from 'primevue/button'
import Card from 'primevue/card'
import type { ExplorerAlbum } from '../../api/explorerClient'

defineProps<{
  album: ExplorerAlbum
}>()

defineEmits<{
  open: [album: ExplorerAlbum]
  edit: [album: ExplorerAlbum]
  move: [album: ExplorerAlbum]
  share: [album: ExplorerAlbum]
  cover: [album: ExplorerAlbum]
  delete: [album: ExplorerAlbum]
}>()
</script>

<template>
  <Card class="explorer-card explorer-card-album">
    <template #content>
      <button class="explorer-card-main" type="button" @click="$emit('open', album)">
        <span class="explorer-card-icon pi pi-images" />
        <span>
          <strong>{{ album.displayName }}</strong>
          <small>{{ album.coverMediaId ? `Cover: ${album.coverMediaId}` : album.path }}</small>
        </span>
      </button>
      <div class="explorer-card-actions">
        <Button icon="pi pi-pencil" text rounded severity="secondary" aria-label="Album bearbeiten" @click="$emit('edit', album)" />
        <Button icon="pi pi-arrow-right-arrow-left" text rounded severity="secondary" aria-label="Album verschieben" @click="$emit('move', album)" />
        <Button icon="pi pi-share-alt" text rounded severity="secondary" aria-label="Album freigeben" @click="$emit('share', album)" />
        <Button icon="pi pi-image" text rounded severity="secondary" aria-label="Cover setzen" @click="$emit('cover', album)" />
        <Button icon="pi pi-trash" text rounded severity="danger" aria-label="Album löschen" @click="$emit('delete', album)" />
      </div>
    </template>
  </Card>
</template>
