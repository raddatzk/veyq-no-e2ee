<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { session } from '../session'
import { i18n, t } from '../../i18n/i18n'
import { calculateJustifiedLayout } from '../../features/gallery/justifiedLayout'
import { formatDateRange, formatDayLabel, groupByDay } from '../../features/gallery/dateBuckets'
import type { GalleryTile } from '../../features/gallery/gallery'
import { getShareJournal, loadShareOriginal, loadShareTiles, resolvePublicShare, updateShareJournal, uploadShareMedia, type ShareContext } from '../../features/memory/share/share'
import { renderMarkdown } from '../../features/memory/journal/markdown'
import { fileToNewMedia } from '../../features/uploads/clientMedia'

const props = defineProps<{ shareId: string; shareKey: string }>()

const ctx = ref<ShareContext | null>(null)
const title = ref('')
const tiles = ref<GalleryTile[]>([])
const loading = ref(true)
const error = ref('')
const containerWidth = ref(0)
const galleryEl = ref<HTMLElement | null>(null)
const lightbox = ref<string | null>(null)
const journal = ref('')
const journalDraft = ref('')
const journalEditing = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const isContributor = computed(() => ctx.value?.role === 'CONTRIBUTOR')
const journalHtml = computed(() => renderMarkdown(journal.value))
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
    journal.value = await getShareJournal(c).catch(() => '')
  } catch {
    error.value = t('share.notFound')
  } finally {
    loading.value = false
  }
})

async function onFilesPicked(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  if (!files.length || !ctx.value) return
  uploading.value = true
  try {
    for (const file of files) await uploadShareMedia(ctx.value, await fileToNewMedia(file))
    tiles.value.forEach((tt) => URL.revokeObjectURL(tt.url))
    tiles.value = await loadShareTiles(ctx.value, ctx.value.rootNodeId)
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}
function startEditJournal() {
  journalDraft.value = journal.value
  journalEditing.value = true
}
async function saveJournal() {
  if (!ctx.value) return
  await updateShareJournal(ctx.value, journalDraft.value)
  journal.value = journalDraft.value
  journalEditing.value = false
}

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
      <template v-if="isContributor">
        <button class="btn btn-primary" type="button" :disabled="uploading" @click="fileInput?.click()">
          {{ uploading ? t('detail.uploading') : t('share.addPhotos') }}
        </button>
        <input ref="fileInput" type="file" accept="image/*,video/*" multiple hidden @change="onFilesPicked" />
      </template>
      <span class="share-badge">{{ isContributor ? t('share.contribute') : t('share.readOnly') }}</span>
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

      <section v-if="journal || journalEditing || isContributor" class="share-journal journal">
        <div class="bucket-date">{{ t('detail.journal') }}</div>
        <template v-if="journalEditing">
          <textarea v-model="journalDraft" class="journal-editor" :placeholder="t('journal.placeholder')"></textarea>
          <div class="journal-actions">
            <button class="btn btn-ghost" type="button" @click="journalEditing = false">{{ t('common.cancel') }}</button>
            <button class="btn btn-primary" type="button" @click="saveJournal">{{ t('journal.save') }}</button>
          </div>
        </template>
        <template v-else>
          <div v-if="isContributor" class="journal-bar"><button class="btn btn-ghost" type="button" @click="startEditJournal">{{ t('journal.edit') }}</button></div>
          <div v-if="journal.trim()" class="journal-body" v-html="journalHtml"></div>
          <div v-else-if="!isContributor" class="home-empty">{{ t('journal.empty') }}</div>
        </template>
      </section>
    </main>

    <div v-if="lightbox" class="lightbox" @click="closeLightbox">
      <img :src="lightbox" alt="" @click.stop />
    </div>
  </div>
</template>
