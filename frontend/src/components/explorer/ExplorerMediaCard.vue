<script setup lang="ts">
import Card from 'primevue/card'
import type { ExplorerMedia } from '../../api/explorerClient'

defineProps<{
  media: ExplorerMedia
}>()
</script>

<template>
  <Card class="media-card">
    <template #content>
      <div class="media-thumb">
        <span class="pi pi-image" />
      </div>
      <strong>{{ media.title || media.id }}</strong>
      <small>{{ media.albumPath || 'Album' }}</small>
    </template>
  </Card>
</template>
