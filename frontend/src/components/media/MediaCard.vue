<script setup lang="ts">
import { computed } from 'vue'
import type { MemoryMedia } from '../../features/memories/api'
import { API_BASE } from '../../api/httpClient'

const props = withDefaults(
  defineProps<{
    media: MemoryMedia
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
const thumbnailUrl = computed(() =>
  `${API_BASE}${props.media.thumbnailUrl || props.media.previewUrl || props.media.contentUrl}`
)
const usesPreviewImage = computed(() => isImage.value || (isVideo.value && Boolean(props.media.thumbnailUrl || props.media.previewUrl)))
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
        v-if="usesPreviewImage"
        :src="thumbnailUrl"
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
