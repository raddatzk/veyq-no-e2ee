<script setup lang="ts">
import Button from 'primevue/button'
import Card from 'primevue/card'
import type { ExplorerFolder } from '../../api/explorerClient'

defineProps<{
  folder: ExplorerFolder
}>()

defineEmits<{
  open: [folder: ExplorerFolder]
  edit: [folder: ExplorerFolder]
  move: [folder: ExplorerFolder]
  share: [folder: ExplorerFolder]
  delete: [folder: ExplorerFolder]
}>()
</script>

<template>
  <Card class="explorer-card explorer-card-folder">
    <template #content>
      <button class="explorer-card-main" type="button" @click="$emit('open', folder)">
        <span class="explorer-card-icon pi pi-folder" />
        <span>
          <strong>{{ folder.displayName }}</strong>
          <small>{{ folder.path }}</small>
        </span>
      </button>
      <div class="explorer-card-actions">
        <Button
          icon="pi pi-pencil"
          text
          rounded
          severity="secondary"
          aria-label="Ordner bearbeiten"
          @click="$emit('edit', folder)"
        />
        <Button
          icon="pi pi-arrow-right-arrow-left"
          text
          rounded
          severity="secondary"
          aria-label="Ordner verschieben"
          @click="$emit('move', folder)"
        />
        <Button
          icon="pi pi-share-alt"
          text
          rounded
          severity="secondary"
          aria-label="Ordner freigeben"
          @click="$emit('share', folder)"
        />
        <Button
          icon="pi pi-trash"
          text
          rounded
          severity="danger"
          aria-label="Ordner löschen"
          @click="$emit('delete', folder)"
        />
      </div>
    </template>
  </Card>
</template>
