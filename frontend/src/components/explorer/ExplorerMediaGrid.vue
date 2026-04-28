<script setup lang="ts">
import ProgressSpinner from 'primevue/progressspinner'
import ExplorerMediaCard from './ExplorerMediaCard.vue'
import type { ExplorerMedia } from '../../api/explorerClient'

defineProps<{
  media: ExplorerMedia[]
  streaming: boolean
}>()
</script>

<template>
  <section class="explorer-section">
    <div class="explorer-section-header">
      <h2>Medien</h2>
      <span>{{ media.length }}</span>
    </div>
    <div v-if="media.length" class="media-grid">
      <ExplorerMediaCard v-for="item in media" :key="item.id" :media="item" />
    </div>
    <div v-else-if="streaming" class="explorer-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Medien werden geladen...</span>
    </div>
    <div v-else class="explorer-empty">Keine Medien unter diesem Pfad.</div>
  </section>
</template>
