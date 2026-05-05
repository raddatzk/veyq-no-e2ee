<script setup lang="ts">
import { computed } from 'vue'
import type { ExplorerMedia } from '../../api/explorerClient'
import { API_BASE } from '../../api/httpClient'

const props = withDefaults(
  defineProps<{
    media: ExplorerMedia
    width: number
    height: number
    loading?: 'lazy' | 'eager'
  }>(),
  {
    loading: 'lazy'
  }
)
defineEmits<{
  open: []
}>()

const isImage = computed(() => props.media.mimeType?.startsWith('image/'))
const isVideo = computed(() => props.media.mimeType?.startsWith('video/'))
const mediaUrl = computed(() => `${API_BASE}${props.media.contentUrl}`)
</script>

<template>
  <button
    class="media-card"
    type="button"
    :style="{ width: `${width}px`, height: `${height}px` }"
    @click="$emit('open')"
  >
    <span class="media-thumb">
      <img
        v-if="isImage"
        :src="mediaUrl"
        :alt="media.title || media.id"
        :loading="loading"
      />
      <video
        v-else-if="isVideo"
        :src="mediaUrl"
        preload="metadata"
        muted
        playsinline
      />
      <span v-else class="pi pi-image" />
    </span>
  </button>
</template>
