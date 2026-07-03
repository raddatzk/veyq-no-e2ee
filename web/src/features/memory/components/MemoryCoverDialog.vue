<script setup lang="ts">
// The Memory cover editor (cr-0072 §1–2 + cr-0073). Crops **one** source and emits a [CoverSaveIntent]
// (`upload` | `keep` | `remove`) — the parent seals it. Sources: a **brand-new uploaded image** (an
// `<input type=file accept=image/*>`), or the **current cover re-cropped** (`existingSrc`). Using an existing
// Memory photo as the cover is a one-click action in the gallery lightbox ("Set as cover"), not a step in here —
// so this dialog has no media picker and scales regardless of photo count. Presentation only: no crypto, no
// network. The crop math (base-scale fill, clamp-so-no-empty-corners, normalize to `0..1`) is the legacy math.
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import type { CoverCrop, CoverSaveIntent, MemoryCover } from '../memoryCover'

const props = defineProps<{
  cover: MemoryCover | null // the current stored cover (restore its crop + enable Remove), or null
  existingSrc: string | null // the current cover's decrypted image URL — the crop source when re-cropping it
  hasMedia: boolean // whether the Memory has photos — drives the copy's "…or open a photo in the gallery" hint
}>()
const emit = defineEmits<{ save: [CoverSaveIntent]; close: [] }>()
const { t } = useI18n()

// The 16:9 crop viewport is **measured** from the rendered cropper (which fills the dialog width) rather than a
// fixed size, so the editor scales with the dialog + never leaves a gap. The stored crop is normalized 0..1, so
// the absolute size doesn't affect the result — only the editing surface. Defaults until first measured.
const cropperEl = ref<HTMLElement | null>(null)
const cropWidth = ref(320)
const cropHeight = ref(180)

/** Measure the cropper's rendered width and derive the 16:9 height (re-run on open + on resize). */
function measureCropper() {
  const w = cropperEl.value?.clientWidth
  if (w && w > 0) {
    cropWidth.value = w
    cropHeight.value = Math.round((w * 9) / 16)
  }
}

// The active crop source: a freshly uploaded file, or the current cover kept (only the crop moves).
type Pick = { kind: 'upload'; file: File } | { kind: 'keep' }
const pick = ref<Pick | null>(null)
const sourceUrl = ref('')
const uploadObjectUrl = ref<string | null>(null) // an owned object URL for a freshly picked file (revoke on replace/close)
const fileInput = ref<HTMLInputElement | null>(null)
const imageElement = ref<HTMLImageElement | null>(null)
const naturalWidth = ref(0)
const naturalHeight = ref(0)
const zoom = ref(1)
const offsetX = ref(0)
const offsetY = ref(0)
// The crop to restore once the picked image's natural size is known (set only when reopening an existing cover).
const initialCrop = ref<CoverCrop | null>(props.cover?.crop ?? null)

const hasSource = computed(() => Boolean(sourceUrl.value))
const imageLoaded = computed(() => naturalWidth.value > 0 && naturalHeight.value > 0)
const canSave = computed(() => imageLoaded.value && pick.value !== null)
const baseScale = computed(() => {
  if (!naturalWidth.value || !naturalHeight.value) return 1
  return Math.max(cropWidth.value / naturalWidth.value, cropHeight.value / naturalHeight.value)
})
const baseImageWidth = computed(() => naturalWidth.value * baseScale.value)
const baseImageHeight = computed(() => naturalHeight.value * baseScale.value)
const displayedImageStyle = computed(() => ({
  width: `${baseImageWidth.value * zoom.value}px`,
  height: `${baseImageHeight.value * zoom.value}px`,
  transform: `translate(${offsetX.value}px, ${offsetY.value}px)`,
}))

const dragging = ref(false)
const dragStart = ref({ pointerId: 0, x: 0, y: 0, offsetX: 0, offsetY: 0 })

