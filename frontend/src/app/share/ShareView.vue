<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { session } from '../session'
import { i18n, t } from '../../i18n/i18n'
import { calculateJustifiedLayout } from '../../features/gallery/justifiedLayout'
import { formatDateRange, formatDayLabel, groupByDay } from '../../features/gallery/dateBuckets'
import type { GalleryTile } from '../../features/gallery/gallery'
import { getShareJournal, loadShareOriginal, loadShareTiles, resolvePublicShare, SharePasswordRequired, uploadShareMedia, type ShareContext, type ShareNotice } from '../../features/memory/share/share'
import { renderMarkdown } from '../../features/memory/journal/markdown'
import { fileToNewMedia } from '../../features/uploads/clientMedia'
import { listMySpaces } from '../../features/spaces/switchActiveSpace/switchActiveSpace'

const props = defineProps<{ shareId: string; shareKey: string }>()
const emit = defineEmits<{ internal: [target: { scopeId: string; nodeId: string }] }>()

const ctx = ref<ShareContext | null>(null)
const title = ref('')
const tiles = ref<GalleryTile[]>([])
const loading = ref(true)
const error = ref('')
const containerWidth = ref(0)
const galleryEl = ref<HTMLElement | null>(null)
const lightbox = ref<string | null>(null)
const journal = ref('')
const uploading = ref(false)
const needPassword = ref(false)
const passwordInput = ref('')
const passwordWrong = ref(false)
const pendingNotices = ref<ShareNotice[]>([])
const noticeKey = (shareId: string, n: ShareNotice) => `veyq.share.accept.${shareId}.${n.scope}.${n.version}`
const fileInput = ref<HTMLInputElement | null>(null)
const isContributor = computed(() => ctx.value?.role === 'CONTRIBUTOR')
const canRead = computed(() => ctx.value?.role !== 'UPLOADER')
const canUpload = computed(() => ctx.value?.role === 'CONTRIBUTOR' || ctx.value?.role === 'UPLOADER')
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
    // A logged-in member who already holds this scope gets the FULL internal memory (not the guest view).
    if (session.unlocked) {
      const peek = await fetch(`${session.apiBase}/api/public/shares/${props.shareId}`)
      if (peek.ok) {
        const body = await peek.json()
        const spaces = await listMySpaces(session.apiBase, session.unlocked).catch(() => [])
        if (spaces.some((s) => s.scopeId === body.scopeId)) {
          emit('internal', { scopeId: body.scopeId, nodeId: body.rootNodeId })
          return
        }
      }
    }
  } catch {
    error.value = t('share.notFound')
    loading.value = false
    return
  }
  await load()
})

async function load(password?: string) {
  loading.value = true
  try {
    const c = await resolvePublicShare(session.apiBase, props.shareId, props.shareKey, password)
    ctx.value = c
    title.value = c.rootName || t('share.uploadOnlyTitle') // uploader gets no album name (blind drop-off)
    needPassword.value = false
    // Consent gate: present any not-yet-accepted notices and block content until the guest clicks Accept.
    pendingNotices.value = c.notices.filter((n) => localStorage.getItem(noticeKey(c.shareId, n)) !== 'true')
    if (pendingNotices.value.length) { loading.value = false; return }
    await loadContent()
  } catch (e) {
    if (e instanceof SharePasswordRequired) {
      needPassword.value = true
      passwordWrong.value = e.wrong
    } else {
      error.value = t('share.notFound')
    }
  } finally {
    loading.value = false
  }
}
function submitPassword() {
  load(passwordInput.value)
}
async function acceptNotices() {
  const c = ctx.value
  if (!c) return
  c.notices.forEach((n) => localStorage.setItem(noticeKey(c.shareId, n), 'true'))
  pendingNotices.value = []
  loading.value = true
  await loadContent()
  loading.value = false
}
async function loadContent() {
  const c = ctx.value
  if (!c) return
  // An UPLOADER is a blind drop-off — it cannot read the gallery or journal (those endpoints 403).
  if (c.role !== 'UPLOADER') {
    tiles.value = await loadShareTiles(c, c.rootNodeId)
    journal.value = await getShareJournal(c).catch(() => '')
    await nextTick()
    if (galleryEl.value && ro) ro.observe(galleryEl.value) // the gallery only renders after content loads
  }
}
const noticesHtml = computed(() => pendingNotices.value.map((n) => renderMarkdown(n.markdown)).join('<hr class="notice-sep">'))

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
      <template v-if="canUpload">
        <button class="btn btn-primary" type="button" :disabled="uploading" @click="fileInput?.click()">
          {{ uploading ? t('detail.uploading') : t('share.addPhotos') }}
        </button>
        <input ref="fileInput" type="file" accept="image/*,video/*" multiple hidden @change="onFilesPicked" />
      </template>
      <span class="share-badge">{{ isContributor ? t('share.contribute') : canRead ? t('share.readOnly') : t('share.uploadOnly') }}</span>
    </header>

    <main class="share-content">
      <div v-if="error" class="home-empty">{{ error }}</div>

      <div v-if="needPassword" class="share-dropbox">
        <div class="share-drop-icon">🔒</div>
        <p class="share-drop-title">{{ t('share.passwordTitle') }}</p>
        <div v-if="passwordWrong" class="auth-msg auth-msg-error">{{ t('share.passwordWrong') }}</div>
        <form class="veyq-form" @submit.prevent="submitPassword">
          <input v-model="passwordInput" type="password" autocomplete="off" autofocus />
          <button class="btn btn-primary" type="submit" :disabled="loading">{{ t('share.passwordSubmit') }}</button>
        </form>
      </div>

      <section v-if="pendingNotices.length && !error" class="share-notice">
        <h2 class="bucket-date">{{ t('share.termsTitle') }}</h2>
        <div class="journal-body share-notice-body" v-html="noticesHtml"></div>
        <button class="btn btn-primary share-accept" type="button" :disabled="loading" @click="acceptNotices">{{ t('share.accept') }}</button>
      </section>

      <div v-if="ctx && !pendingNotices.length && !canRead && !error" class="share-dropbox">
        <div class="share-drop-icon">⬆</div>
        <p class="share-drop-title">{{ t('share.uploadOnlyTitle') }}</p>
        <p class="home-empty">{{ t('share.uploadOnlyHint') }}</p>
        <button class="btn btn-primary" type="button" :disabled="uploading" @click="fileInput?.click()">
          {{ uploading ? t('detail.uploading') : t('share.addPhotos') }}
        </button>
      </div>

      <div v-if="loading && !ctx && !needPassword" class="home-empty">{{ t('detail.loadingGallery') }}</div>

      <template v-if="ctx && !pendingNotices.length && canRead">
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

      <section v-if="journal.trim()" class="share-journal journal">
        <div class="bucket-date">{{ t('detail.journal') }}</div>
        <div class="journal-body" v-html="journalHtml"></div>
      </section>
      </template>
    </main>

    <div v-if="lightbox" class="lightbox" @click="closeLightbox">
      <img :src="lightbox" alt="" @click.stop />
    </div>
  </div>
</template>
