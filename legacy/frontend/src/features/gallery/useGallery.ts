// Shared gallery view-state for the two galleries (member MemoryDetailView + guest ShareView): the tile set,
// its justified day-bucket layout, the ResizeObserver that drives the layout width, the lightbox (PREVIEW on
// open, full-res original only on an explicit download) and the object-URL lifecycle. Both views differ only
// in HOW they fetch a tile's preview/original (member scope vs. share capability), so those come in as loaders.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { i18n } from '../../i18n/i18n'
import { calculateJustifiedLayout } from './justifiedLayout'
import { formatDateRange, formatDayLabel, groupByDay } from './dateBuckets'
import type { GalleryTile } from './gallery'

export type UseGalleryOptions = {
  /** Load + decrypt a tile's PREVIEW (≈2048px) to an object URL — shown in the lightbox. */
  loadPreview: (tile: GalleryTile) => Promise<string>
  /** Load + decrypt a tile's full-resolution original to an object URL — only on an explicit download. */
  loadOriginal: (tile: GalleryTile) => Promise<string>
  /** Download filename for a saved original (defaults to `<id8>.<ext>`). */
  downloadFileName?: (tile: GalleryTile, ext: string) => string
  /** Extra cleanup when the lightbox closes (e.g. disarm a delete confirmation). */
  onLightboxClose?: () => void
  /** Handle a download failure (default: swallow). */
  onError?: (e: Error) => void
}

export function useGallery(opts: UseGalleryOptions) {
  const tiles = ref<GalleryTile[]>([])
  const containerWidth = ref(0)
  const galleryEl = ref<HTMLElement | null>(null)
  const lightbox = ref<string | null>(null)
  const lightboxTile = ref<GalleryTile | null>(null)
  const downloading = ref(false)
  const targetTileHeight = computed(() => (containerWidth.value <= 720 ? 150 : 200))
  let ro: ResizeObserver | null = null

  // Group the (takenAt-ordered) tiles into day buckets, each with its own justified layout — a dated timeline.
  const buckets = computed(() =>
    groupByDay(tiles.value).map((b) => ({
      key: b.key,
      label: formatDayLabel(b.key, i18n.locale),
      layout: calculateJustifiedLayout({
        items: b.items.map((t) => ({ id: t.id, aspectRatio: t.aspectRatio })),
        containerWidth: containerWidth.value,
        targetHeight: targetTileHeight.value,
        gap: 8,
      }),
    })),
  )
  const dateRange = computed(() => formatDateRange(tiles.value, i18n.locale))
  const tileById = computed(() => Object.fromEntries(tiles.value.map((t) => [t.id, t])))

  /** (Re)attach the ResizeObserver to the gallery element — call after the gallery first renders (v-if).
   *  Seed the width immediately from the element too, so the first layout doesn't depend on the
   *  ResizeObserver's initial callback (which some browsers don't deliver synchronously). */
  function observe() {
    if (!galleryEl.value) return
    containerWidth.value = galleryEl.value.clientWidth
    ro?.observe(galleryEl.value)
  }

  /** Revoke every tile thumbnail's object URL — call before replacing the tile set (avoids leaks). */
  function revokeTiles() { tiles.value.forEach((t) => URL.revokeObjectURL(t.url)) }

  onMounted(() => {
    ro = new ResizeObserver((entries) => { containerWidth.value = entries[0].contentRect.width })
    observe() // a v-show gallery is already in the DOM; a v-if one re-observes after content loads.
  })
  onBeforeUnmount(() => {
    ro?.disconnect()
    revokeTiles()
    if (lightbox.value) URL.revokeObjectURL(lightbox.value)
  })

  /** Open the lightbox on a tile: load + show its PREVIEW (full-res originals only on explicit download). */
  async function openLightbox(tile: GalleryTile) {
    lightboxTile.value = tile
    lightbox.value = await opts.loadPreview(tile)
  }
  function closeLightbox() {
    if (lightbox.value) URL.revokeObjectURL(lightbox.value)
    lightbox.value = null
    lightboxTile.value = null
    opts.onLightboxClose?.()
  }

  /** Fetch the open tile's full-resolution original and save it to disk (the only path that pulls full-res). */
  async function downloadThisOriginal() {
    const tile = lightboxTile.value
    if (!tile || downloading.value) return
    downloading.value = true
    let url: string | null = null
    try {
      url = await opts.loadOriginal(tile)
      const ext = (tile.mimeType.split('/')[1] || 'bin').replace('jpeg', 'jpg')
      const a = document.createElement('a')
      a.href = url
      a.download = opts.downloadFileName ? opts.downloadFileName(tile, ext) : `${tile.id.slice(0, 8)}.${ext}`
      document.body.appendChild(a)
      a.click()
      a.remove()
    } catch (e) {
      opts.onError?.(e as Error)
    } finally {
      if (url) URL.revokeObjectURL(url)
      downloading.value = false
    }
  }

  return {
    tiles, containerWidth, galleryEl, lightbox, lightboxTile, downloading,
    buckets, dateRange, tileById,
    observe, revokeTiles, openLightbox, closeLightbox, downloadThisOriginal,
  }
}
