<script setup lang="ts">
// Cover picker: choose a memory's cover from its gallery thumbnails (operator-blind — thumbnails are decrypted
// client-side, setCover only pins the chosen media id on the node). Mirrors the old MemoryCoverDialog's
// gallery-pick path; the 16:9 crop the old UI offered is dropped (covers render object-fit:cover everywhere).
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { loadGalleryTiles, type GalleryTile } from '../../gallery/gallery'
import { setCover } from '../../memory/curate/curate'
import { requireSession, session } from '../../../app/session'
import type { ActiveSpace } from '../../../features/home/home'

const props = defineProps<{ visible: boolean; active: ActiveSpace; nodeId: string; coverMediaId?: string | null }>()
const emit = defineEmits<{ 'update:visible': [boolean]; saved: [coverMediaId: string] }>()
const { t } = useI18n()

const tiles = ref<GalleryTile[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')

watch(() => props.visible, (open) => { if (open) load() })

async function load() {
  loading.value = true
  error.value = ''
  try {
    tiles.value.forEach((tile) => URL.revokeObjectURL(tile.url))
    tiles.value = await loadGalleryTiles(session.apiBase, requireSession(), props.active, props.nodeId)
  } catch {
    error.value = t('memory.coverSaveFailed')
  } finally {
    loading.value = false
  }
}

async function choose(tile: GalleryTile) {
  busy.value = true
  error.value = ''
  try {
    await setCover(session.apiBase, requireSession(), props.active, props.nodeId, tile.id)
    emit('saved', tile.id)
    emit('update:visible', false)
  } catch {
    error.value = t('memory.coverSaveFailed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="t('memory.coverChoose')"
    modal
    class="tenant-dialog memory-cover-dialog"
    @update:visible="emit('update:visible', $event)"
  >
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <p class="cover-dialog-copy">{{ t('memory.coverGallery') }}</p>

    <div v-if="loading" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('common.loading') }}</span>
    </div>

    <p v-else-if="!tiles.length" class="gallery-empty">{{ t('gallery.empty') }}</p>

    <div v-else class="cover-grid" :class="{ 'cover-grid-busy': busy }">
      <button
        v-for="tile in tiles"
        :key="tile.id"
        type="button"
        class="cover-grid-tile"
        :class="{ 'cover-grid-tile-current': tile.id === coverMediaId }"
        :disabled="busy"
        @click="choose(tile)"
      >
        <img :src="tile.url" alt="" loading="lazy" />
        <span v-if="tile.id === coverMediaId" class="pi pi-check cover-grid-current-mark" />
      </button>
    </div>
  </Dialog>
</template>

<style scoped>
.cover-dialog-copy { opacity: 0.7; font-size: 0.9rem; margin: 0 0 0.75rem; }
.gallery-empty { opacity: 0.55; font-size: 0.9rem; }
.cover-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 0.5rem;
  max-height: 60vh;
  overflow-y: auto;
}
.cover-grid-busy { opacity: 0.6; pointer-events: none; }
.cover-grid-tile {
  position: relative;
  padding: 0;
  border: 2px solid transparent;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  aspect-ratio: 1;
  background: var(--p-content-border-color, rgba(127, 127, 127, 0.15));
}
.cover-grid-tile:hover { border-color: var(--p-primary-color, #5b4df5); }
.cover-grid-tile-current { border-color: var(--p-primary-color, #5b4df5); }
.cover-grid-tile img { width: 100%; height: 100%; object-fit: cover; display: block; }
.cover-grid-current-mark {
  position: absolute;
  top: 0.35rem;
  right: 0.35rem;
  background: var(--p-primary-color, #5b4df5);
  color: #fff;
  border-radius: 50%;
  padding: 0.2rem;
  font-size: 0.7rem;
}
</style>
