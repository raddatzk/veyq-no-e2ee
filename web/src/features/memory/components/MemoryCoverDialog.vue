<script setup lang="ts">
// The Memory cover editor (cr-0072, uc.memory.cover main flow §1–2). A picker over the Memory's own **decrypted**
// media, then a fixed **16:9** crop viewport with **drag-to-pan** (pointer events) + a **zoom slider (1–3×)**.
// **Save** emits the normalized `{ mediaId, crop }` (`0..1` against the source image); **Remove** emits `null`.
// Reopening an existing cover restores its pan/zoom. Ported component-for-component from the design source's
// `MemoryCoverDialog` (the file-upload source is deferred to cr-0073) — this component is presentation only: the
// parent seals the emitted cover under `K_mem`, it never touches crypto or the network. The crop math (base-scale
// fill, clamp-so-no-empty-corners, normalize back to `0..1`) is the legacy math verbatim.
import { computed, nextTick, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import type { CoverCrop, CoverPickItem, MemoryCover } from '../memoryCover'

const props = defineProps<{
  items: CoverPickItem[] // the Memory's decrypted image media (id + thumbnail object URL) — the crop source
  cover: MemoryCover | null // the current stored cover (restore its media + pan/zoom on open), or null
}>()
const emit = defineEmits<{ save: [MemoryCover | null]; close: [] }>()
const { t } = useI18n()

const cropWidth = 320
const cropHeight = 180

const selectedMediaId = ref<string | null>(props.cover?.mediaId ?? null)
const sourceUrl = ref('')
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
const baseScale = computed(() => {
  if (!naturalWidth.value || !naturalHeight.value) return 1
  return Math.max(cropWidth / naturalWidth.value, cropHeight / naturalHeight.value)
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

// Preselect the current cover's media so the cropper opens on it (its pan/zoom restored once it loads).
if (props.cover) {
  const current = props.items.find((item) => item.mediaId === props.cover!.mediaId)
  if (current) sourceUrl.value = current.url
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function selectMedia(item: CoverPickItem) {
  selectedMediaId.value = item.mediaId
  initialCrop.value = null // a freshly picked media centers full-frame; only a reopened cover restores a crop
  naturalWidth.value = 0
  naturalHeight.value = 0
  zoom.value = 1
  offsetX.value = 0
  offsetY.value = 0
  sourceUrl.value = item.url
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

function centerImage() {
  offsetX.value = (cropWidth - baseImageWidth.value * zoom.value) / 2
  offsetY.value = (cropHeight - baseImageHeight.value * zoom.value) / 2
  clampOffset()
}

function applyCrop(crop: CoverCrop) {
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
  offsetX.value = clamp(offsetX.value, Math.min(0, cropWidth - width), 0)
  offsetY.value = clamp(offsetY.value, Math.min(0, cropHeight - height), 0)
}

// Normalize the current pan/zoom back to a `0..1` crop rectangle against the source image (the stored shape).
function currentCoverCrop(): CoverCrop {
  const scale = baseScale.value * zoom.value
  return {
    x: clamp(-offsetX.value / scale / naturalWidth.value, 0, 1),
    y: clamp(-offsetY.value / scale / naturalHeight.value, 0, 1),
    width: clamp(cropWidth / scale / naturalWidth.value, 0, 1),
    height: clamp(cropHeight / scale / naturalHeight.value, 0, 1),
  }
}

function save() {
  if (!selectedMediaId.value || !imageLoaded.value) return
  emit('save', { mediaId: selectedMediaId.value, crop: currentCoverCrop() })
  emit('close')
}

function remove() {
  emit('save', null)
  emit('close')
}
</script>

<template>
  <Dialog :visible="true" :header="t('memory.cover.choose')" modal @update:visible="emit('close')">
    <div class="veyq-form memory-cover-dialog">
      <p class="memory-cover-copy">{{ t('memory.cover.copy') }}</p>

      <p v-if="!items.length" class="memory-cover-empty">{{ t('memory.cover.empty') }}</p>

      <div v-else class="memory-cover-picker" :aria-label="t('memory.cover.gallery')">
        <button
          v-for="item in items"
          :key="item.mediaId"
          type="button"
          class="memory-cover-option"
          :class="{ 'memory-cover-option-selected': selectedMediaId === item.mediaId }"
          @click="selectMedia(item)"
        >
          <img :src="item.url" :alt="item.name ?? item.mediaId" draggable="false" />
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
          <img ref="imageElement" :src="sourceUrl" :style="displayedImageStyle" draggable="false" alt="" @load="loadImage" />
        </div>
        <label class="memory-cover-zoom">
          <span>{{ t('memory.zoom') }}</span>
          <input type="range" min="1" max="3" step="0.01" :value="zoom" :disabled="!imageLoaded" @input="updateZoom" />
        </label>
      </div>

      <div class="dialog-actions">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="emit('close')" />
        <div class="veyq-action-group">
          <Button
            type="button"
            :label="t('memory.cover.remove')"
            icon="pi pi-trash"
            severity="secondary"
            outlined
            :disabled="!cover"
            @click="remove"
          />
          <Button type="button" :label="t('memory.cover.save')" icon="pi pi-check" :disabled="!selectedMediaId || !imageLoaded" @click="save" />
        </div>
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.memory-cover-dialog {
  min-width: min(360px, 80vw);
  gap: 0.85rem;
}
.memory-cover-copy {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.memory-cover-empty {
  margin: 0;
  color: var(--app-muted);
}
.memory-cover-picker {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 0.6rem;
  max-height: 260px;
  overflow: auto;
}
.memory-cover-option {
  padding: 0;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-on-strong);
  cursor: pointer;
  overflow: hidden;
}
.memory-cover-option-selected {
  border-color: var(--app-accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-accent) 18%, transparent);
}
.memory-cover-option img {
  display: block;
  width: 100%;
  aspect-ratio: 4 / 3;
  background: var(--app-surface-muted);
  object-fit: cover;
}
.memory-cover-editor {
  display: grid;
  justify-items: center;
  gap: 0.85rem;
}
.memory-cover-cropper {
  position: relative;
  max-width: 100%;
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
  width: min(320px, 100%);
  color: var(--app-control-text);
  font-size: 0.9rem;
}
.memory-cover-zoom input {
  width: 100%;
}
.dialog-actions {
  display: flex;
  justify-content: space-between;
  gap: 0.5rem;
  margin-top: 0.5rem;
}
.veyq-action-group {
  display: flex;
  gap: 0.5rem;
}
</style>
