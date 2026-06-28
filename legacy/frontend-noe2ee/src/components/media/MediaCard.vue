<script setup lang="ts">
import { computed } from 'vue'
import type { MemoryMedia } from '../../features/memories/api'
import { mediaContentUrl, mediaPreviewUrl } from '../../shared/utils/mediaUrls'

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
const mediaUrl = computed(() => mediaContentUrl(props.media))
const thumbnailUrl = computed(() => mediaPreviewUrl(props.media))
const usesPreviewImage = computed(() => isImage.value || (isVideo.value && Boolean(props.media.thumbnailUrl || props.media.previewUrl)))
</script>

<template>
  <button
    class="media-card"
    type="button"
    tabindex="-1"
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