// Open re-cropping the current cover (its decrypted image), else empty (upload only). The stored crop is applied
// once the image's natural size is known (loadImage); `initialCrop` defaults to `props.cover?.crop`.
if (props.cover && props.existingSrc) {
  pick.value = { kind: 'keep' }
  sourceUrl.value = props.existingSrc
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/** Reset the pan/zoom for a freshly picked source (a reopened cover restores its crop instead, via initialCrop). */
function resetView() {
  initialCrop.value = null
  naturalWidth.value = 0
  naturalHeight.value = 0
  zoom.value = 1
  offsetX.value = 0
  offsetY.value = 0
}

function revokeUpload() {
  if (uploadObjectUrl.value) URL.revokeObjectURL(uploadObjectUrl.value)
  uploadObjectUrl.value = null
}

function chooseUpload() {
  fileInput.value?.click()
}

function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = '' // allow re-picking the same file
  if (!file || !file.type.startsWith('image/')) return
  revokeUpload()
  pick.value = { kind: 'upload', file }
  resetView()
  uploadObjectUrl.value = URL.createObjectURL(file)
  sourceUrl.value = uploadObjectUrl.value
}

async function loadImage() {
  const image = imageElement.value
  if (!image) return
  naturalWidth.value = image.naturalWidth
  naturalHeight.value = image.naturalHeight
  await nextTick()
  measureCropper() // the cropper fills the dialog width by now — measure before computing the crop
  if (initialCrop.value) applyCrop(initialCrop.value)
  else centerImage()
}

function centerImage() {
  offsetX.value = (cropWidth.value - baseImageWidth.value * zoom.value) / 2
  offsetY.value = (cropHeight.value - baseImageHeight.value * zoom.value) / 2
  clampOffset()
}

function applyCrop(crop: CoverCrop) {
  const nextZoom = clamp(cropWidth.value / (crop.width * naturalWidth.value * baseScale.value), 1, 3)
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
  const centerX = cropWidth.value / 2
  const centerY = cropHeight.value / 2
  offsetX.value = centerX - ((centerX - offsetX.value) / previousZoom) * nextZoom
  offsetY.value = centerY - ((centerY - offsetY.value) / previousZoom) * nextZoom
  zoom.value = nextZoom
  clampOffset()
}

function startDrag(event: PointerEvent) {
  if (!imageLoaded.value) return
  dragging.value = true
  dragStart.value = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, offsetX: offsetX.value, offsetY: offsetY.value }
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

// Clamp the pan so the (base-scale-filled) image never leaves an empty corner inside the crop frame.
function clampOffset() {
  const width = baseImageWidth.value * zoom.value
  const height = baseImageHeight.value * zoom.value
  offsetX.value = clamp(offsetX.value, Math.min(0, cropWidth.value - width), 0)
  offsetY.value = clamp(offsetY.value, Math.min(0, cropHeight.value - height), 0)
}

// Normalize the current pan/zoom back to a `0..1` crop rectangle against the source image (the stored shape).
function currentCoverCrop(): CoverCrop {
  const scale = baseScale.value * zoom.value
  return {
    x: clamp(-offsetX.value / scale / naturalWidth.value, 0, 1),
    y: clamp(-offsetY.value / scale / naturalHeight.value, 0, 1),
    width: clamp(cropWidth.value / scale / naturalWidth.value, 0, 1),
    height: clamp(cropHeight.value / scale / naturalHeight.value, 0, 1),
  }
}

function save() {
  const p = pick.value
  if (!p || !imageLoaded.value) return
  const crop = currentCoverCrop()
  if (p.kind === 'upload') emit('save', { kind: 'upload', file: p.file, crop })
  else emit('save', { kind: 'keep', crop })
  emit('close')
}

function remove() {
  emit('save', { kind: 'remove' })
  emit('close')
}

