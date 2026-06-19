<script setup lang="ts">
import { ref } from 'vue'
import { t } from '../../i18n/i18n'
import type { GalleryTile } from '../../features/gallery/gallery'
import type { Bucket } from '../../features/memory/buckets/buckets'

const props = defineProps<{
  imageUrl: string
  tile: GalleryTile | null
  tileCount: number
  downloading: boolean
  canMoveTile: boolean
  moveBuckets: Bucket[]
  ownerKey?: string
  bucketLabel?: (bucket: Bucket) => string
  uploaderName?: string
  uploaderUserId?: string
}>()

defineSlots<{
  actions?: (props: { tile: GalleryTile | null }) => unknown
}>()

const emit = defineEmits<{
  close: []
  step: [direction: number]
  moveTile: [bucketId: string]
  download: []
  openUploader: []
}>()

const showInfo = ref(false)
const imageEl = ref<HTMLImageElement | null>(null)

function toggleFullscreen() {
  if (document.fullscreenElement) void document.exitFullscreen()
  else void imageEl.value?.requestFullscreen?.()
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

function moveTile(event: Event) {
  emit('moveTile', (event.target as HTMLSelectElement).value)
}
</script>

<template>
  <div class="lightbox" @click="emit('close')">
    <button v-if="tileCount > 1" class="lightbox-nav lightbox-prev" type="button" :aria-label="t('common.previous')" @click.stop="emit('step', -1)">
      <span aria-hidden="true">‹</span>
    </button>
    <img ref="imageEl" class="lightbox-image" :src="imageUrl" alt="" @click.stop="toggleFullscreen" />
    <button v-if="tileCount > 1" class="lightbox-nav lightbox-next" type="button" :aria-label="t('common.next')" @click.stop="emit('step', 1)">
      <span aria-hidden="true">›</span>
    </button>
    <div v-if="showInfo && tile" class="lightbox-info" @click.stop>
      <div><span>{{ t('info.takenAt') }}</span><strong>{{ new Date(tile.takenAt).toLocaleString() }}</strong></div>
      <div><span>{{ t('info.type') }}</span><strong>{{ tile.mimeType }}</strong></div>
      <div v-if="tile.lat != null"><span>{{ t('info.location') }}</span><strong>{{ tile.lat.toFixed(5) }}, {{ tile.lng!.toFixed(5) }}</strong></div>
      <div v-if="tile.cameraModel"><span>{{ t('info.camera') }}</span><strong>{{ [tile.cameraMake, tile.cameraModel].filter(Boolean).join(' ') }}</strong></div>
      <div v-if="tile.lens"><span>{{ t('info.lens') }}</span><strong>{{ tile.lens }}</strong></div>
      <div v-if="tile.width && tile.height"><span>{{ t('info.dimensions') }}</span><strong>{{ tile.width }}×{{ tile.height }}</strong></div>
      <div v-if="tile.fileSize"><span>{{ t('info.fileSize') }}</span><strong>{{ formatBytes(tile.fileSize) }}</strong></div>
      <div v-if="uploaderName"><span>{{ t('media.uploader') }}</span><strong>{{ uploaderName }}</strong></div>
      <div v-for="(val, key) in (tile.exif ?? {})" :key="key"><span>{{ key }}</span><strong>{{ val }}</strong></div>
    </div>
    <div class="lightbox-actions" @click.stop>
      <button v-if="uploaderName && uploaderUserId" class="btn btn-ghost lightbox-uploader" type="button" @click="emit('openUploader')">
        {{ t('media.uploadedBy', { name: uploaderName }) }}
      </button>
      <span v-else-if="uploaderName" class="lightbox-uploader lightbox-uploader-static">
        {{ t('media.uploadedBy', { name: uploaderName }) }}
      </span>
      <slot name="actions" :tile="tile" />
      <select
        v-if="canMoveTile && tile"
        class="bucket-move"
        :aria-label="t('move.title')"
        :value="tile.bucketId ?? (ownerKey ? `primary:${ownerKey}` : '')"
        @change="moveTile"
      >
        <option v-for="bucket in moveBuckets" :key="bucket.id" :value="bucket.id">{{ bucket.primary ? t('contrib.moveToPrimary') : (bucketLabel?.(bucket) || '—') }}</option>
      </select>
      <button class="btn btn-ghost" type="button" @click="toggleFullscreen">{{ t('detail.fullscreen') }}</button>
      <button class="btn btn-ghost" type="button" @click="showInfo = !showInfo">{{ t('info.info') }}</button>
      <button class="btn btn-ghost" type="button" :disabled="downloading" @click="emit('download')">{{ t('detail.download') }}</button>
      <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.close') }}</button>
    </div>
  </div>
</template>
