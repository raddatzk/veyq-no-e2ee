<script setup lang="ts">
// The Memory gallery lightbox (cr-0049, uc.memory.gallery). Ported from the design source's MediaLightbox
// (legacy/frontend-noe2ee) — same markup/classes (media.css) — with the data layer swapped to the operator-
// blind tiers: the stage shows the **preview** derivative (openLightbox), never the multi-MB original; the
// explicit download fetches the **original** (openOriginal). Every byte is fetched as ciphertext and decrypted
// on device; the decrypted preview becomes an object URL that is **revoked** on navigation / close / unmount.
// The detail panel (cr-0064) shows the lazily-loaded EXIF tier and lets the user **correct the capture time**
// (cr-0065, sealed under K_mem). Out of scope (own CRs): highlight, delete, fullscreen-original, sharing.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  openLightbox,
  openOriginal,
  hasDerivatives,
  loadMediaExif,
  setMediaCapturedAt,
  effectiveCapturedAt,
  type MediaItem,
  type MediaExif,
} from '../media'
import { derivedDownloadName, saveBytes } from '../download'

const props = defineProps<{
  scopeId: string
  memoryKey: Uint8Array
  items: MediaItem[]
  startIndex: number
  memoryName: string
}>()
const emit = defineEmits<{ close: [] }>()

const { t, locale } = useI18n()

const current = ref(props.startIndex)
const previewUrl = ref<string | null>(null)
const loading = ref(false)
const error = ref(false)
const downloading = ref(false)
// Tier-2 EXIF (cr-0064), lazy-loaded per item via the meta endpoint + unsealed under K_mem. Null = none/undecoded.
const exif = ref<MediaExif | null>(null)
const detailsLoading = ref(false)
const isDetailsOpen = ref(false) // mobile bottom-sheet toggle (the design's swipe handle)
// capturedAt override editor (cr-0065)
const isEditingCapturedAt = ref(false)
const capturedAtInput = ref('') // bound to <input type="datetime-local">
const savingCapturedAt = ref(false)
const capturedAtError = ref(false)

const item = computed(() => props.items[current.value])
const canPrev = computed(() => current.value > 0)
const canNext = computed(() => current.value < props.items.length - 1)
const previewable = computed(() => (item.value ? hasDerivatives(item.value) : false))

function revokePreview() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = null
  }
}

// Load the current item's preview tier (operator-blind: ciphertext → decrypt → object URL). A non-derivable
// item (E1) carries no preview tier, so the stage shows a download fallback instead of fetching its original.
async function loadPreview() {
  revokePreview()
  error.value = false
  if (!item.value || !previewable.value) return
  loading.value = true
  const target = item.value
  try {
    const bytes = await openLightbox(props.scopeId, props.memoryKey, target)
    if (item.value !== target) return // navigated away while decrypting — drop this result
    previewUrl.value = URL.createObjectURL(new Blob([bytes as BlobPart]))
  } catch {
    if (item.value === target) error.value = true
  } finally {
    if (item.value === target) loading.value = false
  }
}

watch(current, loadPreview, { immediate: true })

// Lazily fetch + unseal this item's EXIF tier when it opens (operator-blind; the list never carries EXIF).
async function loadDetails() {
  exif.value = null
  isEditingCapturedAt.value = false // close the editor when the item changes
  capturedAtError.value = false
  const target = item.value
  if (!target) return
  detailsLoading.value = true
  try {
    const loaded = await loadMediaExif(props.scopeId, target.mediaId, props.memoryKey)
    if (item.value === target) exif.value = loaded // ignore a result for an item we've navigated away from
  } catch {
    if (item.value === target) exif.value = null
  } finally {
    if (item.value === target) detailsLoading.value = false
  }
}
watch(current, loadDetails, { immediate: true })

// --- capturedAt override (cr-0065): effective time = override ?? original; edit re-seals the EXIF tier ---
const effectiveCaptured = computed(() => effectiveCapturedAt(exif.value))
const isCapturedAtCorrected = computed(() => exif.value?.capturedAtOverride !== undefined)
// editable once the (lazy) EXIF load settled — even with no EXIF, the user can *set* a date (a fresh bundle)
const canEditCapturedAt = computed(() => !!item.value && !detailsLoading.value)

/** epoch-ms → the `<input type="datetime-local">` value (local wall-clock, minute precision). */
function msToDatetimeLocal(ms: number | undefined): string {
  if (ms === undefined || !Number.isFinite(ms)) return ''
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}
/** the datetime-local value → epoch-ms (local wall-clock), or null when empty/invalid. */
function datetimeLocalToMs(value: string): number | null {
  if (!value) return null
  const ms = new Date(value).getTime()
  return Number.isFinite(ms) ? ms : null
}

