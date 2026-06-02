<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import FileUpload, { type FileUploadSelectEvent } from 'primevue/fileupload'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import TieredMenu from 'primevue/tieredmenu'
import CreateMemoryDialog from '../features/memories/components/CreateMemoryDialog.vue'
import MemoryDetail from '../features/memories/components/MemoryDetail.vue'
import UploadStatusMenu from '../features/memories/components/UploadStatusMenu.vue'
import { startUploadBatch, useUploadQueue } from '../features/memories/composables/useUploadQueue'
import PrivacySettingsPanel from '../features/settings/components/PrivacySettingsPanel.vue'
import { setLocale, SUPPORTED_LOCALES, type SupportedLocale } from '../i18n'
import { API_BASE } from '../shared/api/httpClient'
import { useThemeState, type ThemePreference } from '../shared/state/useThemeState'
import { getErrorMessage } from '../shared/utils/errors'
import { memoryTabFromHash, memoryTabToHash, type MemoryDetailTab } from '../features/memories/utils/memoryTabs'
import {
  createPublicShareChildMemory,
  createPublicShareMemoryJournalLiveSession,
  createPublicShareMemoryMediaBucket,
  createPublicShareMemoryPlaylist,
  deletePublicShareMedia,
  deletePublicShareMemoryMediaBatch,
  deletePublicShareMemoryMediaBucket,
  deletePublicShareMemory,
  deletePublicShareMemoryPlaylist,
  getPublicShare,
  getPublicShareMemoryHeader,
  getPublicShareMemoryJournal,
  getPublicShareMemoryByPath,
  listPublicShareChildMemories,
  listPublicShareMemoryMedia,
  listPublicShareMemoryMediaBuckets,
  listPublicShareMemoryPlaylists,
  listPublicShareMemoryTracks,
  loginPublicShare,
  logoutPublicShare,
  removePublicShareMemoryCover,
  movePublicShareMemoryMedia,
  setPublicShareMemoryCover,
  updatePublicShareMemoryMediaBucket,
  updatePublicShareMemory,
  updatePublicShareMemoryJournal,
  updatePublicShareMemoryPlaylist,
  updatePublicShareMemoryTrack,
  uploadPublicShareMedia,
  uploadPublicShareMemoryMedia,
  uploadPublicShareMemoryTrack,
  deletePublicShareMemoryTrack,
  updatePublicShareMediaCapturedAt,
  updatePublicShareMediaHighlight,
  waitForNewPublicShareMemoryMedia,
  type Memory,
  type MemoryHeader,
  type MemoryJournal,
  type MemoryMedia,
  type MemoryMediaBucket,
  type MemoryPlaylist,
  type MemoryTrack,
  type PublicShareNotice,
  type PublicShareSummary
} from '../features/memories/api'

type MediaUpdatedCursor = {
  updatedAt: string
  mediaId: string
}

const route = useRoute()
const router = useRouter()
const { locale, t } = useI18n()

const shareId = computed(() => String(route.params.shareId || ''))
const sharePath = computed(() => routePathToSharePath(route.params.pathMatch))
const activeMemoryTab = computed(() => memoryTabFromHash(route.hash))
const share = ref<PublicShareSummary | null>(null)
const memory = ref<Memory | null>(null)
const memoryHeader = ref<MemoryHeader | null>(null)
const memoryJournal = ref<MemoryJournal | null>(null)
const childMemories = ref<Memory[]>([])
const media = ref<MemoryMedia[]>([])
const mediaBuckets = ref<MemoryMediaBucket[]>([])
const mediaLoading = ref(false)
const playlists = ref<MemoryPlaylist[]>([])
const tracks = ref<MemoryTrack[]>([])
const password = ref('')
const displayName = ref('')
const selectedFiles = ref<File[]>([])
const uploadPicker = ref<{ clear: () => void } | null>(null)
const languageMenu = ref<{ toggle: (event: Event) => void } | null>(null)
const themeMenu = ref<{ toggle: (event: Event) => void } | null>(null)
const createMemoryOpen = ref(false)
const privacyDialogOpen = ref(false)
const error = ref('')
const message = ref('')
const loading = ref(false)
const acceptanceRevision = ref(0)
const { visibleBatches } = useUploadQueue()
const { themePreference, setThemePreference } = useThemeState()
const markdownRenderer = new MarkdownIt({ html: false, linkify: true, typographer: true })
let memoryLoadSequence = 0
let mediaUpdatesController: AbortController | null = null

