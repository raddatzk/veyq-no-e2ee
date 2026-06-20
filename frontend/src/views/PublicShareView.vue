<script setup lang="ts">
// Guest-side public share viewer (no account, operator-blind). The share key rides the URL fragment
// (/s/<id>#k=…) and never reaches the server; the content key is unwrapped client-side (+ password if the
// link is protected). Read-only: decrypted gallery (lightbox) + journal. Contribution uploads are a later step.
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import {
  getShareJournal,
  loadShareOriginal,
  loadSharePreview,
  loadShareTiles,
  resolvePublicShare,
  SharePasswordRequired,
  uploadShareMedia,
  type ShareContext,
} from '../features/memory/share/share'
import { useGallery } from '../features/gallery/useGallery'
import { fileToNewMedia } from '../features/uploads/clientMedia'
import { renderMarkdown } from '../features/memory/journal/markdown'
import { session } from '../app/session'
import { t } from '../i18n/i18n'

const route = useRoute()
const shareId = String(route.params.shareId ?? '')
const shareKey = new URLSearchParams(window.location.hash.replace(/^#/, '')).get('k') ?? ''

const loading = ref(true)
const error = ref('')
const notFound = ref(false)
const passwordRequired = ref(false)
const passwordWrong = ref(false)
const password = ref('')
const ctx = ref<ShareContext | null>(null)
const journal = ref('')
const tab = ref<'gallery' | 'journal'>('gallery')

const { tiles, lightbox, downloading, revokeTiles, openLightbox, closeLightbox, downloadThisOriginal } = useGallery({
  loadPreview: (tile) => loadSharePreview(ctx.value!, tile),
  loadOriginal: (tile) => loadShareOriginal(ctx.value!, tile),
  onError: (e) => { error.value = e.message },
})

async function resolve() {
  loading.value = true
  error.value = ''
  passwordWrong.value = false
  try {
    const resolved = await resolvePublicShare(session.apiBase, shareId, shareKey, password.value || undefined)
    ctx.value = resolved
    passwordRequired.value = false
    revokeTiles()
    const [loadedTiles, loadedJournal] = await Promise.all([
      loadShareTiles(resolved, resolved.rootNodeId),
      getShareJournal(resolved).catch(() => ''),
    ])
    tiles.value = loadedTiles
    journal.value = loadedJournal
  } catch (e) {
    if (e instanceof SharePasswordRequired) {
      passwordRequired.value = true
      passwordWrong.value = e.wrong
    } else {
      notFound.value = true
      error.value = e instanceof Error ? e.message : String(e)
    }
  } finally {
    loading.value = false
  }
}

// Guest contribution (CONTRIBUTOR/UPLOADER shares): upload sealed media via the share's upload-token capability.
const canContribute = computed(() => ctx.value?.role === 'CONTRIBUTOR' || ctx.value?.role === 'UPLOADER')
const uploadOnly = computed(() => ctx.value?.role === 'UPLOADER')
const contributorName = ref('')
const uploading = ref(false)
const uploadDone = ref(0)
const uploadTotal = ref(0)
let contributorId = ''

async function onContribute(event: Event) {
  if (uploading.value || !ctx.value) return
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  uploading.value = true
  error.value = ''
  uploadDone.value = 0
  uploadTotal.value = files.length
  const name = contributorName.value.trim()
  if (name && !contributorId) contributorId = crypto.randomUUID()
  const contributor = name ? { id: contributorId, name } : undefined
  try {
    for (const file of files) {
      const media = await fileToNewMedia(file)
      await uploadShareMedia(ctx.value, media, contributor)
      uploadDone.value += 1
    }
    if (!uploadOnly.value) tiles.value = await loadShareTiles(ctx.value, ctx.value.rootNodeId)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    uploading.value = false
  }
}

onMounted(resolve)
</script>

<template>
  <div class="share-view">
    <div v-if="loading" class="veyq-loading"><ProgressSpinner class="small-spinner" /></div>

    <!-- Password gate -->
    <div v-else-if="passwordRequired" class="share-gate">
      <h1 class="auth-title">{{ t('share.passwordTitle') }}</h1>
      <form class="veyq-form" @submit.prevent="resolve">
        <InputText v-model="password" type="password" autocomplete="off" autofocus />
        <Message v-if="passwordWrong" severity="error" :closable="false">{{ t('share.passwordWrong') }}</Message>
        <Button type="submit" :label="t('share.passwordSubmit')" icon="pi pi-unlock" />
      </form>
    </div>

    <div v-else-if="notFound" class="share-gate">
      <Message severity="error" :closable="false">{{ t('share.notFound') }}</Message>
    </div>

    <template v-else-if="ctx">
      <header class="share-header">
        <p class="auth-wordmark">veyq</p>
        <h1 class="share-title">{{ ctx.rootName || t('share.title') }}</h1>
        <span v-if="!canContribute" class="share-readonly">{{ t('share.readOnly') }}</span>
      </header>

      <div v-if="canContribute" class="contribute-bar">
        <input v-model="contributorName" class="contributor-name" :placeholder="t('share.yourName')" />
        <label class="upload-trigger">
          <span class="p-button p-component"><span class="pi pi-upload" /><span class="p-button-label">{{ uploading ? t('detail.uploading') : t('share.addPhotos') }}</span></span>
          <input type="file" accept="image/*,video/*" multiple class="upload-input" :disabled="uploading" @change="onContribute" />
        </label>
        <span v-if="uploading" class="upload-count">{{ uploadDone }} / {{ uploadTotal }}</span>
      </div>
      <p v-if="uploadOnly" class="empty-hint">{{ t('share.uploadOnlyHint') }}</p>

      <template v-if="!uploadOnly">
      <div class="memory-tabs">
        <button type="button" class="memory-tab" :class="{ active: tab === 'gallery' }" @click="tab = 'gallery'">{{ t('detail.gallery') }}</button>
        <button type="button" class="memory-tab" :class="{ active: tab === 'journal' }" @click="tab = 'journal'">{{ t('detail.journal') }}</button>
      </div>

      <div v-show="tab === 'gallery'" class="gallery-grid">
        <button v-for="tile in tiles" :key="tile.id" type="button" class="gallery-tile" @click="openLightbox(tile)">
          <img :src="tile.url" :alt="tile.id" loading="lazy" />
        </button>
        <p v-if="!tiles.length" class="empty-hint">{{ t('detail.noMedia') }}</p>
      </div>

      <div v-show="tab === 'journal'" class="journal-body">
        <!-- renderMarkdown HTML-escapes input first (safe subset); no raw HTML passes through. -->
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div v-if="journal" v-html="renderMarkdown(journal)"></div>
        <p v-else class="empty-hint">{{ t('journal.empty') }}</p>
      </div>
      </template>

      <div v-if="lightbox" class="lightbox" @click.self="closeLightbox()">
        <img :src="lightbox" alt="" class="lightbox-img" />
        <div class="lightbox-actions">
          <Button type="button" icon="pi pi-download" :label="t('detail.download')" :loading="downloading" @click="downloadThisOriginal()" />
          <Button type="button" icon="pi pi-times" :label="t('common.close')" severity="secondary" @click="closeLightbox()" />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.share-view { max-width: 1100px; margin: 0 auto; padding: 1.5rem 1rem; }
.share-gate { max-width: 360px; margin: 4rem auto; text-align: center; }
.share-header { display: flex; align-items: baseline; gap: 0.75rem; flex-wrap: wrap; margin-bottom: 1rem; }
.share-title { margin: 0; }
.share-readonly { opacity: 0.6; font-size: 0.85rem; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.3)); padding: 0.1rem 0.5rem; border-radius: 999px; }
.contribute-bar { display: flex; align-items: center; gap: 0.6rem; margin-bottom: 1rem; flex-wrap: wrap; }
.contributor-name { padding: 0.45rem 0.6rem; border-radius: 8px; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.3)); background: var(--p-content-background, transparent); color: inherit; }
.upload-trigger { cursor: pointer; }
.upload-input { display: none; }
.upload-count { opacity: 0.7; }
.memory-tabs { display: flex; gap: 0.25rem; border-bottom: 1px solid var(--p-content-border-color, rgba(127,127,127,0.2)); margin-bottom: 1rem; }
.memory-tab { background: none; border: none; border-bottom: 2px solid transparent; padding: 0.5rem 0.85rem; cursor: pointer; color: inherit; opacity: 0.7; }
.memory-tab.active { opacity: 1; border-bottom-color: var(--p-primary-color, #6366f1); font-weight: 600; }
.gallery-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 6px; }
.gallery-tile { padding: 0; border: none; background: none; cursor: pointer; aspect-ratio: 1; overflow: hidden; border-radius: 6px; }
.gallery-tile img { width: 100%; height: 100%; object-fit: cover; display: block; }
.journal-body { line-height: 1.6; }
.empty-hint { opacity: 0.6; }
.lightbox { position: fixed; inset: 0; z-index: 1000; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 1rem; background: rgba(0,0,0,0.85); padding: 2rem; }
.lightbox-img { max-width: 92vw; max-height: 80vh; object-fit: contain; border-radius: 8px; }
.lightbox-actions { display: flex; gap: 0.5rem; }
</style>
