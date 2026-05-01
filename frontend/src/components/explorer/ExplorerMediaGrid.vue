<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ProgressSpinner from 'primevue/progressspinner'
import ExplorerMediaCard from './ExplorerMediaCard.vue'
import ExplorerMediaLightbox from './ExplorerMediaLightbox.vue'
import { buildJustifiedMediaRows } from './mediaGridLayout'
import type { ExplorerMedia } from '../../api/explorerClient'

const props = defineProps<{
  media: ExplorerMedia[]
  streaming: boolean
}>()

const selectedIndex = ref<number | null>(null)
const mediaGrid = ref<HTMLElement | null>(null)
const mediaGridWidth = ref(0)
const aspectRatios = ref<Record<string, number>>({})
let mediaGridObserver: ResizeObserver | null = null
const selectedMedia = computed(() =>
  selectedIndex.value === null ? null : props.media[selectedIndex.value] || null
)
const isLightboxOpen = computed(() => selectedMedia.value !== null)
const canShowPrevious = computed(() => selectedIndex.value !== null && selectedIndex.value > 0)
const canShowNext = computed(
  () => selectedIndex.value !== null && selectedIndex.value < props.media.length - 1
)
const targetTileHeight = computed(() => {
  if (mediaGridWidth.value <= 720) return 140
  if (mediaGridWidth.value <= 1024) return 180
  return 220
})
const mediaRows = computed(() =>
  buildJustifiedMediaRows({
    ids: props.media.map((item) => item.id),
    aspectRatios: aspectRatios.value,
    containerWidth: mediaGridWidth.value,
    targetHeight: targetTileHeight.value,
    gap: 4
  })
)
const mediaById = computed(() => new Map(props.media.map((item) => [item.id, item])))

onMounted(() => {
  mediaGridObserver = new ResizeObserver((entries) => {
    mediaGridWidth.value = entries[0]?.contentRect.width || 0
  })
  if (mediaGrid.value) {
    mediaGridObserver.observe(mediaGrid.value)
    mediaGridWidth.value = mediaGrid.value.clientWidth
  }
})

watch(isLightboxOpen, (isOpen) => {
  if (isOpen) {
    window.addEventListener('keydown', handleKeydown)
  } else {
    window.removeEventListener('keydown', handleKeydown)
  }
})

watch(
  () => props.media.length,
  (length) => {
    if (selectedIndex.value !== null && selectedIndex.value >= length) closeLightbox()
  }
)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
  mediaGridObserver?.disconnect()
})

function openLightbox(index: number) {
  selectedIndex.value = index
}

function mediaIndex(mediaId: string) {
  return props.media.findIndex((item) => item.id === mediaId)
}

function rememberAspectRatio(mediaId: string, aspectRatio: number) {
  aspectRatios.value = { ...aspectRatios.value, [mediaId]: aspectRatio }
}

function closeLightbox() {
  selectedIndex.value = null
}

function showPrevious() {
  if (canShowPrevious.value && selectedIndex.value !== null) selectedIndex.value -= 1
}

function showNext() {
  if (canShowNext.value && selectedIndex.value !== null) selectedIndex.value += 1
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    closeLightbox()
  } else if (event.key === 'ArrowLeft') {
    event.preventDefault()
    showPrevious()
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    showNext()
  }
}
</script>

<template>
  <section class="explorer-section">
    <div class="explorer-section-header">
      <h2>Medien</h2>
      <span>{{ media.length }}</span>
    </div>
    <div v-if="media.length" ref="mediaGrid" class="media-grid">
      <div v-for="(row, rowIndex) in mediaRows" :key="rowIndex" class="media-grid-row">
        <ExplorerMediaCard
          v-for="layoutItem in row.items"
          :key="layoutItem.id"
          :media="mediaById.get(layoutItem.id)!"
          :width="layoutItem.width"
          :height="layoutItem.height"
          @open="openLightbox(mediaIndex(layoutItem.id))"
          @aspect-ratio="rememberAspectRatio(layoutItem.id, $event)"
        />
      </div>
    </div>
    <div v-else-if="streaming" class="explorer-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Medien werden geladen...</span>
    </div>
    <div v-else class="explorer-empty">Keine Medien unter diesem Pfad.</div>

    <ExplorerMediaLightbox
      v-if="selectedMedia"
      :media="selectedMedia"
      :can-show-previous="canShowPrevious"
      :can-show-next="canShowNext"
      @close="closeLightbox"
      @previous="showPrevious"
      @next="showNext"
    />
  </section>
</template>
