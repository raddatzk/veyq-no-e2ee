<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import FileUpload, { type FileUploadSelectEvent } from 'primevue/fileupload'
import Message from 'primevue/message'
import { API_BASE } from '../../../shared/api/httpClient'
import { getErrorMessage } from '../../../shared/utils/errors'
import {
  removeMemoryCover,
  setMemoryCover,
  type Memory,
  type MemoryMedia,
  type SetMemoryCoverRequest
} from '../api'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  memory: Memory
  media: MemoryMedia[]
  setCoverOverride?: (memoryId: string, request: SetMemoryCoverRequest) => Promise<Memory>
  removeCoverOverride?: (memoryId: string) => Promise<Memory>
}>()
const emit = defineEmits<{
  saved: [memory: Memory]
}>()

const { t } = useI18n()
const cropWidth = 320
const cropHeight = 180

const selectedFile = ref<File | null>(null)
const selectedMedia = ref<MemoryMedia | null>(null)
const sourceUrl = ref('')
const sourceName = ref('')
const imageElement = ref<HTMLImageElement | null>(null)
const naturalWidth = ref(0)
const naturalHeight = ref(0)
const zoom = ref(1)
const offsetX = ref(0)
const offsetY = ref(0)
const initialCrop = ref<Memory['coverCrop']>(null)
const error = ref('')
const loading = ref(false)
const dragging = ref(false)
const dragStart = ref({ pointerId: 0, x: 0, y: 0, offsetX: 0, offsetY: 0 })
const hasCover = computed(() => Boolean(props.memory.coverUrl || props.memory.coverMediaId))
const imageMedia = computed(() => props.media.filter((media) => media.mimeType?.startsWith('image/')))
const hasSource = computed(() => Boolean(sourceUrl.value))
const imageLoaded = computed(() => naturalWidth.value > 0 && naturalHeight.value > 0)
const displayedImageStyle = computed(() => ({
  width: `${baseImageWidth.value * zoom.value}px`,
  height: `${baseImageHeight.value * zoom.value}px`,
  transform: `translate(${offsetX.value}px, ${offsetY.value}px)`
}))
const baseScale = computed(() => {
  if (!naturalWidth.value || !naturalHeight.value) return 1
  return Math.max(cropWidth / naturalWidth.value, cropHeight / naturalHeight.value)
})
const baseImageWidth = computed(() => naturalWidth.value * baseScale.value)
const baseImageHeight = computed(() => naturalHeight.value * baseScale.value)

watch(
  visible,
  (isVisible) => {
    if (isVisible) preloadExistingCover()
    else resetSelection()
  },
  { immediate: true }
)

onBeforeUnmount(revokeObjectUrl)

async function saveCover() {
  if (!imageElement.value || !imageLoaded.value) return
  error.value = ''
  loading.value = true
  try {
    const crop = currentCoverCrop()
    const source = await coverSourceRequest()
    const request = { ...source, ...crop }
    const updated = props.setCoverOverride
      ? await props.setCoverOverride(props.memory.id, request)
      : await setMemoryCover(props.tenantName, props.memory.id, request)
    visible.value = false
    emit('saved', updated)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('memory.coverSaveFailed'))
  } finally {
    loading.value = false
  }
}

function selectCover(event: FileUploadSelectEvent) {
  const file = event.files[0] || null
  if (!file) return
  selectedFile.value = file
  selectedMedia.value = null
  setSourceUrl(URL.createObjectURL(file), file.name, null)
}

function selectMedia(media: MemoryMedia) {
  selectedMedia.value = media
  selectedFile.value = null
  setSourceUrl(`${API_BASE}${media.contentUrl || media.previewUrl}`, media.title || media.id, null)
}

function setSourceUrl(url: string, name: string, crop: Memory['coverCrop']) {
  revokeObjectUrl()
  sourceUrl.value = url
  sourceName.value = name
  initialCrop.value = crop
  naturalWidth.value = 0
  naturalHeight.value = 0
  zoom.value = 1
  offsetX.value = 0
  offsetY.value = 0
  error.value = ''
}

function resetSelection() {
  revokeObjectUrl()
  selectedFile.value = null
  selectedMedia.value = null
  sourceUrl.value = ''
  sourceName.value = ''
  initialCrop.value = null
  naturalWidth.value = 0
  naturalHeight.value = 0
  zoom.value = 1
  offsetX.value = 0
  offsetY.value = 0
  error.value = ''
  loading.value = false
}

function revokeObjectUrl() {
  if (sourceUrl.value.startsWith('blob:')) URL.revokeObjectURL(sourceUrl.value)
}

async function loadImage() {
  const image = imageElement.value
  if (!image) return
  naturalWidth.value = image.naturalWidth
  naturalHeight.value = image.naturalHeight
  await nextTick()
  if (initialCrop.value) applyCrop(initialCrop.value)
  else centerImage()
}

function preloadExistingCover() {
  resetSelection()
  if (props.memory.coverMediaId) {
    const media = imageMedia.value.find((candidate) => candidate.id === props.memory.coverMediaId)
    if (media) {
      selectedMedia.value = media
      setSourceUrl(`${API_BASE}${media.contentUrl || media.previewUrl}`, media.title || media.id, props.memory.coverCrop)
      return
    }
  }
  if (props.memory.coverUrl) {
    setSourceUrl(`${API_BASE}${props.memory.coverUrl}`, props.memory.displayName, props.memory.coverCrop)
  }
}

function centerImage() {
  offsetX.value = (cropWidth - baseImageWidth.value * zoom.value) / 2
  offsetY.value = (cropHeight - baseImageHeight.value * zoom.value) / 2
  clampOffset()
}