const isUploaderOnly = computed(() => share.value?.role === 'UPLOADER')
const hasShareAccess = computed(() => Boolean(share.value?.authenticated || share.value?.canViewGallery))
const canShowLogin = computed(() =>
  Boolean(
    noticesAccepted.value &&
      share.value &&
      !share.value.authenticated &&
      (share.value.passwordRequired || share.value.displayNameRequired)
  )
)
const canSubmitLogin = computed(() =>
  Boolean(
    share.value &&
      (!share.value.passwordRequired || password.value.trim()) &&
      (!share.value.displayNameRequired || displayName.value.trim())
  )
)
const activeNotices = computed(() => (share.value?.notices || []).map(renderNotice).filter(Boolean) as RenderedNotice[])
const languageMenuItems = computed(() =>
  SUPPORTED_LOCALES.map((nextLocale) => ({
    label: t(`navigation.locales.${nextLocale}`),
    icon: locale.value === nextLocale ? 'pi pi-check' : 'pi pi-circle',
    command: () => setLocale(nextLocale as SupportedLocale)
  }))
)
const themeMenuItems = computed(() =>
  (['system', 'light', 'dark'] as ThemePreference[]).map((preference) => ({
    label: t(`navigation.themes.${preference}`),
    icon: themePreference.value === preference ? 'pi pi-check' : 'pi pi-circle',
    command: () => setThemePreference(preference)
  }))
)
const pendingNotices = computed(() => {
  const revision = acceptanceRevision.value
  if (revision < 0) return []
  return activeNotices.value.filter((notice) => !isNoticeAccepted(notice))
})
const noticesAccepted = computed(() => pendingNotices.value.length === 0)
const canShowNoticeGate = computed(() => Boolean(share.value && !noticesAccepted.value))
const canShowMemoryDetail = computed(() =>
  Boolean(noticesAccepted.value && hasShareAccess.value && memory.value && !isUploaderOnly.value)
)
const publicShareUploadBatches = computed(() =>
  visibleBatches.value.filter(
    (batch) => batch.context.type === 'public-share-root' && batch.context.shareId === shareId.value
  )
)
const shareCoverUrl = computed(() =>
  memoryHeader.value?.coverUrl ? `${API_BASE}${memoryHeader.value.coverUrl}` : ''
)
const sharePermissions = computed(() => share.value?.permissions)
const capabilities = computed(() => ({
  canEdit: Boolean(sharePermissions.value?.editMemory ?? share.value?.canEdit),
  canUpload: Boolean(share.value?.canUpload),
  canCreateChildMemory: Boolean(sharePermissions.value?.addChapter ?? share.value?.canEdit),
  canDelete: Boolean(share.value?.canDelete),
  canShare: false,
  canManageCover: Boolean(sharePermissions.value?.setCover ?? share.value?.canDelete),
  canEditJournal: Boolean(sharePermissions.value?.editJournal ?? share.value?.canEdit),
  canEditMediaMetadata: Boolean(share.value?.canUpload || share.value?.canEdit),
  canDeleteMedia: Boolean(share.value?.canUpload || share.value?.canDelete),
  canHighlightMedia: Boolean(sharePermissions.value?.setHighlight ?? (share.value?.canUpload || share.value?.canDelete)),
  canEditTracks: Boolean(sharePermissions.value?.uploadTrack ?? share.value?.canEdit),
  canEditPlaylists: Boolean(sharePermissions.value?.uploadPlaylist ?? share.value?.canEdit),
  canDeleteCurrentMemory: Boolean(share.value?.canDelete && memory.value?.id !== share.value.rootMemory?.id),
  showScope: false,
  canDownload: Boolean(share.value?.canViewGallery && !isUploaderOnly.value),
  canFavorite: false,
  canManageParticipants: false,
  showInfo: false,
}))

onMounted(loadShare)
onBeforeUnmount(stopMemoryMediaUpdates)

watch(
  () => [shareId.value, sharePath.value],
  () => {
    if (hasShareAccess.value && !isUploaderOnly.value) void loadCurrentMemory()
  }
)

