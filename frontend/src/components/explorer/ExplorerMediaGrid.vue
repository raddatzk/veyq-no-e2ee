<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import ProgressSpinner from 'primevue/progressspinner'
import ExplorerMediaCard from './ExplorerMediaCard.vue'
import ExplorerMediaLightbox from './ExplorerMediaLightbox.vue'
import type { ExplorerMedia } from '../../api/explorerClient'

const props = defineProps<{
  media: ExplorerMedia[]
  streaming: boolean
}>()

const selectedIndex = ref<number | null>(null)
const selectedMedia = computed(() =>
  selectedIndex.value === null ? null : props.media[selectedIndex.value] || null
)
const isLightboxOpen = computed(() => selectedMedia.value !== null)
const canShowPrevious = computed(() => selectedIndex.value !== null && selectedIndex.value > 0)
const canShowNext = computed(
  () => selectedIndex.value !== null && selectedIndex.value < props.media.length - 1
)

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
})

function openLightbox(index: number) {
  selectedIndex.value = index
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
    <div v-if="media.length" class="media-grid">
      <ExplorerMediaCard
        v-for="(item, index) in media"
        :key="item.id"
        :media="item"
        @open="openLightbox(index)"
      />
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
