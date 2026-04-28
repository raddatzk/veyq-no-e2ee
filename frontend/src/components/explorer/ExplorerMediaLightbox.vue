<script setup lang="ts">
import { computed } from 'vue'
import type { ExplorerMedia } from '../../api/explorerClient'
import { API_BASE } from '../../api/httpClient'

const props = defineProps<{
  media: ExplorerMedia
  canShowPrevious: boolean
  canShowNext: boolean
}>()
defineEmits<{
  close: []
  previous: []
  next: []
}>()

const mediaUrl = computed(() => `${API_BASE}${props.media.contentUrl}`)
const isImage = computed(() => props.media.mimeType?.startsWith('image/'))
const isVideo = computed(() => props.media.mimeType?.startsWith('video/'))
const title = computed(() => props.media.title || props.media.id)
const downloadName = computed(() => safeFilename(title.value))

function safeFilename(value: string): string {
  return (
    value
      .trim()
      .replace(/[\\/:*?"<>|]+/g, '-')
      .replace(/\s+/g, ' ') || props.media.id
  )
}
</script>

<template>
  <div
    class="media-lightbox"
    role="dialog"
    aria-modal="true"
    :aria-label="title"
    @click.self="$emit('close')"
  >
    <div class="media-lightbox-topbar">
      <div class="media-lightbox-title">
        <strong>{{ title }}</strong>
        <small>{{ media.mimeType }}</small>
      </div>
      <div class="media-lightbox-actions">
        <a
          class="lightbox-icon-button"
          :href="mediaUrl"
          :download="downloadName"
          aria-label="Medium herunterladen"
        >
          <span class="pi pi-download" />
        </a>
        <button
          class="lightbox-icon-button"
          type="button"
          aria-label="Lightbox schließen"
          @click="$emit('close')"
        >
          <span class="pi pi-times" />
        </button>
      </div>
    </div>

    <button
      class="lightbox-nav lightbox-nav-previous"
      type="button"
      aria-label="Vorheriges Medium"
      :disabled="!canShowPrevious"
      @click="$emit('previous')"
    >
      <span class="pi pi-chevron-left" />
    </button>

    <div class="media-lightbox-stage">
      <img v-if="isImage" :src="mediaUrl" :alt="title" />
      <video v-else-if="isVideo" :src="mediaUrl" controls playsinline />
      <a v-else :href="mediaUrl" :download="downloadName">{{ title }}</a>
    </div>

    <button
      class="lightbox-nav lightbox-nav-next"
      type="button"
      aria-label="Nächstes Medium"
      :disabled="!canShowNext"
      @click="$emit('next')"
    >
      <span class="pi pi-chevron-right" />
    </button>
  </div>
</template>
