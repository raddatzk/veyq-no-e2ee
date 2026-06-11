<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import {
  updateMemoryMediaCapturedAt,
  type MemoryMedia,
  type UpdateMemoryMediaCapturedAtRequest,
  type UpdateMemoryMediaHighlightRequest
} from '../../features/memories/api'
import {
  mediaContentUrl,
  mediaImageDisplayUrl,
  mediaPosterUrl,
  mediaPreviewOnlyUrl
} from '../../shared/utils/mediaUrls'
import { memoryPathToTenantRoute } from '../../shared/utils/paths'

const props = defineProps<{
  tenantName: string
  media: MemoryMedia
  mediaItems?: MemoryMedia[]
  selectedIndex?: number
  canShowPrevious: boolean
  canShowNext: boolean
  canEditMetadata?: boolean
  canDelete?: boolean
  updateCapturedAt?: (
    mediaId: string,
    memoryId: string,
    request: UpdateMemoryMediaCapturedAtRequest
  ) => Promise<MemoryMedia>
  updateHighlight?: (
    mediaId: string,
    memoryId: string,
    request: UpdateMemoryMediaHighlightRequest
  ) => Promise<MemoryMedia>
  canHighlight?: boolean
  deleteMedia?: (mediaId: string, memoryId: string) => Promise<void>
}>()
const emit = defineEmits<{
  close: []
  previous: []
  next: []
  updated: [media: MemoryMedia]
  deleted: [media: MemoryMedia]
}>()

