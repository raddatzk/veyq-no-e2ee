<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ProgressSpinner from 'primevue/progressspinner'
import ExplorerMediaCard from './ExplorerMediaCard.vue'
import ExplorerMediaLightbox from './ExplorerMediaLightbox.vue'
import { buildJustifiedMediaRows } from './mediaGridLayout'
import type { MediaGridLayoutRow } from './mediaGridLayout'
import type { ExplorerMedia } from '../../api/explorerClient'

const props = defineProps<{
  tenantName: string
  media: ExplorerMedia[]
  streaming: boolean
}>()

const selectedIndex = ref<number | null>(null)
const mediaGrid = ref<HTMLElement | null>(null)
const mediaGridWidth = ref(0)
const aspectRatios = ref<Record<string, number>>({})
const mediaOverrides = ref<Record<string, ExplorerMedia>>({})
const committedRows = ref<MediaGridLayoutRow[]>([])
let mediaGridObserver: ResizeObserver | null = null
let observedMediaGrid: HTMLElement | null = null
let aspectRatioFrame: number | null = null
let pendingAspectRatios: Record<string, number> = {}
const committedMediaIds = computed(() =>
  committedRows.value.flatMap((row) => row.items.map((item) => item.id))
)
const currentMediaIds = computed(() => props.media.map((item) => item.id))
const mediaById = computed(() => new Map(props.media.map((item) => [item.id, item])))
const visibleMedia = computed(() =>
  committedMediaIds.value
    .map((mediaId) => mediaById.value.get(mediaId))
    .filter((item): item is ExplorerMedia => Boolean(item))
)
const selectedMedia = computed(() =>
  selectedIndex.value === null
    ? null
    : mediaOverrides.value[visibleMedia.value[selectedIndex.value]?.id] ||
      visibleMedia.value[selectedIndex.value] ||
      null
)
const isLightboxOpen = computed(() => selectedMedia.value !== null)
const canShowPrevious = computed(() => selectedIndex.value !== null && selectedIndex.value > 0)
const canShowNext = computed(
  () => selectedIndex.value !== null && selectedIndex.value < visibleMedia.value.length - 1
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
const canRenderMediaRows = computed(() => committedRows.value.length > 0)
const mediaRows = computed(() => committedRows.value)
const layoutInputSignature = computed(() =>
  currentMediaIds.value.map((mediaId) => `${mediaId}:${layoutAspectRatios.value[mediaId] ?? ''}`).join('|')
)

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
  () => {
    if (selectedIndex.value !== null && selectedIndex.value >= visibleMedia.value.length) closeLightbox()
    attachMediaGridObserver()
  }
)

watch(
  () => props.media.map((item) => item.id).join('|'),
  () => {
    if (!props.media.length) {
      committedRows.value = []
      aspectRatios.value = {}
      pendingAspectRatios = {}
      if (aspectRatioFrame !== null) {
        cancelAnimationFrame(aspectRatioFrame)
        aspectRatioFrame = null
      }
      return
    }

    const currentIds = new Set(props.media.map((item) => item.id))
    if (!isPrefix(committedMediaIds.value, props.media.map((item) => item.id))) {
      committedRows.value = []
      closeLightbox()
    }
    aspectRatios.value = Object.fromEntries(
      Object.entries(aspectRatios.value).filter(([mediaId]) => currentIds.has(mediaId))
    )
    pendingAspectRatios = Object.fromEntries(
      Object.entries(pendingAspectRatios).filter(([mediaId]) => currentIds.has(mediaId))
    )
  }
)

watch(
  [layoutInputSignature, layoutWidth, minTileHeight, targetTileHeight, maxTileHeight, () => props.streaming],
  commitStableRows,
  { flush: 'post' }
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
  return visibleMedia.value.findIndex((item) => item.id === mediaId)
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

function commitStableRows() {
  if (!props.media.length || mediaGridWidth.value <= 0) {
    committedRows.value = []
    return
  }

  const ids = currentMediaIds.value
  const committedIds = committedMediaIds.value
  const prefixIds = isPrefix(committedIds, ids) ? committedIds : []
  const committedPrefixRows = prefixIds.length ? buildRows(prefixIds) : []
  const suffixIds = ids.slice(prefixIds.length)
  const knownSuffixIds = leadingKnownAspectRatioIds(suffixIds)
  const candidateRows = knownSuffixIds.length ? buildRows(knownSuffixIds) : []
  const publishableRows = props.streaming ? candidateRows.filter((row) => !row.widow) : candidateRows

  committedRows.value = [...committedPrefixRows, ...publishableRows]
}

function buildRows(ids: string[]): MediaGridLayoutRow[] {
  if (!ids.length) return []
  return buildJustifiedMediaRows({
    ids,
    aspectRatios: layoutAspectRatios.value,
    containerWidth: layoutWidth.value,
    targetHeight: targetTileHeight.value,
    minHeight: minTileHeight.value,
    maxHeight: maxTileHeight.value,
    gap: 4
  })
}

function leadingKnownAspectRatioIds(ids: string[]): string[] {
  const result: string[] = []
  for (const mediaId of ids) {
    if (!layoutAspectRatios.value[mediaId]) break
    result.push(mediaId)
  }
  return result
}

function isPrefix(prefix: string[], values: string[]): boolean {
  return prefix.every((value, index) => values[index] === value)
}

function updateMedia(media: ExplorerMedia) {
  mediaOverrides.value = { ...mediaOverrides.value, [media.id]: media }
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
            loading="eager"
            @open="openLightbox(mediaIndex(layoutItem.id))"
            @aspect-ratio="rememberAspectRatio(layoutItem.id, $event)"
          />
        </div>
      </template>
      <div v-else class="media-grid-placeholder">
        <ProgressSpinner class="small-spinner" />
        <span>{{ streaming ? 'Medien werden geladen...' : 'Medien werden vorbereitet...' }}</span>
      </div>
    </div>
    <div v-else-if="streaming" class="explorer-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Medien werden geladen...</span>
    </div>
    <div v-else class="explorer-empty">Keine Medien unter diesem Pfad.</div>
    <div
      v-if="media.length && unknownAspectRatioMedia.length"
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
      :tenant-name="tenantName"
      :media="selectedMedia"
      :can-show-previous="canShowPrevious"
      :can-show-next="canShowNext"
      @close="closeLightbox"
      @previous="showPrevious"
      @next="showNext"
      @updated="updateMedia"
    />
  </section>
</template>