async function loadShare() {
  error.value = ''
  loading.value = true
  try {
    share.value = await getPublicShare(shareId.value)
    if (share.value.redirectUrl) {
      await router.replace(share.value.redirectUrl)
      return
    }
    if (hasShareAccess.value && noticesAccepted.value) await loadProtectedContent()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('share.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function login() {
  if (!canSubmitLogin.value) return
  error.value = ''
  message.value = ''
  loading.value = true
  try {
    share.value = await loginPublicShare(
      shareId.value,
      share.value?.passwordRequired ? password.value : null,
      share.value?.displayNameRequired ? displayName.value.trim() : null
    )
    password.value = ''
    if (share.value.redirectUrl) {
      await router.replace(share.value.redirectUrl)
      return
    }
    if (noticesAccepted.value) await loadProtectedContent()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('share.loginFailed'))
  } finally {
    loading.value = false
  }
}

async function closeShare() {
  memoryLoadSequence += 1
  stopMemoryMediaUpdates()
  await logoutPublicShare(shareId.value)
  message.value = ''
  memory.value = null
  memoryHeader.value = null
  memoryJournal.value = null
  childMemories.value = []
  media.value = []
  mediaBuckets.value = []
  mediaLoading.value = false
  playlists.value = []
  tracks.value = []
  await loadShare()
}

async function loadProtectedContent() {
  if (!noticesAccepted.value) return
  if (isUploaderOnly.value) return
  await loadCurrentMemory()
}

async function acceptNotices() {
  pendingNotices.value.forEach((notice) => window.localStorage.setItem(notice.storageKey, 'true'))
  acceptanceRevision.value += 1
  if (hasShareAccess.value) await loadProtectedContent()
}

async function loadCurrentMemory() {
  const sequence = ++memoryLoadSequence
  stopMemoryMediaUpdates()
  error.value = ''
  message.value = ''
  loading.value = true
  memory.value = null
  memoryHeader.value = null
  memoryJournal.value = null
  childMemories.value = []
  media.value = []
  mediaLoading.value = false
  playlists.value = []
  tracks.value = []
  try {
    const current = await getPublicShareMemoryByPath(shareId.value, sharePath.value)
    if (sequence !== memoryLoadSequence) return
    memory.value = current
    mediaLoading.value = true
    void loadCurrentMemoryDetails(sequence, current)
  } catch (unknownError) {
    if (sequence === memoryLoadSequence) error.value = getErrorMessage(unknownError, t('memory.loadFailed'))
  } finally {
    if (sequence === memoryLoadSequence) loading.value = false
  }
}

async function loadCurrentMemoryDetails(sequence: number, current: Memory) {
  await Promise.all([
    loadCurrentMemoryDetailPart(sequence, () => getPublicShareMemoryHeader(shareId.value, current.id), (header) => {
      memoryHeader.value = header
    }),
    loadCurrentMemoryDetailPart(sequence, () => getPublicShareMemoryJournal(shareId.value, current.id), (journal) => {
      memoryJournal.value = journal
    }),
    loadCurrentMemoryDetailPart(sequence, () => listPublicShareChildMemories(shareId.value, current.id), (children) => {
      childMemories.value = children
    }),
    loadCurrentMemoryDetailPart(sequence, () => listPublicShareMemoryMedia(shareId.value, current.id, true), (memoryMedia) => {
      media.value = memoryMedia
      mediaLoading.value = false
      startMemoryMediaUpdates(sequence, current)
    }, () => {
      mediaLoading.value = false
    }),
    loadCurrentMemoryDetailPart(sequence, () => listPublicShareMemoryMediaBuckets(shareId.value, current.id, true), (buckets) => {
      mediaBuckets.value = buckets
    }),
    loadCurrentMemoryDetailPart(sequence, () => listPublicShareMemoryTracks(shareId.value, current.id), (memoryTracks) => {
      tracks.value = memoryTracks
    }),
    loadCurrentMemoryDetailPart(sequence, () => listPublicShareMemoryPlaylists(shareId.value, current.id), (memoryPlaylists) => {
      playlists.value = memoryPlaylists
    })
  ])
}

async function loadCurrentMemoryDetailPart<T>(
  sequence: number,
  loadPart: () => Promise<T>,
  applyPart: (value: T) => void,
  finalize?: () => void
) {
  try {
    const value = await loadPart()
    if (sequence === memoryLoadSequence) applyPart(value)
  } catch (unknownError) {
    if (sequence === memoryLoadSequence) error.value = getErrorMessage(unknownError, t('memory.loadFailed'))
  } finally {
    if (sequence === memoryLoadSequence) finalize?.()
  }
}

function startMemoryMediaUpdates(sequence: number, current: Memory) {
  stopMemoryMediaUpdates()
  const controller = new AbortController()
  mediaUpdatesController = controller
  void watchMemoryMediaUpdates(sequence, current, controller)
}

function stopMemoryMediaUpdates() {
  mediaUpdatesController?.abort()
  mediaUpdatesController = null
}

async function watchMemoryMediaUpdates(
  sequence: number,
  current: Memory,
  controller: AbortController
) {
  while (!controller.signal.aborted && sequence === memoryLoadSequence && memory.value?.id === current.id) {
    const cursor = newestUpdatedAtCursor(media.value)
    try {
      const updates = await waitForNewPublicShareMemoryMedia(
        shareId.value,
        current.id,
        true,
        cursor?.updatedAt,
        cursor?.mediaId,
        controller.signal
      )
      if (controller.signal.aborted || sequence !== memoryLoadSequence || memory.value?.id !== current.id) return
      if (updates.media.length > 0) {
        media.value = chronologicallySortedMedia(mergeMediaById(media.value, updates.media))
        const [header, buckets] = await Promise.all([
          getPublicShareMemoryHeader(shareId.value, current.id),
          listPublicShareMemoryMediaBuckets(shareId.value, current.id, true)
        ])
        if (controller.signal.aborted || sequence !== memoryLoadSequence || memory.value?.id !== current.id) return
        memoryHeader.value = header
        mediaBuckets.value = buckets
      }
    } catch (unknownError) {
      if (controller.signal.aborted) return
      if (sequence === memoryLoadSequence) {
        error.value = getErrorMessage(unknownError, t('memory.loadFailed'))
      }
      await delay(5_000, controller.signal)
    }
  }
}

function openMemory(nextMemory: Memory) {
  void router.push(publicShareRoute(nextMemory.path))
}

function updateActiveMemoryTab(tab: MemoryDetailTab) {
  void router.replace({ path: route.path, query: route.query, hash: memoryTabToHash(tab) })
}

function toggleLanguageMenu(event: Event) {
  languageMenu.value?.toggle(event)
}

function toggleThemeMenu(event: Event) {
  themeMenu.value?.toggle(event)
}

function handleMemoryChanged(updated: Memory) {
  if (updated.path !== sharePath.value) {
    void router.replace(publicShareRoute(updated.path))
    return
  }
  void loadCurrentMemory()
}

function handleMemoryDeleted(parentPath: string) {
  void router.replace(publicShareRoute(parentPath))
}

function selectUpload(event: FileUploadSelectEvent) {
  if (loading.value) return
  selectedFiles.value = event.files
}

function selectUploadFallback(event: Event) {
  if (loading.value) return
  selectedFiles.value = Array.from((event.target as HTMLInputElement).files || [])
}

async function uploadToRoot() {
  if (!selectedFiles.value.length) return
  error.value = ''
  message.value = ''
  loading.value = true
  try {
    const uploadCount = selectedFiles.value.length
    startUploadBatch({
      label: t('media.uploadBatchTitle', { count: uploadCount }),
      files: [...selectedFiles.value],
      context: { type: 'public-share-root', shareId: shareId.value },
      uploadFile: (file, options) =>
        uploadPublicShareMedia(
          shareId.value,
          {
            file,
            title: file.name
          },
          options
        ),
      onComplete: async () => {
        message.value = t('share.uploadComplete', { count: uploadCount })
      }
    })
    selectedFiles.value = []
    uploadPicker.value?.clear()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('share.uploadFailed'))
  } finally {
    loading.value = false
  }
}

