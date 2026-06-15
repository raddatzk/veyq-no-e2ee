<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { session } from '../session'
import { i18n, t } from '../../i18n/i18n'
import { calculateJustifiedLayout } from '../../features/gallery/justifiedLayout'
import { formatDateRange, formatDayLabel, groupByDay } from '../../features/gallery/dateBuckets'
import type { GalleryTile } from '../../features/gallery/gallery'
import { loadShareOriginal, loadShareTiles, resolvePublicShare, type ShareContext } from '../../features/memory/share/share'

const props = defineProps<{ shareId: string; shareKey: string }>()

const ctx = ref<ShareContext | null>(null)
const title = ref('')
const tiles = ref<GalleryTile[]>([])
const loading = ref(true)
const error = ref('')
const containerWidth = ref(0)
const galleryEl = ref<HTMLElement | null>(null)
const lightbox = ref<string | null>(null)
let ro: ResizeObserver | null = null

const buckets = computed(() =>
  groupByDay(tiles.value).map((b) => ({
    key: b.key,
    label: formatDayLabel(b.key, i18n.locale),
    layout: calculateJustifiedLayout({ items: b.items.map((tt) => ({ id: tt.id, aspectRatio: tt.aspectRatio })), containerWidth: containerWidth.value, targetHeight: 220, gap: 6 }),
  })),
)
const dateRange = computed(() => formatDateRange(tiles.value, i18n.locale))
const tileById = computed(() => Object.fromEntries(tiles.value.map((tt) => [tt.id, tt])))

onMounted(async () => {
  ro = new ResizeObserver((entries) => { containerWidth.value = entries[0].contentRect.width })
  if (galleryEl.value) ro.observe(galleryEl.value)
  try {
    const c = await resolvePublicShare(session.apiBase, props.shareId, props.shareKey)
    ctx.value = c
    title.value = c.rootName
    tiles.value = await loadShareTiles(c, c.rootNodeId)
  } catch {
    error.value = t('share.notFound')
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  ro?.disconnect()
  tiles.value.forEach((tt) => URL.revokeObjectURL(tt.url))
  if (lightbox.value) URL.revokeObjectURL(lightbox.value)
})

async function openLightbox(tile: GalleryTile) {
  if (!ctx.value) return
  lightbox.value = await loadShareOriginal(ctx.value, tile)
}
function closeLightbox() {
  if (lightbox.value) URL.revokeObjectURL(lightbox.value)
  lightbox.value = null
}
</script>

<template>
  <div class="share-view">
    <header class="share-head">
      <span class="auth-wordmark">veyq</span>
      <div class="share-titles">
        <h1 class="detail-title">{{ title || t('share.title') }}</h1>
        <span v-if="dateRange" class="detail-daterange">{{ dateRange }}</span>
      </div>
      <span class="share-badge">{{ t('share.readOnly') }}</span>
    </header>

    <main class="share-content">
      <div v-if="error" class="home-empty">{{ error }}</div>
      <div ref="galleryEl" class="gallery-buckets">
        <section v-for="b in buckets" :key="b.key" class="gallery-bucket">
          <h2 class="bucket-date">{{ b.label }}</h2>
          <div class="gallery" :style="{ height: b.layout.height + 'px' }">
            <button
              v-for="tile in b.layout.tiles"
              :key="tile.id"
              class="gallery-tile"
              :style="{ left: tile.left + 'px', top: tile.top + 'px', width: tile.renderedWidth + 'px', height: tile.renderedHeight + 'px' }"
              @click="openLightbox(tileById[tile.id])"
            >
              <img :src="tileById[tile.id].url" alt="" />
            </button>
          </div>
        </section>
      </div>
      <div v-if="loading" class="home-empty">{{ t('detail.loadingGallery') }}</div>
      <div v-else-if="!tiles.length && !error" class="home-empty">{{ t('detail.noMedia') }}</div>
    </main>

    <div v-if="lightbox" class="lightbox" @click="closeLightbox">
      <img :src="lightbox" alt="" @click.stop />
    </div>
  </div>
</template>
