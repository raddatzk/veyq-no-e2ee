<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { fetchCuratedPhotos } from './api';
import { calculateJustifiedLayout } from './layout';
import type { GalleryItem, LayoutResult } from './types';

const apiKey = import.meta.env.VITE_PEXELS_API_KEY as string | undefined;

const gridElement = ref<HTMLElement | null>(null);
const items = ref<GalleryItem[]>([]);
const layout = ref<LayoutResult>({ tiles: [], height: 0 });
const isLoadingMetadata = ref(false);
const shouldLoadImages = ref(false);
const errorMessage = ref('');
const containerWidth = ref(0);

let resizeObserver: ResizeObserver | null = null;

const statusText = computed(() => {
  if (isLoadingMetadata.value) {
    return 'Metadaten werden geladen';
  }

  if (shouldLoadImages.value && layout.value.tiles.length > 0) {
    return `${layout.value.tiles.length} Bilder`;
  }

  return 'Bereit';
});

function recalculateLayout() {
  layout.value = calculateJustifiedLayout(items.value, containerWidth.value);
}

async function loadGallery() {
  const trimmedApiKey = apiKey?.trim();

  if (!trimmedApiKey) {
    errorMessage.value = 'Bitte VITE_PEXELS_API_KEY in .env.local setzen und den Dev-Server neu starten.';
    return;
  }

  isLoadingMetadata.value = true;
  shouldLoadImages.value = false;
  errorMessage.value = '';

  try {
    items.value = await fetchCuratedPhotos(trimmedApiKey);
    recalculateLayout();
    await nextTick();
    shouldLoadImages.value = true;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Unbekannter Fehler beim Laden der Bilder.';
  } finally {
    isLoadingMetadata.value = false;
  }
}

watch(containerWidth, recalculateLayout);

onMounted(() => {
  if (gridElement.value) {
    resizeObserver = new ResizeObserver((entries) => {
      const entry = entries[0];
      containerWidth.value = Math.floor(entry.contentRect.width);
    });

    resizeObserver.observe(gridElement.value);
  }

  void loadGallery();
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
});
</script>

<template>
  <main class="app-shell">
    <header class="topbar">
      <div>
        <p class="eyebrow">Pexels Curated</p>
        <h1>Justified Grid</h1>
      </div>
      <span class="status-pill">{{ statusText }}</span>
    </header>

    <section v-if="errorMessage" class="state-panel" role="alert">
      {{ errorMessage }}
    </section>

    <section v-else-if="isLoadingMetadata" class="state-panel">
      Metadaten fuer die ersten 20 Bilder werden geladen.
    </section>

    <section v-else-if="items.length === 0" class="state-panel">
      Keine Bilder gefunden.
    </section>

    <section
      ref="gridElement"
      class="gallery"
      :style="{ height: `${layout.height}px` }"
      aria-label="Pexels Bildergalerie"
    >
      <a
        v-for="tile in layout.tiles"
        :key="tile.id"
        class="tile"
        :href="tile.pageUrl"
        target="_blank"
        rel="noreferrer"
        :style="{
          transform: `translate(${tile.left}px, ${tile.top}px)`,
          width: `${tile.renderedWidth}px`,
          height: `${tile.renderedHeight}px`,
        }"
      >
        <img
          v-if="shouldLoadImages"
          :src="tile.imageUrl"
          :alt="tile.alt"
          loading="lazy"
          decoding="async"
        />
        <div v-else class="placeholder" />
        <span class="credit">{{ tile.photographer }}</span>
      </a>
    </section>
  </main>
</template>