function publicShareRoute(path: string) {
  const normalized = path === '/' ? '' : path.replace(/^\/+/, '/')
  return `/s/${shareId.value}${normalized}`
}

function routePathToSharePath(value: unknown): string {
  if (Array.isArray(value)) return value.length ? `/${value.join('/')}` : '/'
  if (typeof value === 'string' && value) return `/${value}`
  return '/'
}

function publicShareDownloadUrl(memoryId: string, includeChildren: boolean): string {
  return `${API_BASE}/api/public-shares/${encodeURIComponent(shareId.value)}/memories/${encodeURIComponent(memoryId)}/download?includeChildren=${includeChildren}&locale=${encodeURIComponent(locale.value)}`
}

type RenderedNotice = {
  notice: PublicShareNotice
  locale: string
  html: string
  storageKey: string
}

function renderNotice(notice: PublicShareNotice): RenderedNotice | null {
  const resolved = resolveNoticeMarkdown(notice.markdownByLocale)
  if (!resolved) return null
  return {
    notice,
    locale: resolved.locale,
    html: DOMPurify.sanitize(markdownRenderer.render(resolved.markdown)),
    storageKey: noticeAcceptanceKey(notice, resolved.locale)
  }
}

function resolveNoticeMarkdown(markdownByLocale: Record<string, string>): { locale: string; markdown: string } | null {
  const entries = Object.entries(markdownByLocale || {}).filter(([, markdown]) => markdown.trim())
  if (!entries.length) return null
  const requested = locale.value.toLowerCase()
  const base = requested.split('-')[0]
  const match =
    entries.find(([entryLocale]) => entryLocale.toLowerCase() === requested) ||
    entries.find(([entryLocale]) => entryLocale.toLowerCase() === base) ||
    entries.find(([entryLocale]) => entryLocale.toLowerCase() === 'en') ||
    entries[0]
  return { locale: match[0].toLowerCase(), markdown: match[1] }
}