function startCapturedAtEdit() {
  capturedAtInput.value = msToDatetimeLocal(effectiveCaptured.value)
  capturedAtError.value = false
  isEditingCapturedAt.value = true
}

async function persistCapturedAt(overrideMs: number | null) {
  const target = item.value
  if (!target) return
  savingCapturedAt.value = true
  capturedAtError.value = false
  try {
    const updated = await setMediaCapturedAt(props.scopeId, target.mediaId, props.memoryKey, exif.value, overrideMs)
    if (item.value !== target) return // navigated away mid-save — drop the stale result
    exif.value = updated
    isEditingCapturedAt.value = false
  } catch {
    if (item.value === target) capturedAtError.value = true
  } finally {
    if (item.value === target) savingCapturedAt.value = false
  }
}
/** Save the edited date as the override (empty input clears it). */
const saveCapturedAt = () => persistCapturedAt(datetimeLocalToMs(capturedAtInput.value))
/** Reset back to the extracted original (removes the override). */
const resetCapturedAt = () => persistCapturedAt(null)

// --- EXIF value formatting (locale-aware; every field optional → empty string filtered out of the list) ---
const dateFormat = computed(() => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }))
function formatCaptured(ms?: number): string {
  return ms !== undefined && Number.isFinite(ms) ? dateFormat.value.format(new Date(ms)) : ''
}
function formatFocal(mm?: number): string {
  return mm ? `${Math.round(mm)} mm` : ''
}
function formatAperture(f?: number): string {
  return f ? `f/${Number(f.toFixed(1))}` : ''
}
function formatExposure(s?: number): string {
  if (!s || s <= 0) return ''
  return s >= 1 ? `${Number(s.toFixed(1))} s` : `1/${Math.round(1 / s)} s`
}

const hasGps = computed(() => {
  const e = exif.value
  return !!e && e.gpsLat !== undefined && e.gpsLng !== undefined
})
const gpsLabel = computed(() =>
  hasGps.value ? `${exif.value!.gpsLat!.toFixed(6)}, ${exif.value!.gpsLng!.toFixed(6)}` : '',
)
const metadataRows = computed(() => {
  const e = exif.value
  if (!e) return []
  return [
    { label: t('media.captured'), value: formatCaptured(effectiveCaptured.value) },
    { label: t('media.camera'), value: [e.make, e.model].filter(Boolean).join(' ').trim() },
    { label: t('media.lens'), value: e.lens ?? '' },
    { label: t('media.focalLength'), value: formatFocal(e.focalLength) },
    { label: t('media.aperture'), value: formatAperture(e.fNumber) },
    { label: t('media.exposure'), value: formatExposure(e.exposureTime) },
    { label: t('media.iso'), value: e.iso !== undefined ? String(e.iso) : '' },
    { label: t('media.dimensions'), value: e.w && e.h ? `${e.w} × ${e.h} px` : '' },
  ].filter((row) => row.value)
})
const hasDetail = computed(() => metadataRows.value.length > 0 || hasGps.value)

function prev() {
  if (canPrev.value) current.value -= 1
}
function next() {
  if (canNext.value) current.value += 1
}

async function download() {
  if (!item.value || downloading.value) return
  downloading.value = true
  const target = item.value
  try {
    const bytes = await openOriginal(props.scopeId, props.memoryKey, target)
    saveBytes(bytes, derivedDownloadName(props.memoryName, current.value, bytes))
  } catch {
    error.value = true
  } finally {
    downloading.value = false
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
  else if (event.key === 'ArrowLeft') prev()
  else if (event.key === 'ArrowRight') next()
}
window.addEventListener('keydown', onKeydown)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  revokePreview()
})
</script>

