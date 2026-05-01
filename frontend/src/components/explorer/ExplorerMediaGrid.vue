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
let observedMediaGrid: HTMLElement | null = null
let aspectRatioFrame: number | null = null
let pendingAspectRatios: Record<string, number> = {}
const selectedMedia = computed(() =>
  selectedIndex.value === null ? null : props.media[selectedIndex.value] || null
)
const isLightboxOpen = computed(() => selectedMedia.value !== null)
const canShowPrevious = computed(() => selectedIndex.value !== null && selectedIndex.value > 0)
const canShowNext = computed(
  () => selectedIndex.value !== null && selectedIndex.value < props.media.length - 1
)
const layoutWidth = computed(() => mediaGridWidth.value || 1024)
const minTileHeight = computed(() => (layoutWidth.value <= 720 ? 140 : 170))
const maxTileHeight = computed(() => (layoutWidth.value <= 720 ? 190 : 210))
const targetTileHeight = computed(() => (layoutWidth.value <= 720 ? 190 : 210))
const backendAspectRatios = computed<Record<string, number>>(() =>
  Object.fromEntries(
    props.media
      .map((item) => [item.id, mediaAspectRatio(item)] as const)
      .filter((entry): entry is readonly [string, number] => entry[1] !== null)
  )
)
const layoutAspectRatios = computed<Record<string, number>>(() => ({
  ...backendAspectRatios.value,
  ...aspectRatios.value
}))
const knownAspectRatioIds = computed(
  () => new Set(props.media.filter((item) => layoutAspectRatios.value[item.id]).map((item) => item.id))
)
const unknownAspectRatioMedia = computed(() =>
  props.media.filter((item) => !knownAspectRatioIds.value.has(item.id))
)
const canRenderMediaRows = computed(
  () => props.media.length > 0 && mediaGridWidth.value > 0 && unknownAspectRatioMedia.value.length === 0
)
const mediaRows = computed(() => {
  if (!canRenderMediaRows.value) return []

  return buildJustifiedMediaRows({
    ids: props.media.map((item) => item.id),
    aspectRatios: layoutAspectRatios.value,
    containerWidth: layoutWidth.value,
    targetHeight: targetTileHeight.value,
    minHeight: minTileHeight.value,
    maxHeight: maxTileHeight.value,
    gap: 4
  })
})
const mediaById = computed(() => new Map(props.media.map((item) => [item.id, item])))

onMounted(() => {
  attachMediaGridObserver()
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
    attachMediaGridObserver()
  }
)

watch(mediaGrid, attachMediaGridObserver, { flush: 'post' })

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
  mediaGridObserver?.disconnect()
  if (aspectRatioFrame !== null) cancelAnimationFrame(aspectRatioFrame)
})

function attachMediaGridObserver() {
  const element = mediaGrid.value
  if (!element || observedMediaGrid === element) return

  mediaGridObserver?.disconnect()
  observedMediaGrid = element
  measureMediaGrid(element)

  if (typeof ResizeObserver === 'undefined') return
  mediaGridObserver = new ResizeObserver((entries) => {
    const width = entries[0]?.contentRect.width || 0
    mediaGridWidth.value = width || measuredWidth(element)
  })
  mediaGridObserver.observe(element)
}

function measureMediaGrid(element: HTMLElement) {
  mediaGridWidth.value = measuredWidth(element)
}

function measuredWidth(element: HTMLElement): number {
  return element.getBoundingClientRect().width || element.clientWidth || element.parentElement?.clientWidth || 0
}

function openLightbox(index: number) {
  selectedIndex.value = index
}

function mediaIndex(mediaId: string) {
  return props.media.findIndex((item) => item.id === mediaId)
}

function mediaAspectRatio(media: ExplorerMedia): number | null {
  if (!media.imageWidth || !media.imageHeight || media.imageWidth <= 0 || media.imageHeight <= 0) {
    return null
  }
  return media.imageWidth / media.imageHeight
}

function rememberAspectRatio(mediaId: string, aspectRatio: number) {
  if (!Number.isFinite(aspectRatio) || aspectRatio <= 0) return
  pendingAspectRatios = { ...pendingAspectRatios, [mediaId]: aspectRatio }
  if (aspectRatioFrame !== null) return

  aspectRatioFrame = requestAnimationFrame(() => {
    aspectRatios.value = { ...aspectRatios.value, ...pendingAspectRatios }
    pendingAspectRatios = {}
    aspectRatioFrame = null
  })
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
      <template v-if="canRenderMediaRows">
        <div
          v-for="(row, rowIndex) in mediaRows"
          :key="rowIndex"
          class="media-grid-row"
          :class="`media-grid-row-${row.align}`"
        >
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
      </template>
      <div v-else class="media-grid-placeholder">
        <ProgressSpinner class="small-spinner" />
        <span>Medien werden vorbereitet...</span>
      </div>
    </div>
    <div v-else-if="streaming" class="explorer-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Medien werden geladen...</span>
    </div>
    <div v-else class="explorer-empty">Keine Medien unter diesem Pfad.</div>
    <div
      v-if="media.length && !canRenderMediaRows && unknownAspectRatioMedia.length"
      class="media-ratio-probes"
      inert
      aria-hidden="true"
    >
      <ExplorerMediaCard
        v-for="item in unknownAspectRatioMedia"
        :key="item.id"
        :media="item"
        :width="1"
        :height="1"
        loading="eager"
        @aspect-ratio="rememberAspectRatio(item.id, $event)"
      />
    </div>

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