const { locale, t } = useI18n()
const PRELOAD_NEXT_COUNT = 5
const PRELOAD_PREVIOUS_COUNT = 2
const PREVIEW_MAX_EDGE = 1600
const ACTION_ERROR_VISIBLE_MS = 3000
const mediaUrl = computed(() => mediaContentUrl(props.media))
const previewUrl = computed(() => mediaPreviewOnlyUrl(props.media))
const posterUrl = computed(() => mediaPosterUrl(props.media))
const displayImageUrl = computed(() => previewUrl.value || mediaUrl.value)
const fullscreenImageUrl = computed(() => imageFullscreenUrl(props.media))
const isImage = computed(() => props.media.mimeType?.startsWith('image/'))
const isVideo = computed(() => props.media.mimeType?.startsWith('video/'))
const title = computed(() => props.media.title || props.media.id)
const downloadName = computed(() => safeFilename(title.value))
const isDetailsOpen = ref(false)
const isEditingCapturedAt = ref(false)
const capturedAtInput = ref('')
const saveError = ref('')
let actionErrorTimeout: number | null = null
const isSaving = ref(false)
const dragStartY = ref<number | null>(null)
const detailActionMenu = ref<InstanceType<typeof Menu> | null>(null)
const fullscreenTarget = ref<HTMLElement | null>(null)
const fullscreenOpen = ref(false)
const fullscreenOriginalLoaded = ref(false)
const fullscreenError = ref('')
const canUseFullscreen = ref(false)
const viewportWidth = ref(0)
const viewportHeight = ref(0)
const loadedFullscreenSources = ref<Set<string>>(new Set())
const displayCapturedAt = computed(() => props.media.capturedAt || '')
const camera = computed(() =>
  [props.media.cameraMake, props.media.cameraModel].filter(Boolean).join(' ').trim()
)
const dimensions = computed(() =>
  props.media.imageWidth && props.media.imageHeight
    ? `${props.media.imageWidth} x ${props.media.imageHeight} px`
    : ''
)
const highlightActionLabel = computed(() =>
  isHighlighted.value ? t('media.highlightRemove') : t('media.highlightAdd')
)
const hasGps = computed(() => props.media.gpsLatitude !== null && props.media.gpsLongitude !== null)
const gpsLabel = computed(() =>
  hasGps.value
    ? `${props.media.gpsLatitude!.toFixed(6)}, ${props.media.gpsLongitude!.toFixed(6)}`
    : ''
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
const canHighlightMedia = computed(() => props.canHighlight !== false && Boolean(props.updateHighlight))
const canFullscreenOriginal = computed(
  () => canUseFullscreen.value && (isImage.value || isVideo.value)
)
const detailActionItems = computed<MenuItem[]>(() => {
  const items: Array<MenuItem | null> = [
    canEditMetadata.value
      ? {
          label: t('media.capturedAtEdit'),
          icon: 'pi pi-clock',
          command: startCapturedAtEdit
        }
      : null,
    canDeleteMedia.value
      ? {
          label: t('common.delete'),
          icon: 'pi pi-trash',
          class: 'memory-menu-danger',
          disabled: isSaving.value,
          command: deleteCurrentMedia
        }
      : null
  ]
  return items.filter((item): item is MenuItem => item !== null)
})
const metadataRows = computed(() =>
  [
    {
      label: t('media.capturedAt'),
      value: displayCapturedAt.value ? formatDate(displayCapturedAt.value) : ''
    },
    { label: t('media.uploadedAt'), value: formatDate(props.media.createdAt) },
    { label: t('media.camera'), value: camera.value },
    { label: t('media.lens'), value: props.media.lensModel || '' },
    { label: t('media.dimensions'), value: dimensions.value },
    { label: t('media.fileSize'), value: formatBytes(props.media.sizeBytes) },
    { label: t('media.mimeType'), value: props.media.mimeType }
  ].filter((row) => row.value)
)
const isHighlighted = computed(() => props.media.highlight)

onMounted(() => {
  canUseFullscreen.value =
    document.fullscreenEnabled !== false &&
    typeof document.documentElement.requestFullscreen === 'function'
  updateViewportSize()
  document.addEventListener('fullscreenchange', handleFullscreenChange)
  window.addEventListener('resize', handleViewportResize)
  window.addEventListener('keydown', handleKeydown)
  syncPreloads()
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', handleFullscreenChange)
  window.removeEventListener('resize', handleViewportResize)
  window.removeEventListener('keydown', handleKeydown)
  clearActionErrorTimeout()
  abortAllPreloads()
})

watch(
  () => props.media.id,
  () => {
    isEditingCapturedAt.value = false
    capturedAtInput.value = isoToDatetimeLocal(props.media.capturedAt)
    clearActionErrorTimeout()
    saveError.value = ''
    fullscreenError.value = ''
    fullscreenOriginalLoaded.value =
      !isImage.value || loadedFullscreenSources.value.has(fullscreenImageUrl.value)
  },
  { immediate: true }
)

watch(
  [
    () => props.media.id,
    () => props.selectedIndex,
    () => props.mediaItems,
    viewportWidth,
    viewportHeight
  ],
  () => syncPreloads(),
  { flush: 'post' }
)

const preloadControllers = new Map<string, AbortController>()

function safeFilename(value: string): string {
  return (
    value
      .trim()
      .replace(/[\\/:*?"<>|]+/g, '-')
      .replace(/\s+/g, ' ') || props.media.id
  )
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'de-DE', {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(new Date(value))
}

function formatBytes(value: number): string {
  return new Intl.NumberFormat(locale.value === 'en' ? 'en-US' : 'de-DE', {
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
  clearActionErrorTimeout()
  saveError.value = ''
  isEditingCapturedAt.value = true
}

async function saveCapturedAt() {
  isSaving.value = true
  clearActionErrorTimeout()
  saveError.value = ''
  try {
    const request = { capturedAt: datetimeLocalToIso(capturedAtInput.value) }
    const updated = props.updateCapturedAt
      ? await props.updateCapturedAt(props.media.id, props.media.memoryId, request)
      : await updateMemoryMediaCapturedAt(
          props.tenantName,
          props.media.memoryId,
          props.media.id,
          request
        )
    emit('updated', updated)
    isEditingCapturedAt.value = false
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : t('media.capturedAtSaveFailed')
  } finally {
    isSaving.value = false
  }
}

async function saveHighlight() {
  const updateHighlight = props.updateHighlight
  if (!canHighlightMedia.value || !updateHighlight) {
    showTemporaryActionError(t('media.highlightUnavailable'))
    return
  }
  isSaving.value = true
  clearActionErrorTimeout()
  saveError.value = ''
  try {
    const request = { highlight: !isHighlighted.value }
    const updated = await updateHighlight(props.media.id, props.media.memoryId, request)
    emit('updated', updated)
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : t('media.highlightSaveFailed')
  } finally {
    isSaving.value = false
  }
}

async function deleteCurrentMedia() {
  if (!props.deleteMedia) return
  isSaving.value = true
  clearActionErrorTimeout()
  saveError.value = ''
  try {
    await props.deleteMedia(props.media.id, props.media.memoryId)
    emit('deleted', props.media)
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : t('media.deleteFailed')
  } finally {
    isSaving.value = false
  }
}

function toggleDetailActionMenu(event: MouseEvent) {
  detailActionMenu.value?.toggle(event)
}

async function openOriginalFullscreen() {
  if (!canFullscreenOriginal.value) return
  fullscreenError.value = ''
  fullscreenOpen.value = true
  fullscreenOriginalLoaded.value =
    !isImage.value || loadedFullscreenSources.value.has(fullscreenImageUrl.value)
  syncPreloads()
  await nextTick()
  try {
    await fullscreenTarget.value?.requestFullscreen()
  } catch (error) {
    fullscreenOpen.value = false
    fullscreenOriginalLoaded.value = false
    fullscreenError.value = error instanceof Error ? error.message : ''
  }
}

function handleFullscreenChange() {
  if (document.fullscreenElement !== fullscreenTarget.value) {
    fullscreenOpen.value = false
    fullscreenOriginalLoaded.value =
      !isImage.value || loadedFullscreenSources.value.has(fullscreenImageUrl.value)
  }
}

function handleViewportResize() {
  updateViewportSize()
}

function handleKeydown(event: KeyboardEvent) {
  if (event.metaKey || event.ctrlKey || event.altKey) return
  if (isTypingTarget(event.target)) return

  if (event.key === 'ArrowLeft') {
    if (!props.canShowPrevious) return
    event.preventDefault()
    emit('previous')
    return
  }

  if (event.key === 'ArrowRight') {
    if (!props.canShowNext) return
    event.preventDefault()
    emit('next')
    return
  }

  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
    return
  }

  if (event.key.toLowerCase() !== 'f') return
  if (isSaving.value) return

  event.preventDefault()
  if (!canHighlightMedia.value) {
    showTemporaryActionError(t('media.highlightUnavailable'))
    return
  }
  void saveHighlight()
}

function showTemporaryActionError(message: string) {
  clearActionErrorTimeout()
  saveError.value = message
  actionErrorTimeout = window.setTimeout(() => {
    saveError.value = ''
    actionErrorTimeout = null
  }, ACTION_ERROR_VISIBLE_MS)
}

function clearActionErrorTimeout() {
  if (actionErrorTimeout === null) return
  window.clearTimeout(actionErrorTimeout)
  actionErrorTimeout = null
}

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return (
    target.tagName === 'INPUT' ||
    target.tagName === 'TEXTAREA' ||
    target.tagName === 'SELECT' ||
    target.isContentEditable
  )
}

function updateViewportSize() {
  viewportWidth.value = Math.max(1, window.innerWidth || document.documentElement.clientWidth || 1)
  viewportHeight.value = Math.max(
    1,
    window.innerHeight || document.documentElement.clientHeight || 1
  )
}

function imageFullscreenUrl(media: MemoryMedia): string {
  if (!media.mimeType?.startsWith('image/')) return mediaContentUrl(media)
  return canUsePreviewForFullscreen(media) ? mediaImageDisplayUrl(media) : mediaContentUrl(media)
}

function canUsePreviewForFullscreen(media: MemoryMedia): boolean {
  if (!media.previewUrl || !media.imageWidth || !media.imageHeight) return false
  const maxEdge = Math.max(media.imageWidth, media.imageHeight)
  if (maxEdge <= 0) return false
  const scale = Math.min(1, PREVIEW_MAX_EDGE / maxEdge)
  const previewWidth = media.imageWidth * scale
  const previewHeight = media.imageHeight * scale
  const target = fullscreenTargetSize(media)
  return previewWidth >= target.width && previewHeight >= target.height
}

function fullscreenTargetSize(media: MemoryMedia): { width: number; height: number } {
  const width = viewportWidth.value || 1
  const height = viewportHeight.value || 1
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  if (!media.imageWidth || !media.imageHeight) return { width: width * dpr, height: height * dpr }

  const imageAspect = media.imageWidth / media.imageHeight
  const viewportAspect = width / height
  if (imageAspect > viewportAspect) {
    return { width: width * dpr, height: (width / imageAspect) * dpr }
  }
  return { width: height * imageAspect * dpr, height: height * dpr }
}

function preloadMediaItems(): MemoryMedia[] {
  const items = props.mediaItems?.length ? props.mediaItems : [props.media]
  const index = props.selectedIndex ?? items.findIndex((item) => item.id === props.media.id)
  const selectedIndex = index >= 0 ? index : 0
  const start = Math.max(0, selectedIndex - PRELOAD_PREVIOUS_COUNT)
  const end = Math.min(items.length - 1, selectedIndex + PRELOAD_NEXT_COUNT)
  return items.slice(start, end + 1)
}

function preloadUrlForMedia(media: MemoryMedia): string | null {
  if (media.mimeType?.startsWith('image/')) return imageFullscreenUrl(media)
  if (media.mimeType?.startsWith('video/')) return mediaPosterUrl(media)
  return null
}

function syncPreloads() {
  updateViewportSize()
  const targetUrls = new Set(
    preloadMediaItems()
      .map(preloadUrlForMedia)
      .filter((url): url is string => Boolean(url))
  )

  for (const [url, controller] of preloadControllers) {
    if (!targetUrls.has(url)) {
      controller.abort()
      preloadControllers.delete(url)
    }
  }

  targetUrls.forEach((url) => {
    if (loadedFullscreenSources.value.has(url) || preloadControllers.has(url)) return
    const controller = new AbortController()
    preloadControllers.set(url, controller)
    void fetch(url, { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) return
        await response.blob()
        loadedFullscreenSources.value = new Set([...loadedFullscreenSources.value, url])
        if (url === fullscreenImageUrl.value) fullscreenOriginalLoaded.value = true
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
      })
      .finally(() => {
        if (preloadControllers.get(url) === controller) preloadControllers.delete(url)
      })
  })
}

function abortAllPreloads() {
  preloadControllers.forEach((controller) => controller.abort())
  preloadControllers.clear()
}

function markFullscreenImageLoaded() {
  const url = fullscreenImageUrl.value
  loadedFullscreenSources.value = new Set([...loadedFullscreenSources.value, url])
  fullscreenOriginalLoaded.value = true
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
          class="veyq-icon-button lightbox-icon-button"
          :href="mediaUrl"
          :download="downloadName"
          :aria-label="t('media.download')"
        >
          <span class="pi pi-download" />
        </a>
        <Button
          v-if="canHighlightMedia"
          type="button"
          class="veyq-icon-button veyq-prime-icon-button lightbox-star-button"
          :class="{ 'lightbox-star-button-active': isHighlighted }"
          :icon="isHighlighted ? 'pi pi-star-fill' : 'pi pi-star'"
          :aria-label="highlightActionLabel"
          :title="canHighlightMedia ? highlightActionLabel : t('media.highlightUnavailable')"
          severity="secondary"
          outlined
          :loading="isSaving"
          :disabled="!canHighlightMedia"
          @click="saveHighlight"
        />

        <button
          v-if="canFullscreenOriginal"
          class="veyq-icon-button lightbox-icon-button"
          type="button"
          :aria-label="t('media.fullscreenOriginal')"
          :title="t('media.fullscreenOriginal')"
          @click="openOriginalFullscreen"
        >
          <span class="pi pi-window-maximize" />
        </button>
        <button
          class="veyq-icon-button lightbox-icon-button"
          type="button"
          :aria-label="t('media.closeLightbox')"
          @click="$emit('close')"
        >
          <span class="pi pi-times" />
        </button>
      </div>
    </div>
    <p v-if="saveError && !isEditingCapturedAt" class="media-lightbox-error media-lightbox-action-error">
      {{ saveError }}
    </p>

    <main class="media-lightbox-layout">
      <section class="media-lightbox-viewer">
        <button
          class="lightbox-nav lightbox-nav-previous"
          type="button"
          :aria-label="t('media.previous')"
          :disabled="!canShowPrevious"
          @click="$emit('previous')"
        >
          <span class="pi pi-chevron-left" />
        </button>

        <div class="media-lightbox-stage">
          <p v-if="fullscreenError" class="media-lightbox-error">{{ fullscreenError }}</p>
          <img v-if="isImage" :src="displayImageUrl" :alt="title" />
          <video
            v-else-if="isVideo"
            :src="mediaUrl"
            :poster="posterUrl || undefined"
            controls
            playsinline
            preload="metadata"
          />
          <a v-else :href="mediaUrl" :download="downloadName">{{ title }}</a>
        </div>

        <button
          class="lightbox-nav lightbox-nav-next"
          type="button"
          :aria-label="t('media.next')"
          :disabled="!canShowNext"
          @click="$emit('next')"
        >
          <span class="pi pi-chevron-right" />
        </button>
      </section>

      <div v-if="fullscreenOpen" ref="fullscreenTarget" class="media-original-fullscreen">
        <button
          class="lightbox-nav lightbox-nav-previous media-original-nav"
          type="button"
          :aria-label="t('media.previous')"
          :disabled="!canShowPrevious"
          @click="$emit('previous')"
        >
          <span class="pi pi-chevron-left" />
        </button>
        <div v-if="isImage && !fullscreenOriginalLoaded" class="media-original-loading">
          <span class="pi pi-spin pi-spinner" />
          <span>{{ t('media.originalLoading') }}</span>
        </div>
        <img
          v-if="isImage"
          :src="fullscreenImageUrl"
          :alt="title"
          :class="{ 'media-original-loaded': fullscreenOriginalLoaded }"
          @load="markFullscreenImageLoaded"
        />
        <video
          v-else-if="isVideo"
          :src="mediaUrl"
          :poster="posterUrl || undefined"
          controls
          autoplay
          playsinline
          preload="metadata"
        />
        <button
          class="lightbox-nav lightbox-nav-next media-original-nav"
          type="button"
          :aria-label="t('media.next')"
          :disabled="!canShowNext"
          @click="$emit('next')"
        >
          <span class="pi pi-chevron-right" />
        </button>
      </div>

      <aside
        class="veyq-surface media-lightbox-details"
        :class="{ 'media-lightbox-details-open': isDetailsOpen }"
        @pointerdown="onSheetPointerDown"
        @pointerup="onSheetPointerUp"
      >
        <button
          class="media-lightbox-sheet-handle"
          type="button"
          :aria-expanded="isDetailsOpen"
          :aria-label="t('media.toggleInfo')"
          @click="isDetailsOpen = !isDetailsOpen"
        />
        <div class="media-lightbox-details-header">
          <h2>{{ t('media.information') }}</h2>
          <div v-if="detailActionItems.length" class="media-lightbox-details-actions">
            <button
              class="veyq-icon-button lightbox-icon-button"
              type="button"
              :aria-label="t('media.options')"
              :disabled="isSaving"
              aria-haspopup="menu"
              @click="toggleDetailActionMenu"
            >
              <span class="pi pi-ellipsis-v" aria-hidden="true" />
            </button>
            <Menu ref="detailActionMenu" :model="detailActionItems" popup />
          </div>
        </div>

        <form
          v-if="isEditingCapturedAt"
          class="media-lightbox-date-form"
          @submit.prevent="saveCapturedAt"
        >
          <label for="captured-at-input">{{ t('media.capturedAt') }}</label>
          <input id="captured-at-input" v-model="capturedAtInput" type="datetime-local" />
          <div class="media-lightbox-date-actions">
            <button
              class="veyq-text-button lightbox-text-button"
              type="submit"
              :disabled="isSaving"
            >
              {{ t('common.save') }}
            </button>
            <button
              class="veyq-text-button lightbox-text-button"
              type="button"
              :disabled="isSaving"
              @click="isEditingCapturedAt = false"
            >
              {{ t('common.cancel') }}
            </button>
          </div>
          <p v-if="saveError" class="media-lightbox-error">{{ saveError }}</p>
        </form>

        <dl class="media-lightbox-metadata">
          <dt>{{ t('media.uploadedBy') }}</dt>
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
            <dt>{{ t('media.location') }}</dt>
            <dd>
              <a :href="gpsUrl" target="_blank" rel="noreferrer">{{ gpsLabel }}</a>
            </dd>
          </template>
          <template v-if="media.capturedAtOverride">
            <dt>{{ t('common.status') }}</dt>
            <dd>{{ t('media.capturedAtCorrected') }}</dd>
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
  outline: 2px solid var(--app-focus-ring);
  outline-offset: 3px;
  border-radius: 4px;
}
</style>
