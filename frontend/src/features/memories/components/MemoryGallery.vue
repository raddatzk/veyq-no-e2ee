<script setup lang="ts">
// A memory's media as a justified, day-bucketed gallery (E2EE). Thumbnails are decrypted to object URLs by
// loadGalleryTiles; the lightbox loads the larger PREVIEW on open and the full-res original only on an
// explicit download. Layout + lifecycle come from the shared useGallery composable.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { loadGalleryTiles, loadPreview, loadOriginal } from '../../gallery/gallery'
import { useGallery } from '../../gallery/useGallery'
import { uploadMedia } from '../../uploads/media/media'
import { fileToNewMedia } from '../../uploads/clientMedia'
import { enqueueUploads } from '../../uploads/useUploadQueue'
import { requireSession, session } from '../../../app/session'
import type { ActiveSpace } from '../../../features/home/home'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()
const { t } = useI18n()
const uploadError = ref('')
const uploadingCount = ref(0)

const {
  tiles, galleryEl, buckets, tileById, lightbox, downloading,
  observe, revokeTiles, openLightbox, closeLightbox, downloadThisOriginal,
} = useGallery({
  loadPreview: (tile) => loadPreview(session.apiBase, requireSession(), props.active, tile),
  loadOriginal: (tile) => loadOriginal(session.apiBase, requireSession(), props.active, tile),
})

async function reload() {
  revokeTiles()
  tiles.value = await loadGalleryTiles(session.apiBase, requireSession(), props.active, props.nodeId)
  observe()
}

onMounted(reload)

async function onUpload(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0) return
  uploadError.value = ''
  uploadingCount.value = files.length
  try {
    await enqueueUploads(files, async (file) => {
      const media = await fileToNewMedia(file)
      await uploadMedia(session.apiBase, requireSession(), props.active.scope.scopeId, props.active.contentKey, props.nodeId, media)
    })
    await reload()
  } catch {
    uploadError.value = t('gallery.uploadFailed')
  } finally {
    uploadingCount.value = 0
  }
}
</script>

<template>
  <div ref="galleryEl" class="memory-gallery">
    <div class="gallery-toolbar">
      <label class="kit-file-trigger">
        <span class="p-button p-component p-button-secondary">
          <i class="pi pi-upload" />&nbsp;{{ uploadingCount > 0 ? t('gallery.uploading', { count: uploadingCount }) : t('gallery.upload') }}
        </span>
        <input class="kit-file-input" type="file" accept="image/*,video/*" multiple :disabled="uploadingCount > 0" @change="onUpload" />
      </label>
    </div>
    <Message v-if="uploadError" severity="error" :closable="false">{{ uploadError }}</Message>

    <p v-if="tiles.length === 0" class="gallery-empty">{{ t('gallery.empty') }}</p>

    <section v-for="bucket in buckets" :key="bucket.key" class="gallery-bucket">
      <h3 class="gallery-day">{{ bucket.label }}</h3>
      <div class="gallery-rows" :style="{ height: bucket.layout.height + 'px' }">
        <button
          v-for="tile in bucket.layout.tiles"
          :key="tile.id"
          type="button"
          class="gallery-tile"
          :style="{ left: tile.left + 'px', top: tile.top + 'px', width: tile.renderedWidth + 'px', height: tile.renderedHeight + 'px' }"
          @click="openLightbox(tileById[tile.id])"
        >
          <img :src="tileById[tile.id]?.url" alt="" loading="lazy" />
        </button>
      </div>
    </section>

    <!-- Lightbox -->
    <div v-if="lightbox" class="gallery-lightbox" @click.self="closeLightbox()">
      <div class="gallery-lightbox-inner">
        <img :src="lightbox" alt="" />
        <div class="gallery-lightbox-actions">
          <Button type="button" icon="pi pi-download" :label="t('gallery.download')" :loading="downloading" severity="secondary" @click="downloadThisOriginal()" />
          <Button type="button" icon="pi pi-times" :label="t('common.close')" text @click="closeLightbox()" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.memory-gallery { margin-bottom: 1.5rem; }
.gallery-empty { opacity: 0.55; font-size: 0.9rem; }
.gallery-day { font-size: 0.95rem; opacity: 0.7; margin: 1rem 0 0.5rem; }
.gallery-rows { position: relative; width: 100%; }
.gallery-tile {
  position: absolute;
  padding: 0;
  border: none;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  background: var(--p-content-border-color, rgba(127, 127, 127, 0.15));
}
.gallery-tile img { width: 100%; height: 100%; object-fit: cover; display: block; }
.gallery-lightbox {
  position: fixed; inset: 0; z-index: 1000;
  display: flex; align-items: center; justify-content: center;
  background: rgba(0, 0, 0, 0.85); padding: 2rem;
}
.gallery-lightbox-inner { display: flex; flex-direction: column; align-items: center; gap: 0.75rem; max-width: 92vw; max-height: 92vh; }
.gallery-lightbox-inner img { max-width: 92vw; max-height: 80vh; border-radius: 8px; object-fit: contain; }
.gallery-lightbox-actions { display: flex; gap: 0.5rem; }
</style>