<template>
  <div
    class="media-lightbox"
    role="dialog"
    aria-modal="true"
    :aria-label="memoryName"
    @click.self="emit('close')"
  >
    <div class="media-lightbox-topbar">
      <div class="media-lightbox-title">
        <strong>{{ memoryName }}</strong>
        <small>{{ t('media.itemPosition', { index: current + 1, count: items.length }) }}</small>
      </div>
      <div class="media-lightbox-actions">
        <button
          class="veyq-icon-button lightbox-icon-button"
          type="button"
          :aria-label="t('media.download')"
          :title="t('media.download')"
          :disabled="downloading"
          @click="download"
        >
          <span class="pi" :class="downloading ? 'pi-spin pi-spinner' : 'pi-download'" />
        </button>
        <button
          class="veyq-icon-button lightbox-icon-button"
          type="button"
          :aria-label="t('media.close')"
          @click="emit('close')"
        >
          <span class="pi pi-times" />
        </button>
      </div>
    </div>

    <main class="media-lightbox-layout">
      <section class="media-lightbox-viewer">
        <button
          type="button"
          class="veyq-icon-button lightbox-nav lightbox-nav-previous"
          :aria-label="t('media.previous')"
          :disabled="!canPrev"
          @click="prev"
        >
          <span class="pi pi-chevron-left" />
        </button>

        <div class="media-lightbox-stage">
          <p v-if="error" class="media-lightbox-error">{{ t('media.loadError') }}</p>
          <img v-else-if="previewUrl" :src="previewUrl" :alt="memoryName" />
          <span v-else-if="loading" class="media-original-loading">
            <span class="pi pi-spin pi-spinner" />
            <span>{{ t('media.loading') }}</span>
          </span>
          <button
            v-else
            type="button"
            class="media-lightbox-file-fallback"
            :disabled="downloading"
            @click="download"
          >
            <span class="pi pi-file" />
            <span>{{ t('media.download') }}</span>
          </button>
        </div>

        <button
          type="button"
          class="veyq-icon-button lightbox-nav lightbox-nav-next"
          :aria-label="t('media.next')"
          :disabled="!canNext"
          @click="next"
        >
          <span class="pi pi-chevron-right" />
        </button>
      </section>

      <aside
        class="veyq-surface media-lightbox-details"
        :class="{ 'media-lightbox-details-open': isDetailsOpen }"
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
          <div v-if="canEditCapturedAt && !isEditingCapturedAt" class="media-lightbox-details-actions">
            <button
              class="veyq-icon-button lightbox-icon-button"
              type="button"
              :aria-label="t('media.editCapturedAt')"
              :title="t('media.editCapturedAt')"
              @click="startCapturedAtEdit"
            >
              <span class="pi pi-pencil" />
            </button>
          </div>
        </div>

        <form v-if="isEditingCapturedAt" class="media-lightbox-date-form" @submit.prevent="saveCapturedAt">
          <label for="captured-at-input">{{ t('media.captured') }}</label>
          <input id="captured-at-input" v-model="capturedAtInput" type="datetime-local" :disabled="savingCapturedAt" />
          <div class="media-lightbox-date-actions">
            <button class="veyq-text-button lightbox-text-button" type="submit" :disabled="savingCapturedAt">
              {{ t('common.save') }}
            </button>
            <button
              class="veyq-text-button lightbox-text-button"
              type="button"
              :disabled="savingCapturedAt"
              @click="isEditingCapturedAt = false"
            >
              {{ t('common.cancel') }}
            </button>
            <button
              v-if="isCapturedAtCorrected"
              class="veyq-text-button lightbox-text-button"
              type="button"
              :disabled="savingCapturedAt"
              @click="resetCapturedAt"
            >
              {{ t('media.resetCapturedAt') }}
            </button>
          </div>
          <p v-if="capturedAtError" class="media-lightbox-error">{{ t('media.capturedAtSaveFailed') }}</p>
        </form>

        <template v-else>
          <dl v-if="hasDetail" class="media-lightbox-metadata">
            <template v-for="row in metadataRows" :key="row.label">
              <dt>{{ row.label }}</dt>
              <dd>{{ row.value }}</dd>
            </template>
            <template v-if="isCapturedAtCorrected">
              <dt>{{ t('media.status') }}</dt>
              <dd>{{ t('media.capturedAtCorrected') }}</dd>
            </template>
            <template v-if="hasGps">
              <dt>{{ t('media.location') }}</dt>
              <dd>{{ gpsLabel }}</dd>
            </template>
          </dl>
          <p v-else class="media-lightbox-no-detail">{{ t('media.noDetail') }}</p>
        </template>
      </aside>
    </main>
  </div>
</template>

<style scoped>
.media-lightbox-no-detail {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.media-lightbox-date-form {
  display: grid;
  gap: 0.55rem;
}
.media-lightbox-date-form label {
  color: var(--app-muted);
  font-size: 0.8rem;
}
.media-lightbox-date-form input {
  width: 100%;
  padding: 0.4rem 0.55rem;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
  color: var(--app-text);
  font: inherit;
}
.media-lightbox-date-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.media-lightbox-file-fallback {
  display: grid;
  place-items: center;
  gap: 0.6rem;
  padding: 2rem;
  border: 1px dashed var(--app-line);
  border-radius: var(--app-radius);
  background: var(--app-surface);
  color: var(--app-text);
  font-weight: 700;
  cursor: pointer;
}
.media-lightbox-file-fallback .pi {
  font-size: 2rem;
}
</style>
