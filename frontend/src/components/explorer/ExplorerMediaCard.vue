<script setup lang="ts">
import { computed } from 'vue'
import Card from 'primevue/card'
import type { ExplorerMedia } from '../../api/explorerClient'
import { API_BASE } from '../../api/httpClient'

const props = defineProps<{
  media: ExplorerMedia
}>()
defineEmits<{
  open: []
}>()

const isImage = computed(() => props.media.mimeType?.startsWith('image/'))
const isVideo = computed(() => props.media.mimeType?.startsWith('video/'))
const mediaUrl = computed(() => `${API_BASE}${props.media.contentUrl}`)
</script>

<template>
  <Card class="media-card">
    <template #content>
      <button class="media-card-button" type="button" @click="$emit('open')">
        <div class="media-thumb">
          <img v-if="isImage" :src="mediaUrl" :alt="media.title || media.id" loading="lazy" />
          <video v-else-if="isVideo" :src="mediaUrl" preload="metadata" muted playsinline />
          <span v-else class="pi pi-image" />
        </div>
        <strong>{{ media.title || media.id }}</strong>
        <small>{{ media.albumPath || 'Album' }}</small>
      </button>
    </template>
  </Card>
</template>
