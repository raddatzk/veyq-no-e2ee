<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  updateMemoryMediaMetadata,
  type MemoryMedia,
  type UpdateMemoryMediaMetadataRequest
} from '../../features/memories/api'
import { API_BASE } from '../../api/httpClient'
import { memoryPathToTenantRoute } from '../../shared/utils/paths'

const props = defineProps<{
  tenantName: string
  media: MemoryMedia
  canShowPrevious: boolean
  canShowNext: boolean
  canEditMetadata?: boolean
  canDelete?: boolean
  updateMetadata?: (
    mediaId: string,
    memoryId: string,
    request: UpdateMemoryMediaMetadataRequest
  ) => Promise<MemoryMedia>
  deleteMedia?: (mediaId: string, memoryId: string) => Promise<void>
}>()
const emit = defineEmits<{
  close: []
  previous: []
  next: []
  updated: [media: MemoryMedia]
  deleted: [media: MemoryMedia]
}>()

const mediaUrl = computed(() => `${API_BASE}${props.media.contentUrl}`)
const previewUrl = computed(() =>
  props.media.previewUrl ? `${API_BASE}${props.media.previewUrl}` : null
)
const originalImageLoaded = ref(false)
let imageLoadToken = 0
const displayImageUrl = computed(() =>
  previewUrl.value && !originalImageLoaded.value ? previewUrl.value : mediaUrl.value
)
const isImage = computed(() => props.media.mimeType?.startsWith('image/'))
const isVideo = computed(() => props.media.mimeType?.startsWith('video/'))
const title = computed(() => props.media.title || props.media.id)
const downloadName = computed(() => safeFilename(title.value))
const isDetailsOpen = ref(false)
const isEditingCapturedAt = ref(false)
const capturedAtInput = ref('')
const saveError = ref('')
const isSaving = ref(false)
const dragStartY = ref<number | null>(null)
const displayCapturedAt = computed(() => props.media.capturedAt || '')
const camera = computed(() =>
  [props.media.cameraMake, props.media.cameraModel].filter(Boolean).join(' ').trim()
)
const dimensions = computed(() =>
  props.media.imageWidth && props.media.imageHeight
    ? `${props.media.imageWidth} x ${props.media.imageHeight} px`
    : ''
)
const hasGps = computed(
  () => props.media.gpsLatitude !== null && props.media.gpsLongitude !== null
)
const gpsLabel = computed(() =>
  hasGps.value ? `${props.media.gpsLatitude!.toFixed(6)}, ${props.media.gpsLongitude!.toFixed(6)}` : ''
)
const gpsUrl = computed(() =>
  hasGps.value
    ? `https://www.google.com/maps/search/?api=1&query=${props.media.gpsLatitude},${props.media.gpsLongitude}`
    : ''
)
const uploaderLink = computed(() =>
  props.tenantName && props.media.uploader.type === 'USER' && props.media.uploader.permalink
    ? memoryPathToTenantRoute(props.tenantName, `/~${props.media.uploader.permalink}`)
    : ''
)
const canEditMetadata = computed(() => props.canEditMetadata !== false)
const canDeleteMedia = computed(() => Boolean(props.canDelete && props.deleteMedia))
const metadataRows = computed(() =>
  [
    { label: 'Aufnahmezeit', value: displayCapturedAt.value ? formatDate(displayCapturedAt.value) : '' },
    { label: 'Uploadzeit', value: formatDate(props.media.createdAt) },
    { label: 'Kamera', value: camera.value },
    { label: 'Objektiv', value: props.media.lensModel || '' },
    { label: 'Bildgröße', value: dimensions.value },
    { label: 'Dateigröße', value: formatBytes(props.media.sizeBytes) },
    { label: 'MIME-Type', value: props.media.mimeType }
  ].filter((row) => row.value)
)

watch(
  () => props.media.id,
  () => {
    isEditingCapturedAt.value = false
    capturedAtInput.value = isoToDatetimeLocal(props.media.capturedAt)
    saveError.value = ''
  },
  { immediate: true }
)

