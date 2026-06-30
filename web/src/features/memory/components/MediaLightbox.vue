<script setup lang="ts">
// The Memory gallery lightbox (cr-0049, uc.memory.gallery). Ported from the design source's MediaLightbox
// (legacy/frontend-noe2ee) — same markup/classes (media.css) — with the data layer swapped to the operator-
// blind tiers: the stage shows the **preview** derivative (openLightbox), never the multi-MB original; the
// explicit download fetches the **original** (openOriginal). Every byte is fetched as ciphertext and decrypted
// on device; the decrypted preview becomes an object URL that is **revoked** on navigation / close / unmount.
// Out of scope (own CRs): metadata sidebar, highlight, captured-at edit, delete, fullscreen-original, sharing.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { openLightbox, openOriginal, hasDerivatives, type MediaItem } from '../media'
import { derivedDownloadName, saveBytes } from '../download'

const props = defineProps<{
  scopeId: string
  memoryKey: Uint8Array
  items: MediaItem[]
  startIndex: number
  memoryName: string
}>()
const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()

const current = ref(props.startIndex)
const previewUrl = ref<string | null>(null)
const loading = ref(false)
const error = ref(false)
const downloading = ref(false)

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

    <main class="media-lightbox-layout media-lightbox-layout-viewer-only">
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
    </main>
  </div>
</template>

<style scoped>
/* The slice ships the viewer only (the metadata sidebar is a later CR) — so the stage spans the full width
   instead of the design's viewer + sidebar split. */
.media-lightbox-layout-viewer-only {
  grid-template-columns: minmax(0, 1fr);
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