// Keep the crop viewport in sync with the dialog width (re-measure + re-clamp the pan so no empty corner shows).
function onResize() {
  measureCropper()
  clampOffset()
}
onMounted(() => {
  window.addEventListener('resize', onResize)
  // A reopened cover shows an already-cached image whose `@load` may not refire — measure the laid-out cropper
  // and compute the crop here so it fills the width on open, not only after a resize.
  void nextTick(() => {
    measureCropper()
    const img = imageElement.value
    if (img?.complete && img.naturalWidth > 0) void loadImage()
  })
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  revokeUpload()
})
</script>

<template>
  <Dialog :visible="true" :header="t('memory.cover.choose')" modal @update:visible="emit('close')">
    <div class="veyq-form memory-cover-dialog">
      <p class="memory-cover-copy">{{ hasMedia ? t('memory.cover.copy') : t('memory.cover.copyNoMedia') }}</p>

      <div class="memory-cover-sources">
        <Button type="button" :label="t('memory.cover.upload')" icon="pi pi-upload" severity="secondary" outlined @click="chooseUpload" />
        <input ref="fileInput" type="file" accept="image/*" class="memory-cover-file" @change="onFilePicked" />
      </div>

      <div v-if="hasSource" class="memory-cover-editor">
        <div
          ref="cropperEl"
          class="memory-cover-cropper"
          :class="{ 'memory-cover-cropper-dragging': dragging }"
          style="aspect-ratio: 16 / 9"
          @pointerdown="startDrag"
          @pointermove="drag"
          @pointerup="stopDrag"
          @pointercancel="stopDrag"
        >
          <img ref="imageElement" :src="sourceUrl" :style="displayedImageStyle" draggable="false" alt="" @load="loadImage" />
        </div>
        <label class="memory-cover-zoom">
          <span>{{ t('memory.zoom') }}</span>
          <input type="range" min="1" max="3" step="0.01" :value="zoom" :disabled="!imageLoaded" @input="updateZoom" />
        </label>
      </div>

      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="emit('close')" />
        <div class="veyq-action-group">
          <Button
            type="button"
            :label="t('memory.cover.remove')"
            icon="pi pi-trash"
            severity="danger"
            outlined
            :disabled="!cover"
            @click="remove"
          />
          <Button type="button" :label="t('memory.cover.save')" icon="pi pi-check" :disabled="!canSave" @click="save" />
        </div>
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.memory-cover-dialog {
  /* A fixed, comfortable width so the copy wraps + the full-width cropper stays a sensible size in every state
     (with/without the media picker) — without it the long copy line + fluid cropper balloon the dialog. */
  width: min(500px, 92vw);
  gap: 0.85rem;
}
.memory-cover-copy {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.memory-cover-sources {
  display: flex;
  gap: 0.5rem;
}
.memory-cover-file {
  display: none;
}
.memory-cover-editor {
  display: grid;
  justify-items: stretch;
  gap: 0.85rem;
}
.memory-cover-cropper {
  position: relative;
  width: 100%;
  overflow: hidden;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-backdrop-strong);
  cursor: grab;
  touch-action: none;
}
.memory-cover-cropper::after {
  position: absolute;
  inset: 0;
  border: 2px solid rgb(255 255 255 / 82%);
  box-shadow: inset 0 0 0 999px rgb(15 23 42 / 8%);
  content: '';
  pointer-events: none;
}
.memory-cover-cropper-dragging {
  cursor: grabbing;
}
.memory-cover-cropper img {
  position: absolute;
  top: 0;
  left: 0;
  max-width: none;
  user-select: none;
  will-change: transform;
}
.memory-cover-zoom {
  display: grid;
  grid-template-columns: auto minmax(180px, 1fr);
  align-items: center;
  gap: 0.75rem;
  width: 100%;
  color: var(--app-control-text);
  font-size: 0.9rem;
}
.memory-cover-zoom input {
  width: 100%;
}
</style>
