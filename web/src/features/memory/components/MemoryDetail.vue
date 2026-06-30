<script setup lang="ts">
// A Memory's detail surface = its **gallery** (cr-0049, uc.memory.gallery). Opens the Memory's media as a
// thumbnail grid, each tile's thumbnail fetched as ciphertext via presigned GET and decrypted on device under
// K_mem (operator-blind); clicking a tile opens the lightbox (preview, prev/next, download original). The host
// view (MemoryHomeView) owns the rename/delete dialogs + the favorites store and unwraps K_mem from the tree.
// Ported presentation from the design source (legacy/frontend-noe2ee); only the data layer is ours. Decrypted
// thumbnails become object URLs that are **revoked** on reload / unmount (no lingering plaintext blob URLs).
// Out of scope (own CRs): journal, chapters + chapter-nested media, map/tracks, playlists, buckets, sharing.
import { ref, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import type { TreeNode } from '../tree'
import { listMedia, openGrid, hasDerivatives, type MediaItem } from '../media'
import MediaLightbox from './MediaLightbox.vue'

const props = defineProps<{ node: TreeNode; scopeId: string; memoryKey: Uint8Array | null; favorite?: boolean }>()
defineEmits<{ toggleFavorite: []; rename: []; delete: [] }>()

const { t } = useI18n()

const items = ref<MediaItem[]>([])
const thumbs = ref<Map<string, string>>(new Map()) // mediaId → thumbnail object URL (derivable items only)
const status = ref<'loading' | 'ready' | 'error'>('loading')
const lightboxIndex = ref<number | null>(null)

function revokeThumbs() {
  for (const url of thumbs.value.values()) URL.revokeObjectURL(url)
  thumbs.value = new Map()
}

/** List the Memory's media and fetch each derivable item's thumbnail tier (parallel, operator-blind). */
async function load() {
  revokeThumbs()
  items.value = []
  status.value = 'loading'
  const key = props.memoryKey
  if (!key) {
    status.value = 'error' // E2: no K_mem (locked / not openable) → gallery unavailable
    return
  }
  const nodeId = props.node.id
  try {
    const listed = await listMedia(props.scopeId, nodeId)
    if (props.node.id !== nodeId) return // navigated away while listing
    items.value = listed
    status.value = 'ready'
    await Promise.all(
      listed.map(async (m) => {
        if (!hasDerivatives(m)) return // E1: non-image/video → icon tile, no fetch
        try {
          const bytes = await openGrid(props.scopeId, key, m)
          if (props.node.id !== nodeId) return
          thumbs.value.set(m.mediaId, URL.createObjectURL(new Blob([bytes as BlobPart])))
          thumbs.value = new Map(thumbs.value) // reassign so the template reacts
        } catch {
          // a single failed tile stays a placeholder; the rest of the grid is unaffected
        }
      }),
    )
  } catch {
    if (props.node.id === nodeId) status.value = 'error'
  }
}

watch(
  () => [props.node.id, props.memoryKey] as const,
  () => {
    lightboxIndex.value = null
    void load()
  },
  { immediate: true },
)

onBeforeUnmount(revokeThumbs)
</script>

<template>
  <div class="memory-route-surface memory-detail">
    <header class="gallery-header">
      <div class="gallery-header-copy">
        <h1 class="gallery-title">{{ node.name }}</h1>
        <span class="gallery-count">{{ t('media.itemCount', { count: items.length }, items.length) }}</span>
      </div>
      <div class="gallery-header-actions">
        <Button
          type="button"
          class="memory-detail-fav"
          :class="{ 'is-favorite': favorite }"
          :label="favorite ? t('memory.unfavorite') : t('memory.favorite')"
          :icon="favorite ? 'pi pi-heart-fill' : 'pi pi-heart'"
          severity="secondary"
          text
          @click="$emit('toggleFavorite')"
        />
        <Button
          type="button"
          :label="t('memory.rename')"
          icon="pi pi-pencil"
          severity="secondary"
          text
          @click="$emit('rename')"
        />
        <Button
          type="button"
          :label="t('memory.delete')"
          icon="pi pi-trash"
          severity="danger"
          text
          @click="$emit('delete')"
        />
      </div>
    </header>

    <div v-if="status === 'loading'" class="media-grid-placeholder">
      <span class="pi pi-spin pi-spinner" />
      <span>{{ t('media.loading') }}</span>
    </div>
    <p v-else-if="status === 'error'" class="veyq-empty">{{ t('media.unavailable') }}</p>
    <p v-else-if="!items.length" class="veyq-empty">{{ t('media.empty') }}</p>
    <div v-else class="gallery-grid" :aria-label="t('media.gallery')">
      <button
        v-for="(media, i) in items"
        :key="media.mediaId"
        class="gallery-tile"
        type="button"
        :aria-label="t('media.openItem', { index: i + 1 })"
        @click="lightboxIndex = i"
      >
        <img
          v-if="thumbs.get(media.mediaId)"
          class="media-grid-tile-media media-grid-tile-media-loaded"
          :src="thumbs.get(media.mediaId)"
          :alt="node.name"
        />
        <span v-else-if="!hasDerivatives(media)" class="media-grid-icon pi pi-file" />
        <span v-else class="media-grid-tile-placeholder" />
      </button>
    </div>

    <MediaLightbox
      v-if="lightboxIndex !== null && memoryKey"
      :scope-id="scopeId"
      :memory-key="memoryKey"
      :items="items"
      :start-index="lightboxIndex"
      :memory-name="node.name"
      @close="lightboxIndex = null"
    />
  </div>
</template>

<style scoped>
.gallery-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 1.15rem;
  border-bottom: 1px solid var(--app-line);
}
.gallery-header-copy {
  display: grid;
  gap: 0.3rem;
  min-width: 0;
}
.gallery-title {
  margin: 0;
  font-size: 2rem;
  line-height: 1.1;
  overflow-wrap: anywhere;
}
.gallery-count {
  color: var(--app-muted);
  font-size: 0.9rem;
  font-weight: 700;
}
.gallery-header-actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 0.5rem;
}
/* Narrow viewports: stack the actions under the title so the title keeps the full row width (otherwise the
   actions squeeze it into a sliver and it breaks mid-word). */
@media (max-width: 640px) {
  .gallery-header {
    flex-direction: column;
  }
  .gallery-header-actions {
    justify-content: flex-start;
  }
  .gallery-title {
    font-size: 1.5rem;
  }
}
/* The active heart is a heart-red blended with the brand accent, matching the card pin. */
.memory-detail-fav.is-favorite :deep(.p-button-icon) {
  color: color-mix(in srgb, var(--app-accent) 45%, #e11d48);
}

/* A uniform square-tile grid (the thumbnails are capped at 400px). The design's justified-rows layout +
   lazy/virtualized loading are a later optimization, not part of this slice (cr-0049). The tile-inner classes
   (.media-grid-tile-media / -placeholder / -icon) are the design source's (media.css). */
.gallery-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 160px), 1fr));
  gap: 0.5rem;
}
.gallery-tile {
  position: relative;
  display: block;
  aspect-ratio: 1;
  overflow: hidden;
  padding: 0;
  border: 0;
  border-radius: var(--app-radius-sm);
  background: var(--app-control-bg);
  color: var(--app-muted);
  cursor: pointer;
}
.gallery-tile img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.gallery-tile .media-grid-icon {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  font-size: 1.5rem;
}
</style>