function noticeAcceptanceKey(notice: PublicShareNotice, renderedLocale: string): string {
  if (notice.scope === 'TENANT') {
    const tenantKey = notice.tenantDomain || notice.tenantName
    return `veyq:tenant-terms:${window.location.origin}:${tenantKey}:${notice.version}:${renderedLocale}`
  }
  return `veyq:share-notice:${shareId.value}:${notice.version}:${renderedLocale}`
}

function isNoticeAccepted(notice: RenderedNotice): boolean {
  return window.localStorage.getItem(notice.storageKey) === 'true'
}

function newestUpdatedAtCursor(media: MemoryMedia[]): MediaUpdatedCursor | null {
  const newest = [...media]
    .filter((item) => item.updatedAt)
    .sort((left, right) => {
      const updatedAtOrder = left.updatedAt.localeCompare(right.updatedAt)
      return updatedAtOrder || left.id.localeCompare(right.id)
    })
    .at(-1)
  return newest ? { updatedAt: newest.updatedAt, mediaId: newest.id } : null
}

function mergeMediaById(current: MemoryMedia[], updates: MemoryMedia[]): MemoryMedia[] {
  const byId = new Map<string, MemoryMedia>()
  current.forEach((item) => byId.set(item.id, item))
  updates.forEach((item) => byId.set(item.id, item))
  return [...byId.values()]
}

function chronologicallySortedMedia(media: MemoryMedia[]): MemoryMedia[] {
  return [...media].sort((left, right) => {
    const leftSortTime = left.capturedAt || left.createdAt
    const rightSortTime = right.capturedAt || right.createdAt
    const sortTimeOrder = leftSortTime.localeCompare(rightSortTime)
    if (sortTimeOrder !== 0) return sortTimeOrder
    const createdAtOrder = left.createdAt.localeCompare(right.createdAt)
    return createdAtOrder || left.id.localeCompare(right.id)
  })
}

function delay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timeout = window.setTimeout(resolve, milliseconds)
    signal.addEventListener('abort', () => {
      window.clearTimeout(timeout)
      resolve()
    }, { once: true })
  })
}
</script>