function applyCrop(crop: NonNullable<Memory['coverCrop']>) {
  const nextZoom = clamp(cropWidth / (crop.width * naturalWidth.value * baseScale.value), 1, 3)
  zoom.value = nextZoom
  const scale = baseScale.value * zoom.value
  offsetX.value = -crop.x * naturalWidth.value * scale
  offsetY.value = -crop.y * naturalHeight.value * scale
  clampOffset()
}

function updateZoom(event: Event) {
  const target = event.target as HTMLInputElement
  const nextZoom = Number(target.value)
  const previousZoom = zoom.value
  if (!Number.isFinite(nextZoom) || nextZoom <= 0 || previousZoom <= 0) return
  const centerX = cropWidth / 2
  const centerY = cropHeight / 2
  offsetX.value = centerX - ((centerX - offsetX.value) / previousZoom) * nextZoom
  offsetY.value = centerY - ((centerY - offsetY.value) / previousZoom) * nextZoom
  zoom.value = nextZoom
  clampOffset()
}

function startDrag(event: PointerEvent) {
  if (!imageLoaded.value) return
  dragging.value = true
  dragStart.value = {
    pointerId: event.pointerId,
    x: event.clientX,
    y: event.clientY,
    offsetX: offsetX.value,
    offsetY: offsetY.value
  }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function drag(event: PointerEvent) {
  if (!dragging.value || event.pointerId !== dragStart.value.pointerId) return
  offsetX.value = dragStart.value.offsetX + event.clientX - dragStart.value.x
  offsetY.value = dragStart.value.offsetY + event.clientY - dragStart.value.y
  clampOffset()
}

function stopDrag(event: PointerEvent) {
  if (!dragging.value || event.pointerId !== dragStart.value.pointerId) return
  dragging.value = false
  ;(event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId)
}

function clampOffset() {
  const width = baseImageWidth.value * zoom.value
  const height = baseImageHeight.value * zoom.value
  offsetX.value = clamp(offsetX.value, Math.min(0, cropWidth - width), 0)
  offsetY.value = clamp(offsetY.value, Math.min(0, cropHeight - height), 0)
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function currentCoverCrop() {
  const scale = baseScale.value * zoom.value
  return {
    cropX: clamp(-offsetX.value / scale / naturalWidth.value, 0, 1),
    cropY: clamp(-offsetY.value / scale / naturalHeight.value, 0, 1),
    cropWidth: clamp(cropWidth / scale / naturalWidth.value, 0, 1),
    cropHeight: clamp(cropHeight / scale / naturalHeight.value, 0, 1)
  }
}

function coverSourceRequest() {
  if (selectedFile.value) return { file: selectedFile.value }
  if (selectedMedia.value) return { mediaId: selectedMedia.value.id }
  if (sourceUrl.value && props.memory.coverUrl && !props.memory.coverMediaId) return {}
  throw new Error(t('memory.coverSourceMissing'))
}

async function removeCover() {
  error.value = ''
  loading.value = true
  try {
    const updated = props.removeCoverOverride
      ? await props.removeCoverOverride(props.memory.id)
      : await removeMemoryCover(props.tenantName, props.memory.id)
    visible.value = false
    emit('saved', updated)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('memory.coverRemoveFailed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('memory.coverChoose')" modal class="tenant-dialog">
    <div class="form">
      <p class="memory-dialog-copy">
        {{ t('memory.coverCopy') }}
      </p>
      <FileUpload
        mode="basic"
        accept="image/*"
        :choose-label="t('memory.coverUpload')"
        custom-upload
        @select="selectCover"
      />
      <strong v-if="sourceName" class="memory-dialog-copy">{{ sourceName }}</strong>

      <div v-if="imageMedia.length" class="memory-cover-picker" :aria-label="t('memory.coverGallery')">
        <button
          v-for="mediaItem in imageMedia"
          :key="mediaItem.id"
          type="button"
          class="memory-cover-option"
          :class="{ 'memory-cover-option-selected': selectedMedia?.id === mediaItem.id }"
          @click="selectMedia(mediaItem)"
        >
          <img
            :src="`${API_BASE}${mediaItem.previewUrl || mediaItem.contentUrl}`"
            :alt="mediaItem.title || mediaItem.id"
          />
          <strong>{{ mediaItem.title || mediaItem.id }}</strong>
        </button>
      </div>

      <div v-if="hasSource" class="memory-cover-editor">
        <div
          class="memory-cover-cropper"
          :class="{ 'memory-cover-cropper-dragging': dragging }"
          :style="{ width: `${cropWidth}px`, aspectRatio: `${cropWidth} / ${cropHeight}` }"
          @pointerdown="startDrag"
          @pointermove="drag"
          @pointerup="stopDrag"
          @pointercancel="stopDrag"
        >
          <img
            ref="imageElement"
            :src="sourceUrl"
            :alt="sourceName"
            :style="displayedImageStyle"
            draggable="false"
            @load="loadImage"
          />
        </div>
        <label class="memory-cover-zoom">
          <span>{{ t('memory.zoom') }}</span>
          <input
            type="range"
            min="1"
            max="3"
            step="0.01"
            :value="zoom"
            :disabled="!imageLoaded"
            @input="updateZoom"
          />
        </label>
      </div>

      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button
          type="button"
          :label="t('memory.coverSave')"
          icon="pi pi-image"
          :disabled="!imageLoaded"
          :loading="loading"
          @click="saveCover"
        />
        <Button
          type="button"
          :label="t('memory.coverRemove')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          :disabled="!hasCover"
          :loading="loading"
          @click="removeCover"
        />
        <Button
          type="button"
          :label="t('common.cancel')"
          severity="secondary"
          text
          @click="visible = false"
        />
      </div>
    </div>
  </Dialog>
</template>
