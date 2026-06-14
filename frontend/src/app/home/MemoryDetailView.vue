<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { session } from '../session'
import { calculateJustifiedLayout } from '../../features/gallery/justifiedLayout'
import { loadGalleryTiles, loadOriginal, type GalleryTile } from '../../features/gallery/gallery'
import { fileToNewMedia } from '../../features/uploads/clientMedia'
import { uploadMedia } from '../../features/uploads/media/media'
import type { ActiveSpace, MemoryNode } from '../../features/home/home'

const props = defineProps<{ active: ActiveSpace; memory: MemoryNode; name: string }>()
const emit = defineEmits<{ back: [] }>()

const tiles = ref<GalleryTile[]>([])
const loading = ref(true)
const containerWidth = ref(0)
const galleryEl = ref<HTMLElement | null>(null)
const lightbox = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
let ro: ResizeObserver | null = null

const layout = computed(() =>
  calculateJustifiedLayout({
    items: tiles.value.map((t) => ({ id: t.id, aspectRatio: t.aspectRatio })),
    containerWidth: containerWidth.value,
    targetHeight: 220,
    gap: 6,
  }),
)
const tileById = computed(() => Object.fromEntries(tiles.value.map((t) => [t.id, t])))

onMounted(async () => {
  ro = new ResizeObserver((entries) => { containerWidth.value = entries[0].contentRect.width })
  if (galleryEl.value) ro.observe(galleryEl.value)
  try {
    tiles.value = await loadGalleryTiles(session.apiBase, session.unlocked!, props.active, props.memory.id)
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  ro?.disconnect()
  tiles.value.forEach((t) => URL.revokeObjectURL(t.url))
  if (lightbox.value) URL.revokeObjectURL(lightbox.value)
})

async function refresh() {
  tiles.value.forEach((t) => URL.revokeObjectURL(t.url))
  tiles.value = await loadGalleryTiles(session.apiBase, session.unlocked!, props.active, props.memory.id)
}

async function onFilesPicked(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  if (!files.length) return
  uploading.value = true
  try {
    for (const file of files) {
      const media = await fileToNewMedia(file)
      await uploadMedia(session.apiBase, session.unlocked!, props.active.scope.scopeId, props.active.contentKey, props.memory.id, media)
    }
    await refresh()
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function openLightbox(tile: GalleryTile) {
  lightbox.value = await loadOriginal(session.apiBase, session.unlocked!, props.active, tile)
}
function closeLightbox() {
  if (lightbox.value) URL.revokeObjectURL(lightbox.value)
  lightbox.value = null
}
</script>

<template>
  <div class="memory-detail">
    <div class="detail-head">
      <button class="crumb" @click="emit('back')">← Zurück</button>
      <h1 class="detail-title">{{ name }}</h1>
      <span class="content-count">{{ tiles.length }} Medien</span>
      <button class="btn btn-primary detail-upload" type="button" :disabled="uploading" @click="fileInput?.click()">
        {{ uploading ? 'Lädt hoch…' : 'Hochladen' }}
      </button>
      <input ref="fileInput" type="file" accept="image/*,video/*" multiple hidden @change="onFilesPicked" />
    </div>

    <div ref="galleryEl" class="gallery" :style="{ height: layout.height + 'px' }">
      <button
        v-for="tile in layout.tiles"
        :key="tile.id"
        class="gallery-tile"
        :style="{ left: tile.left + 'px', top: tile.top + 'px', width: tile.renderedWidth + 'px', height: tile.renderedHeight + 'px' }"
        @click="openLightbox(tileById[tile.id])"
      >
        <img :src="tileById[tile.id].url" alt="" />
      </button>
    </div>

    <div v-if="loading" class="home-empty">Lädt Galerie…</div>
    <div v-else-if="!tiles.length" class="home-empty">Noch keine Medien.</div>

    <div v-if="lightbox" class="lightbox" @click="closeLightbox"><img :src="lightbox" alt="" /></div>
  </div>
</template>