watch(
  [() => props.media.id, mediaUrl, previewUrl, isImage],
  () => {
    const token = ++imageLoadToken
    originalImageLoaded.value = !isImage.value || !previewUrl.value
    if (!isImage.value || !previewUrl.value) return

    const image = new Image()
    image.onload = () => {
      if (token === imageLoadToken) originalImageLoaded.value = true
    }
    image.src = mediaUrl.value
  },
  { immediate: true }
)

function safeFilename(value: string): string {
  return (
    value
      .trim()
      .replace(/[\\/:*?"<>|]+/g, '-')
      .replace(/\s+/g, ' ') || props.media.id
  )
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('de-DE', {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(new Date(value))
}

function formatBytes(value: number): string {
  return new Intl.NumberFormat('de-DE', {
    style: 'unit',
    unit: 'byte',
    unitDisplay: 'short'
  }).format(value)
}

function isoToDatetimeLocal(value: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

function datetimeLocalToIso(value: string): string | null {
  return value ? new Date(value).toISOString() : null
}

function startCapturedAtEdit() {
  capturedAtInput.value = isoToDatetimeLocal(props.media.capturedAt)
  saveError.value = ''
  isEditingCapturedAt.value = true
}

async function saveCapturedAt() {
  isSaving.value = true
  saveError.value = ''
  try {
    const request = { capturedAt: datetimeLocalToIso(capturedAtInput.value) }
    const updated = props.updateMetadata
      ? await props.updateMetadata(props.media.id, props.media.memoryId, request)
      : await updateMemoryMediaMetadata(props.tenantName, props.media.memoryId, props.media.id, request)
    emit('updated', updated)
    isEditingCapturedAt.value = false
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : 'Aufnahmezeit konnte nicht gespeichert werden'
  } finally {
    isSaving.value = false
  }
}

async function deleteCurrentMedia() {
  if (!props.deleteMedia) return
  isSaving.value = true
  saveError.value = ''
  try {
    await props.deleteMedia(props.media.id, props.media.memoryId)
    emit('deleted', props.media)
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : 'Medium konnte nicht gelöscht werden'
  } finally {
    isSaving.value = false
  }
}

function onSheetPointerDown(event: PointerEvent) {
  dragStartY.value = event.clientY
}

function onSheetPointerUp(event: PointerEvent) {
  if (dragStartY.value === null) return
  const delta = dragStartY.value - event.clientY
  if (delta > 40) isDetailsOpen.value = true
  if (delta < -40) isDetailsOpen.value = false
  dragStartY.value = null
}
</script>

<template>
  <div
    class="media-lightbox"
    role="dialog"
    aria-modal="true"
    :aria-label="title"
    @click.self="$emit('close')"
  >
    <div class="media-lightbox-topbar">
      <div class="media-lightbox-title">
        <strong>{{ title }}</strong>
        <small>{{ media.mimeType }}</small>
      </div>
      <div class="media-lightbox-actions">
        <a
          class="lightbox-icon-button"
          :href="mediaUrl"
          :download="downloadName"
          aria-label="Medium herunterladen"
        >
          <span class="pi pi-download" />
        </a>
        <button
          class="lightbox-icon-button"
          type="button"
          aria-label="Lightbox schließen"
          @click="$emit('close')"
        >
          <span class="pi pi-times" />
        </button>
      </div>
    </div>

    <main class="media-lightbox-layout">
      <section class="media-lightbox-viewer">
        <button
          class="lightbox-nav lightbox-nav-previous"
          type="button"
          aria-label="Vorheriges Medium"
          :disabled="!canShowPrevious"
          @click="$emit('previous')"
        >
          <span class="pi pi-chevron-left" />
        </button>

        <div class="media-lightbox-stage">
          <img v-if="isImage" :src="displayImageUrl" :alt="title" />
          <video
            v-else-if="isVideo"
            :src="mediaUrl"
            :poster="previewUrl || undefined"
            controls
            playsinline
            preload="metadata"
          />
          <a v-else :href="mediaUrl" :download="downloadName">{{ title }}</a>
        </div>

        <button
          class="lightbox-nav lightbox-nav-next"
          type="button"
          aria-label="Nächstes Medium"
          :disabled="!canShowNext"
          @click="$emit('next')"
        >
          <span class="pi pi-chevron-right" />
        </button>
      </section>

      <aside
        class="media-lightbox-details"
        :class="{ 'media-lightbox-details-open': isDetailsOpen }"
        @pointerdown="onSheetPointerDown"
        @pointerup="onSheetPointerUp"
      >
        <button
          class="media-lightbox-sheet-handle"
          type="button"
          :aria-expanded="isDetailsOpen"
          aria-label="Bildinformationen ein- oder ausklappen"
          @click="isDetailsOpen = !isDetailsOpen"
        />
        <div class="media-lightbox-details-header">
          <h2>Informationen</h2>
          <button
            v-if="canDeleteMedia"
            class="lightbox-text-button"
            type="button"
            :disabled="isSaving"
            @click="deleteCurrentMedia"
          >
            Löschen
          </button>
          <button
            v-if="canEditMetadata"
            class="lightbox-text-button"
            type="button"
            @click="startCapturedAtEdit"
          >
            Aufnahmezeit ändern
          </button>
        </div>

        <form v-if="isEditingCapturedAt" class="media-lightbox-date-form" @submit.prevent="saveCapturedAt">
          <label for="captured-at-input">Aufnahmezeit</label>
          <input id="captured-at-input" v-model="capturedAtInput" type="datetime-local" />
          <div class="media-lightbox-date-actions">
            <button class="lightbox-text-button" type="submit" :disabled="isSaving">
              Speichern
            </button>
            <button
              class="lightbox-text-button"
              type="button"
              :disabled="isSaving"
              @click="isEditingCapturedAt = false"
            >
              Abbrechen
            </button>
          </div>
          <p v-if="saveError" class="media-lightbox-error">{{ saveError }}</p>
        </form>

        <dl class="media-lightbox-metadata">
          <dt>Hochgeladen von</dt>
          <dd class="media-lightbox-uploader">
            <RouterLink v-if="uploaderLink" class="media-lightbox-uploader-link" :to="uploaderLink">
              {{ media.uploader.displayName }}
            </RouterLink>
            <span v-else>{{ media.uploader.displayName }}</span>
          </dd>
          <template v-for="row in metadataRows" :key="row.label">
            <dt>{{ row.label }}</dt>
            <dd>{{ row.value }}</dd>
          </template>
          <template v-if="hasGps">
            <dt>Ort</dt>
            <dd><a :href="gpsUrl" target="_blank" rel="noreferrer">{{ gpsLabel }}</a></dd>
          </template>
          <template v-if="media.capturedAtOverride">
            <dt>Status</dt>
            <dd>Aufnahmezeit korrigiert</dd>
          </template>
        </dl>
      </aside>
    </main>
  </div>
</template>

<style scoped>
.media-lightbox-uploader {
  display: grid;
  gap: 0.55rem;
  align-items: start;
}

.media-lightbox-uploader-link {
  display: inline-flex;
  align-items: center;
  justify-self: start;
  min-width: 0;
  color: var(--app-text);
  font: inherit;
  line-height: 1.2;
  text-decoration: underline;
  text-decoration-color: color-mix(in srgb, var(--app-text) 30%, transparent);
  text-underline-offset: 0.18em;
}

.media-lightbox-uploader-link:hover,
.media-lightbox-uploader-link:focus-visible {
  text-decoration-color: currentColor;
}

.media-lightbox-uploader-link:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--app-accent) 35%, transparent);
  outline-offset: 3px;
  border-radius: 4px;
}
</style>
