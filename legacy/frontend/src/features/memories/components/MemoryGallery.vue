<script setup lang="ts">
// Encrypted media gallery for a memory: upload (sealed → direct-to-S3/proxied) + a justified, date-bucketed
// thumbnail grid (decrypted to object URLs) + a lightbox that decrypts the PREVIEW on open and the full-res
// original only on an explicit download. Images and videos both supported. Server/S3 only see ciphertext.
import { onMounted, ref } from 'vue'
import Button from 'primevue/button'
import ProgressSpinner from 'primevue/progressspinner'
import { useGallery } from '../../../features/gallery/useGallery'
import { loadGalleryTiles, loadOriginal, loadPreview } from '../../../features/gallery/gallery'
import { fileToNewMedia } from '../../../features/uploads/clientMedia'
import { uploadMedia } from '../../../features/uploads/media/media'
import { enqueueUploads } from '../../../features/uploads/useUploadQueue'
import type { ActiveSpace } from '../../../features/home/home'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()

const loading = ref(false)
const uploading = ref(false)
const uploadDone = ref(0)
const uploadTotal = ref(0)
const error = ref('')

const { tiles, buckets, tileById, galleryEl, lightbox, lightboxTile, downloading, observe, revokeTiles, openLightbox, closeLightbox, downloadThisOriginal } = useGallery({
  loadPreview: (tile) => loadPreview(session.apiBase, session.unlocked!, props.active, tile),
  loadOriginal: (tile) => loadOriginal(session.apiBase, session.unlocked!, props.active, tile),
  onError: (e) => { error.value = e.message },
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    revokeTiles()
    tiles.value = await loadGalleryTiles(session.apiBase, session.unlocked!, props.active, props.nodeId)
    observe() // (re)attach the ResizeObserver now the gallery has content + width
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function onPick(event: Event) {
  if (uploading.value) return // guard against a re-entrant change event kicking off a duplicate upload
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  uploading.value = true
  error.value = ''
  uploadDone.value = 0
  uploadTotal.value = files.length
  try {
    // Route through the global queue so the topbar status menu reflects progress (and it survives navigation).
    await enqueueUploads(files, async (file) => {
      const media = await fileToNewMedia(file)
      await uploadMedia(session.apiBase, session.unlocked!, props.active.scope.scopeId, props.active.contentKey, props.nodeId, media)
      uploadDone.value += 1
    })
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    uploading.value = false
  }
}

const isVideo = (mime?: string) => !!mime && mime.startsWith('video/')

// Lightbox info panel: all EXIF/capture metadata is sealed at upload and already decoded into the tile, so the
// panel just renders what's present (no extra fetch). Toggled per session.
const showInfo = ref(false)

function formatBytes(n?: number): string {
  if (!n) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let v = n
  let i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i += 1 }
  return `${v.toFixed(i ? 1 : 0)} ${units[i]}`
}

function formatTaken(iso?: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  return isNaN(d.getTime()) ? iso : d.toLocaleString()
}

onMounted(load)
</script>

<template>
  <div class="memory-gallery">
    <div class="gallery-toolbar">
      <label class="upload-trigger">
        <span class="p-button p-component">
          <span class="pi pi-upload" />
          <span class="p-button-label">{{ uploading ? t('detail.uploading') : t('detail.upload') }}</span>
        </span>
        <input type="file" accept="image/*,video/*" multiple class="upload-input" :disabled="uploading" @change="onPick" />
      </label>
      <span v-if="uploading" class="upload-count">{{ uploadDone }} / {{ uploadTotal }}</span>
    </div>

    <p v-if="error" class="gallery-error">{{ error }}</p>
    <div v-if="loading" class="veyq-loading"><ProgressSpinner class="small-spinner" /><span>{{ t('detail.loadingGallery') }}</span></div>

    <div ref="galleryEl" class="gallery-buckets">
      <section v-for="bucket in buckets" :key="bucket.key" class="gallery-bucket">
        <h3 class="bucket-date">{{ bucket.label }}</h3>
        <div class="gallery" :style="{ height: bucket.layout.height + 'px' }">
          <button
            v-for="tile in bucket.layout.tiles"
            :key="tile.id"
            type="button"
            class="gallery-tile"
            :style="{ left: tile.left + 'px', top: tile.top + 'px', width: tile.renderedWidth + 'px', height: tile.renderedHeight + 'px' }"
            @click="openLightbox(tileById[tile.id])"
          >
            <img :src="tileById[tile.id].url" alt="" loading="lazy" />
            <span v-if="isVideo(tileById[tile.id].mimeType)" class="tile-badge pi pi-play" />
            <span v-if="tileById[tile.id].highlighted" class="tile-star">★</span>
          </button>
        </div>
      </section>
    </div>
    <p v-if="!loading && !tiles.length" class="empty-hint">{{ t('detail.noMedia') }}</p>

    <div v-if="lightbox" class="lightbox" @click.self="closeLightbox()">
      <video v-if="isVideo(lightboxTile?.mimeType)" :src="lightbox" controls autoplay class="lightbox-media" />
      <img v-else :src="lightbox" alt="" class="lightbox-media" />
      <aside v-if="showInfo && lightboxTile" class="lightbox-info">
        <dl>
          <template v-if="lightboxTile.takenAt"><dt>{{ t('exif.takenAt') }}</dt><dd>{{ formatTaken(lightboxTile.takenAt) }}</dd></template>
          <template v-if="lightboxTile.cameraMake || lightboxTile.cameraModel"><dt>{{ t('exif.camera') }}</dt><dd>{{ [lightboxTile.cameraMake, lightboxTile.cameraModel].filter(Boolean).join(' ') }}</dd></template>
          <template v-if="lightboxTile.lens"><dt>{{ t('exif.lens') }}</dt><dd>{{ lightboxTile.lens }}</dd></template>
          <template v-if="lightboxTile.width && lightboxTile.height"><dt>{{ t('exif.dimensions') }}</dt><dd>{{ lightboxTile.width }} × {{ lightboxTile.height }}</dd></template>
          <template v-if="lightboxTile.fileSize"><dt>{{ t('exif.fileSize') }}</dt><dd>{{ formatBytes(lightboxTile.fileSize) }}</dd></template>
          <template v-if="lightboxTile.lat != null && lightboxTile.lng != null"><dt>{{ t('exif.location') }}</dt><dd>{{ lightboxTile.lat.toFixed(5) }}, {{ lightboxTile.lng.toFixed(5) }}</dd></template>
        </dl>
        <template v-if="lightboxTile.exif && Object.keys(lightboxTile.exif).length">
          <h4>{{ t('exif.details') }}</h4>
          <dl><template v-for="(v, k) in lightboxTile.exif" :key="k"><dt>{{ k }}</dt><dd>{{ v }}</dd></template></dl>
        </template>
      </aside>
      <div class="lightbox-actions">
        <Button type="button" icon="pi pi-info-circle" :label="t('exif.info')" :severity="showInfo ? undefined : 'secondary'" @click="showInfo = !showInfo" />
        <Button type="button" icon="pi pi-download" :label="t('detail.download')" :loading="downloading" @click="downloadThisOriginal()" />
        <Button type="button" icon="pi pi-times" :label="t('common.close')" severity="secondary" @click="closeLightbox()" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.memory-gallery { margin: 0.5rem 0 1rem; }
.gallery-toolbar { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.75rem; }
.upload-trigger { cursor: pointer; }
.upload-input { display: none; }
.upload-count { opacity: 0.7; }
.gallery-error { color: var(--p-red-400, #f87171); }
.gallery-bucket { margin-bottom: 1.25rem; }
.bucket-date { font-size: 0.95rem; opacity: 0.7; margin: 0 0 0.5rem; }
.gallery { position: relative; width: 100%; }
.gallery-tile { position: absolute; padding: 0; border: none; background: none; cursor: pointer; overflow: hidden; border-radius: 6px; }
.gallery-tile img { width: 100%; height: 100%; object-fit: cover; display: block; }
.tile-badge { position: absolute; left: 6px; bottom: 6px; color: #fff; text-shadow: 0 1px 2px rgba(0,0,0,0.6); }
.tile-star { position: absolute; right: 6px; top: 6px; color: #fbbf24; text-shadow: 0 1px 2px rgba(0,0,0,0.6); }
.empty-hint { opacity: 0.6; }
.lightbox { position: fixed; inset: 0; z-index: 1000; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 1rem; background: rgba(0,0,0,0.85); padding: 2rem; }
.lightbox-media { max-width: 92vw; max-height: 80vh; object-fit: contain; border-radius: 8px; }
.lightbox-actions { display: flex; gap: 0.5rem; }
.lightbox-info { position: absolute; top: 1rem; right: 1rem; max-width: 340px; max-height: 80vh; overflow: auto; background: rgba(0,0,0,0.72); color: #fff; border-radius: 8px; padding: 0.75rem 1rem; font-size: 0.85rem; }
.lightbox-info dl { display: grid; grid-template-columns: auto 1fr; gap: 0.15rem 0.75rem; margin: 0; }
.lightbox-info dt { opacity: 0.65; white-space: nowrap; }
.lightbox-info dd { margin: 0; word-break: break-word; }
.lightbox-info h4 { margin: 0.75rem 0 0.25rem; font-size: 0.8rem; opacity: 0.85; }
</style>