<template>
  <section class="veyq-page public-share-view">
    <header
      v-if="canShowLogin"
      class="public-share-header"
      :class="{ 'public-share-header-with-cover': Boolean(shareCoverUrl) }"
    >
      <img v-if="shareCoverUrl" class="public-share-header-cover" :src="shareCoverUrl" alt="" />
      <div>
        <p>{{ t('share.intro') }}</p>
        <h1>{{ memory?.displayName || share?.rootMemory?.displayName || t('share.intro') }}</h1>
      </div>
    </header>

    <div v-if="share" class="public-share-actions">
      <div class="public-share-preference-actions">
        <Button
          type="button"
          icon="pi pi-language"
          severity="secondary"
          outlined
          :aria-label="t('navigation.language')"
          :title="t('navigation.language')"
          @click="toggleLanguageMenu"
        />
        <TieredMenu ref="languageMenu" :model="languageMenuItems" popup />
        <Button
          type="button"
          icon="pi pi-palette"
          severity="secondary"
          outlined
          :aria-label="t('navigation.theme')"
          :title="t('navigation.theme')"
          @click="toggleThemeMenu"
        />
        <TieredMenu ref="themeMenu" :model="themeMenuItems" popup />
      </div>
      <Button
        v-if="share.authenticated"
        :label="t('common.close')"
        icon="pi pi-sign-out"
        severity="secondary"
        outlined
        @click="closeShare"
      />
    </div>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>

    <section v-if="canShowNoticeGate" class="veyq-surface public-share-notice-gate">
      <div class="public-share-login-copy">
        <h2>{{ t('share.noticeAcceptTitle') }}</h2>
        <p class="public-share-copy">{{ t('share.noticeAcceptCopy') }}</p>
      </div>
      <article
        v-for="notice in pendingNotices"
        :key="notice.storageKey"
        class="veyq-surface public-share-notice"
      >
        <h3>
          {{ notice.notice.scope === 'TENANT' ? t('share.noticeTenantTitle') : t('share.noticeShareTitle') }}
        </h3>
        <!-- eslint-disable-next-line vue/no-v-html -- rendered Markdown is sanitized with DOMPurify in renderNotice(). -->
        <div class="markdown-body" v-html="notice.html" />
      </article>
      <Button
        type="button"
        :label="t('share.noticeAccept')"
        icon="pi pi-check"
        @click="acceptNotices"
      />
    </section>

    <form v-else-if="canShowLogin" class="veyq-surface public-share-login" @submit.prevent="login">
      <div class="public-share-login-copy">
        <h2>{{ t('share.loginTitle') }}</h2>
        <p class="public-share-copy">{{ t('share.loginCopy') }}</p>
      </div>
      <InputText
        v-if="share?.passwordRequired"
        v-model="password"
        type="password"
        autocomplete="current-password"
        :placeholder="t('common.password')"
      />
      <InputText
        v-if="share?.displayNameRequired"
        v-model="displayName"
        autocomplete="name"
        :placeholder="t('share.displayNamePlaceholder')"
      />
      <Button
        :label="t('share.open')"
        icon="pi pi-lock-open"
        type="submit"
        :loading="loading"
        :disabled="!canSubmitLogin"
      />
    </form>

    <div v-else-if="loading && !memory" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('share.loading') }}</span>
    </div>

    <form
      v-else-if="noticesAccepted && share?.authenticated && isUploaderOnly"
      class="veyq-surface public-share-upload"
      @submit.prevent="uploadToRoot"
    >
      <div class="public-share-upload-copy">
        <h2>{{ t('share.uploadTitle') }}</h2>
        <p>{{ t('share.uploadCopy') }}</p>
        <p class="public-share-upload-note">{{ t('share.uploaderOnlyNotice') }}</p>
      </div>
      <div class="memory-upload-picker">
        <FileUpload
          ref="uploadPicker"
          mode="basic"
          accept="image/*,video/*"
          :choose-label="t('media.chooseFiles')"
          custom-upload
          multiple
          :disabled="loading"
          @select="selectUpload"
        >
          <template #filelabel />
        </FileUpload>
        <input
          class="memory-upload-native-fallback"
          type="file"
          multiple
          accept="image/*,video/*"
          :disabled="loading"
          @change="selectUploadFallback"
        />
        <span>{{ selectedFiles.length ? t('media.selectedCount', { count: selectedFiles.length }) : t('share.uploadEmpty') }}</span>
      </div>
      <ul v-if="selectedFiles.length" class="memory-upload-file-list">
        <li v-for="file in selectedFiles" :key="`${file.name}-${file.size}`">
          <span class="pi pi-file" />
          <span>{{ file.name }}</span>
        </li>
      </ul>
      <UploadStatusMenu v-if="publicShareUploadBatches.length" inline :batches="publicShareUploadBatches" />
      <Button
        :label="t('common.upload')"
        icon="pi pi-upload"
        type="submit"
        :loading="loading"
        :disabled="!selectedFiles.length || loading"
      />
    </form>

    <MemoryDetail
      v-else-if="canShowMemoryDetail && memory"
      tenant-name=""
      :memory="memory"
      :header="memoryHeader"
      :journal="memoryJournal"
      :child-memories="childMemories"
      :media="media"
      :media-buckets="mediaBuckets"
      :show-local-primary-bucket="false"
      :media-loading="mediaLoading"
      :playlists="playlists"
      :tracks="tracks"
      :active-tab="activeMemoryTab"
      :capabilities="capabilities"
      :save-memory="(memoryId, request) => updatePublicShareMemory(shareId, memoryId, request)"
      :save-journal="(memoryId, request) => updatePublicShareMemoryJournal(shareId, memoryId, request)"
      :create-journal-live-session="(memoryId) => createPublicShareMemoryJournalLiveSession(shareId, memoryId)"
      :set-cover="(memoryId, request) => setPublicShareMemoryCover(shareId, memoryId, request)"
      :remove-cover="(memoryId) => removePublicShareMemoryCover(shareId, memoryId)"
      :upload-media="(memoryId, request, options) => uploadPublicShareMemoryMedia(shareId, memoryId, request, options)"
      :delete-memory="(memoryId) => deletePublicShareMemory(shareId, memoryId)"
      :update-media-captured-at="(mediaId, memoryId, request) => updatePublicShareMediaCapturedAt(shareId, memoryId, mediaId, request)"
      :update-media-highlight="(mediaId, memoryId, request) => updatePublicShareMediaHighlight(shareId, memoryId, mediaId, request)"
      :delete-media="(mediaId, memoryId) => deletePublicShareMedia(shareId, memoryId, mediaId)"
      :create-media-bucket="(memoryId, request) => createPublicShareMemoryMediaBucket(shareId, memoryId, request)"
      :update-media-bucket="(bucketId, memoryId, request) => updatePublicShareMemoryMediaBucket(shareId, memoryId, bucketId, request)"
      :delete-media-bucket="(bucketId, memoryId) => deletePublicShareMemoryMediaBucket(shareId, memoryId, bucketId)"
      :move-media="(memoryId, request) => movePublicShareMemoryMedia(shareId, memoryId, request)"
      :delete-media-batch="(memoryId, request) => deletePublicShareMemoryMediaBatch(shareId, memoryId, request)"
      :upload-track="(memoryId, request) => uploadPublicShareMemoryTrack(shareId, memoryId, request)"
      :update-track="(trackId, memoryId, request) => updatePublicShareMemoryTrack(shareId, memoryId, trackId, request)"
      :delete-track="(trackId, memoryId) => deletePublicShareMemoryTrack(shareId, memoryId, trackId)"
      :create-playlist="(memoryId, request) => createPublicShareMemoryPlaylist(shareId, memoryId, request)"
      :update-playlist="(playlistId, memoryId, request) => updatePublicShareMemoryPlaylist(shareId, memoryId, playlistId, request)"
      :delete-playlist="(playlistId, memoryId) => deletePublicShareMemoryPlaylist(shareId, memoryId, playlistId)"
      :download-url="publicShareDownloadUrl"
      @update:active-tab="updateActiveMemoryTab"
      @open-memory="openMemory"
      @create-child-memory="createMemoryOpen = true"
      @changed="handleMemoryChanged"
      @deleted="handleMemoryDeleted"
      @media-deleted="loadCurrentMemory"
    />

    <footer class="public-share-footer">
      <span>{{ t('share.providedBy') }}</span>
      <a href="https://github.com/raddatzk/veyq" target="_blank" rel="noreferrer">{{ t('share.projectLink') }}</a>
      <button type="button" @click="privacyDialogOpen = true">{{ t('share.privacyLink') }}</button>
    </footer>

    <Dialog
      v-model:visible="privacyDialogOpen"
      modal
      :header="t('privacy.dialogTitle')"
      class="privacy-settings-dialog"
    >
      <PrivacySettingsPanel />
    </Dialog>

    <CreateMemoryDialog
      v-if="memory"
      v-model:visible="createMemoryOpen"
      tenant-name=""
      :tenant-display-name="t('share.intro')"
      :parent-memory-id="memory.id"
      :create-memory-override="(request) => createPublicShareChildMemory(shareId, memory!.id, request)"
      :set-cover-override="(memoryId, request) => setPublicShareMemoryCover(shareId, memoryId, request)"
      @saved="loadCurrentMemory"
    />
  </section>
</template>
