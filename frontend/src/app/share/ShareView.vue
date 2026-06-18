<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import * as Y from 'yjs'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { getShareJournal, getSharePlaylists, getShareTracks, listShareNodes, loadShareOriginal, loadSharePreview, loadShareTiles, resolvePublicShare, SharePasswordRequired, uploadShareMedia, type ShareContext, type ShareNotice } from '../../features/memory/share/share'
import { loadShareJournalDoc, subscribeShareJournal } from '../../features/memory/journalLive/shareJournalLive'
import { decodeNode, type MemoryNode } from '../../features/memory/createTree/memoryTree'
import { renderMarkdown } from '../../features/memory/journal/markdown'
import { fileToNewMedia } from '../../features/uploads/clientMedia'
import { listMySpaces } from '../../features/spaces/switchActiveSpace/switchActiveSpace'
import MemoryContent from '../home/MemoryContent.vue'

const props = defineProps<{ shareId: string; shareKey: string }>()
const emit = defineEmits<{ internal: [target: { scopeId: string; nodeId: string }] }>()

const ctx = ref<ShareContext | null>(null)
const title = ref('')
const loading = ref(true)
const error = ref('')
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
// The whole shared subtree (decrypted), so MemoryContent can browse chapters + resolve ::chapter embed names.
const allNodes = ref<MemoryNode[]>([])
const contentReady = ref(false)
const content = ref<InstanceType<typeof MemoryContent> | null>(null)
let loadSeq = 0 // generation guard: only the latest load() may apply its result (mirrors HomeView's C3 fix)

const nodeName = (n: MemoryNode) => { try { return decodeNode(ctx.value!.contentKey, n).name } catch { return '🔒' } }
const chapterMap = () => Object.fromEntries(allNodes.value.filter((n) => n.type === 'CHAPTER').map((n) => [n.id, { name: nodeName(n) }]))
const childNodesOf = (nodeId: string) => allNodes.value.filter((n) => n.parentId === nodeId).map((n) => ({ id: n.id, type: n.type, name: nodeName(n) }))

// ---- guest loaders (share capability), keyed by node ----
const c = () => ctx.value!
const loadTiles = (nodeId: string) => loadShareTiles(c(), nodeId)
const loadJournalMarkdown = (nodeId: string) => getShareJournal(c(), nodeId)
const loadTracks = (nodeId: string) => getShareTracks(c(), nodeId)
const loadPlaylists = (nodeId: string) => getSharePlaylists(c(), nodeId)
const loadChildNodes = async (nodeId: string) => childNodesOf(nodeId)
const resolveChapterNames = async () => chapterMap()

/** Operator-blind live read of a node's journal: reconstruct from the store, then stream owner edits. */
function liveJournal(nodeId: string, onUpdate: (markdown: string) => void): () => void {
  const cur = ctx.value!
  const doc = new Y.Doc()
  const ytext = doc.getText('journal')
  let provider: ReturnType<typeof subscribeShareJournal> | null = null
  doc.on('update', () => onUpdate(ytext.toString()))
  void (async () => {
    try { await loadShareJournalDoc(cur.apiBase, cur.shareId, nodeId, cur.contentKey, doc) } catch { /* none written yet */ }
    onUpdate(ytext.toString())
    provider = subscribeShareJournal(cur.apiBase, cur.scopeId, nodeId, cur.shareId, cur.contentKey, doc)
  })()
  return () => { provider?.destroy(); doc.destroy() }
}

onMounted(() => { void boot() })
async function boot() {
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
}

async function load(password?: string) {
  const seq = ++loadSeq
  loading.value = true
  try {
    const resolved = await resolvePublicShare(session.apiBase, props.shareId, props.shareKey, password)
    if (seq !== loadSeq) return
    ctx.value = resolved
    title.value = resolved.rootName || t('share.uploadOnlyTitle')
    needPassword.value = false
    pendingNotices.value = resolved.notices.filter((n) => localStorage.getItem(noticeKey(resolved.shareId, n)) !== 'true')
    if (pendingNotices.value.length) { loading.value = false; return }
    await loadContent()
  } catch (e) {
    if (seq !== loadSeq) return
    if (e instanceof SharePasswordRequired) { needPassword.value = true; passwordWrong.value = e.wrong }
    else error.value = t('share.notFound')
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}
function submitPassword() { load(passwordInput.value) }
async function acceptNotices() {
  const cur = ctx.value
  if (!cur) return
  cur.notices.forEach((n) => localStorage.setItem(noticeKey(cur.shareId, n), 'true'))
  pendingNotices.value = []
  loading.value = true
  await loadContent()
  loading.value = false
}
async function loadContent() {
  const cur = ctx.value
  if (!cur || cur.role === 'UPLOADER') return // an UPLOADER is a blind drop-off — no read
  try { allNodes.value = await listShareNodes(cur) } catch { allNodes.value = [] }
  contentReady.value = true // mount MemoryContent (its loaders read allNodes/ctx)
}
const noticesHtml = computed(() => pendingNotices.value.map((n) => renderMarkdown(n.markdown)).join('<hr class="notice-sep">'))

// A guest names themselves ONCE per share (stored locally + a stable id); all their uploads then group under
// their own named, operator-blind contribution bucket. Declining leaves them in the shared guest bucket.
function shareContributor(shareId: string): { id: string; name: string } | undefined {
  const key = `veyq.share.contributor.${shareId}`
  const stored = localStorage.getItem(key)
  if (stored) return JSON.parse(stored)
  const name = window.prompt(t('share.yourName'))?.trim()
  if (!name) return undefined
  const c = { id: crypto.randomUUID(), name }
  localStorage.setItem(key, JSON.stringify(c))
  return c
}

async function onFilesPicked(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  if (!files.length || !ctx.value) return
  uploading.value = true
  try {
    const contributor = shareContributor(ctx.value.shareId)
    for (const file of files) await uploadShareMedia(ctx.value, await fileToNewMedia(file), contributor)
    await content.value?.reloadTiles()
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}
</script>

<template>
  <div class="share-view">
    <header class="share-head">
      <span class="auth-wordmark">veyq</span>
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
        <input ref="fileInput" type="file" accept="image/*,video/*" multiple hidden @change="onFilesPicked" />
      </div>

      <div v-if="loading && !ctx && !needPassword" class="home-empty">{{ t('detail.loadingGallery') }}</div>

      <MemoryContent
        v-if="ctx && contentReady && !pendingNotices.length && canRead"
        ref="content"
        :root-node-id="ctx.rootNodeId"
        :root-name="title"
        :load-tiles="loadTiles"
        :load-journal-markdown="loadJournalMarkdown"
        :live-journal="liveJournal"
        :load-tracks="loadTracks"
        :load-playlists="loadPlaylists"
        :load-child-nodes="loadChildNodes"
        :resolve-chapter-names="resolveChapterNames"
        :load-preview="(tile) => loadSharePreview(c(), tile)"
        :load-original="(tile) => loadShareOriginal(c(), tile)"
        @error="error = $event"
      >
        <template v-if="canUpload" #head-actions="{ tab }">
          <template v-if="tab === 'gallery'">
            <button class="btn btn-primary" type="button" :disabled="uploading" @click="fileInput?.click()">
              {{ uploading ? t('detail.uploading') : t('share.addPhotos') }}
            </button>
            <input ref="fileInput" type="file" accept="image/*,video/*" multiple hidden @change="onFilesPicked" />
          </template>
        </template>
      </MemoryContent>
    </main>
  </div>
</template>
