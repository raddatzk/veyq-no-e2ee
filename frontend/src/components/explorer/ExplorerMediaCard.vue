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
const emit = defineEmits<{
  open: []
  aspectRatio: [value: number]
}>()

const isImage = computed(() => props.media.mimeType?.startsWith('image/'))
const isVideo = computed(() => props.media.mimeType?.startsWith('video/'))
const mediaUrl = computed(() => `${API_BASE}${props.media.contentUrl}`)

function emitImageAspectRatio(event: Event) {
  const image = event.currentTarget as HTMLImageElement
  if (image.naturalWidth > 0 && image.naturalHeight > 0) {
    emit('aspectRatio', image.naturalWidth / image.naturalHeight)
  }
}

function emitVideoAspectRatio(event: Event) {
  const video = event.currentTarget as HTMLVideoElement
  if (video.videoWidth > 0 && video.videoHeight > 0) {
    emit('aspectRatio', video.videoWidth / video.videoHeight)
  }
}
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
        @load="emitImageAspectRatio"
      />
      <video
        v-else-if="isVideo"
        :src="mediaUrl"
        preload="metadata"
        muted
        playsinline
        @loadedmetadata="emitVideoAspectRatio"
      />
      <span v-else class="pi pi-image" />
    </span>
  </button>
</template>
