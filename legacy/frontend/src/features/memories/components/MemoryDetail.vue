<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Menu from 'primevue/menu'
import Message from 'primevue/message'
import Tab from 'primevue/tab'
import TabList from 'primevue/tablist'
import TabPanel from 'primevue/tabpanel'
import TabPanels from 'primevue/tabpanels'
import Tabs from 'primevue/tabs'
import type { MenuItem } from 'primevue/menuitem'
import MediaGrid from '../../../components/media/MediaGrid.vue'
import { API_BASE } from '../../../shared/api/httpClient'
import {
  createMemoryMediaBucket as createTenantMemoryMediaBucket,
  deleteMemoryMediaBatch as deleteTenantMemoryMediaBatch,
  deleteMemoryMediaBucket as deleteTenantMemoryMediaBucket,
  deleteMemoryMedia as deleteTenantMemoryMedia,
  favoriteMemory,
  moveMemoryMedia as moveTenantMemoryMedia,
  unfavoriteMemory,
  updateMemoryMediaBucket as updateTenantMemoryMediaBucket,
  updateMemoryMediaCapturedAt,
  updateMemoryMediaMetadataBatch as updateTenantMediaMetadataBatch,
  uploadMemoryTrack as uploadTenantMemoryTrack,
  updateMemoryTrack as updateTenantMemoryTrack,
  deleteMemoryTrack as deleteTenantMemoryTrack,
  createMemoryPlaylist as createTenantMemoryPlaylist,
  updateMemoryPlaylist as updateTenantMemoryPlaylist,
  deleteMemoryPlaylist as deleteTenantMemoryPlaylist,
  SELF_PRIMARY_BUCKET_PLACEHOLDER_ID,
  updateMemoryMediaHighlight,
  UpdateMemoryMediaCapturedAtRequest,
  UpdateMemoryMediaHighlightRequest
} from '../api'
import { notifyMemoryFavoritesChanged } from '../composables/useMemoryFavoritesRefresh'
import type {
  Memory,
  MemoryHeader,
  MemoryJournal,
  MemoryJournalLiveSession,
  MemoryMedia,
  MemoryMediaBucket,
  MemoryPlaylist,
  MemoryTrack,
  CreateMemoryPlaylistRequest,
  SetMemoryCoverRequest,
  CreateMemoryMediaBucketRequest,
  DeleteMemoryMediaBatchRequest,
  MoveMemoryMediaRequest,
  UpdateMemoryMediaMetadataBatchRequest,
  UpdateMemoryMediaBucketRequest,
  UpdateMemoryJournalRequest,
  UpdateMemoryPlaylistRequest,
  UpdateMemoryTrackRequest,
  UpdateMemoryRequest,
  UploadMediaOptions,
  UploadMemoryMediaRequest,
  UploadMemoryTrackRequest
} from '../api'
import DeleteMemoryDialog from './DeleteMemoryDialog.vue'
import EditMemoryDialog from './EditMemoryDialog.vue'
import JournalEmbedPickerDialog from './JournalEmbedPickerDialog.vue'
import JournalMediaPickerDialog from './JournalMediaPickerDialog.vue'
import JournalRenderer from './JournalRenderer.vue'
import MemoryJournalLiveEditor from './MemoryJournalLiveEditor.vue'
import MemoryAssetBuckets from './MemoryAssetBuckets.vue'
import MemoryCard from './MemoryCard.vue'
import MemoryCoverImage from './MemoryCoverImage.vue'
import MemoryCoverDialog from './MemoryCoverDialog.vue'
import MemoryHeaderPlaylistPlayer from './MemoryHeaderPlaylistPlayer.vue'
import MemoryParticipantsDialog from './MemoryParticipantsDialog.vue'
import MemoryParticipantsStrip from './MemoryParticipantsStrip.vue'
import MemoryPlaylistsPanel from './MemoryPlaylistsPanel.vue'
import MemoryShareDialog from './MemoryShareDialog.vue'
import MemoryUploaderShareDialog from './MemoryUploaderShareDialog.vue'
import MemoryUploadDialog from './MemoryUploadDialog.vue'
import { DEFAULT_MEMORY_DETAIL_TAB, type MemoryDetailTab } from '../utils/memoryTabs'
import { useMemoryDetailMediaState } from '../composables/useMemoryDetailMediaState'
import { mediaFilesFromList, startMemoryMediaUpload } from '../composables/useMemoryMediaUpload'
import { useAuthState } from '../../../shared/state/useAuthState'
import {
  journalEmbedRangeAtPosition,
  journalEmbedTypeTriggerRange,
  journalEmbedMarkdown,
  journalEmbedTriggerRange,
  mediaTagRangeAtPosition,
  mediaTriggerRange,
  type JournalEmbedKind,
  type JournalEmbedRange,
  type JournalEmbedTypeRange,
  type MediaInsertRange
} from '../utils/journalMediaMarkdown'

const MemoryContextPanel = defineAsyncComponent(() => import('./MemoryContextPanel.vue'))
const nonFocusableTabPanelPt = { root: { tabindex: -1 } } as const

type MemoryDetailCapabilities = {
  canEdit?: boolean
  canUpload?: boolean
  canCreateChildMemory?: boolean
  canDelete?: boolean
  canShare?: boolean
  canManageCover?: boolean
  canEditMediaMetadata?: boolean
  canDeleteMedia?: boolean
  canHighlightMedia?: boolean
  canDeleteCurrentMemory?: boolean
  canEditJournal?: boolean
  canDownload?: boolean
  canEditTracks?: boolean
  canEditPlaylists?: boolean
  canFavorite?: boolean
  canManageParticipants?: boolean,
  showInfo?: boolean
}

type JournalEmbedPickerItem = {
  id: string
  title: string
  meta?: string
  icon: string
}

const props = withDefaults(
  defineProps<{
    tenantName: string
    memory: Memory
    header?: MemoryHeader | null
    journal?: MemoryJournal | null
    childMemories: Memory[]
    media: MemoryMedia[]
    mediaBuckets?: MemoryMediaBucket[]
    showLocalPrimaryBucket?: boolean
    mediaLoading?: boolean
    playlists?: MemoryPlaylist[]
    tracks?: MemoryTrack[]
    activeTab?: MemoryDetailTab
    capabilities?: MemoryDetailCapabilities
    saveMemory?: (memoryId: string, request: UpdateMemoryRequest) => Promise<Memory>
    saveJournal?: (memoryId: string, request: UpdateMemoryJournalRequest) => Promise<MemoryJournal>
    createJournalLiveSession?: (memoryId: string) => Promise<MemoryJournalLiveSession>
    setCover?: (memoryId: string, request: SetMemoryCoverRequest) => Promise<Memory>
    removeCover?: (memoryId: string) => Promise<Memory>
    uploadMedia?: (
      memoryId: string,
      request: UploadMemoryMediaRequest,
      options?: UploadMediaOptions
    ) => Promise<MemoryMedia>
    deleteMemory?: (memoryId: string) => Promise<void>
    updateMediaCapturedAt?: (
      mediaId: string,
      memoryId: string,
      request: UpdateMemoryMediaCapturedAtRequest
    ) => Promise<MemoryMedia>
    updateMediaHighlight?: (
      mediaId: string,
      memoryId: string,
      request: UpdateMemoryMediaHighlightRequest
    ) => Promise<MemoryMedia>
    updateMediaMetadataBatch?: (
      memoryId: string,
      request: UpdateMemoryMediaMetadataBatchRequest
    ) => Promise<MemoryMedia[]>
    deleteMedia?: (mediaId: string, memoryId: string) => Promise<void>
    createMediaBucket?: (
      memoryId: string,
      request: CreateMemoryMediaBucketRequest
    ) => Promise<MemoryMediaBucket>
    updateMediaBucket?: (
      bucketId: string,
      memoryId: string,
      request: UpdateMemoryMediaBucketRequest
    ) => Promise<MemoryMediaBucket>
    deleteMediaBucket?: (bucketId: string, memoryId: string) => Promise<void>
    moveMedia?: (memoryId: string, request: MoveMemoryMediaRequest) => Promise<MemoryMedia[]>
    deleteMediaBatch?: (memoryId: string, request: DeleteMemoryMediaBatchRequest) => Promise<void>
    uploadTrack?: (memoryId: string, request: UploadMemoryTrackRequest) => Promise<MemoryTrack>
    updateTrack?: (
      trackId: string,
      memoryId: string,
      request: UpdateMemoryTrackRequest
    ) => Promise<MemoryTrack>
    deleteTrack?: (trackId: string, memoryId: string) => Promise<void>
    createPlaylist?: (
      memoryId: string,
      request: CreateMemoryPlaylistRequest
    ) => Promise<MemoryPlaylist>
    updatePlaylist?: (
      playlistId: string,
      memoryId: string,
      request: UpdateMemoryPlaylistRequest
    ) => Promise<MemoryPlaylist>
    deletePlaylist?: (playlistId: string, memoryId: string) => Promise<void>
    downloadUrl?: (memoryId: string, includeChildren: boolean) => string
  }>(),
  {
    showLocalPrimaryBucket: true
  }
)
const { locale, t } = useI18n()
const { authState } = useAuthState()

const emit = defineEmits<{
  openMemory: [memory: Memory]
  createChildMemory: []
  changed: [memory: Memory]
  deleted: [parentPath: string]
  mediaDeleted: [media: MemoryMedia]
  'update:activeTab': [tab: MemoryDetailTab]
}>()

const editOpen = ref(false)
const coverOpen = ref(false)
const deleteOpen = ref(false)
const shareOpen = ref(false)
const uploaderShareOpen = ref(false)
const participantsOpen = ref(false)
const uploadOpen = ref(false)
const uploadBucketId = ref<string | null>(null)
const uploadMemoryId = ref<string | null>(null)
const downloadOpen = ref(false)
const actionMenu = ref<InstanceType<typeof Menu> | null>(null)
const secondaryActionMenu = ref<InstanceType<typeof Menu> | null>(null)
const favoriteSaving = ref(false)
const favoriteOverride = ref<boolean | null>(null)
const journalEditing = ref(false)
const journalSaving = ref(false)
const journalDraft = ref('')
const journalError = ref('')
const journalOverride = ref<MemoryJournal | null>(null)
const journalTextarea = ref<HTMLTextAreaElement | null>(null)
const journalLiveEditor = ref<InstanceType<typeof MemoryJournalLiveEditor> | null>(null)
const journalLiveSession = ref<MemoryJournalLiveSession | null>(null)
const journalLiveStatus = ref('')
const mediaPickerOpen = ref(false)
const mediaPickerCaption = ref('')
const mediaPickerCaptionEdited = ref(false)
const selectedJournalMediaIds = ref<string[]>([])
const mediaInsertRange = ref<MediaInsertRange | null>(null)
const lastMediaTriggerPosition = ref<number | null>(null)
const embedTypePickerOpen = ref(false)
const embedTypeInsertRange = ref<JournalEmbedTypeRange | null>(null)
const embedTypePicker = ref<HTMLElement | null>(null)
const embedTypePickerStyle = ref<Record<string, string>>({})
const embedTypeActiveIndex = ref(0)
const embedPickerOpen = ref(false)
const embedPickerKind = ref<JournalEmbedKind>('chapter')
const embedPickerLabel = ref('')
const embedPickerLabelEdited = ref(false)
const selectedJournalEmbedIds = ref<string[]>([])
const embedInsertRange = ref<JournalEmbedRange | null>(null)
const lastJournalEmbedTriggerPosition = ref<number | null>(null)
const galleryDropActive = ref(false)
const childDropMemoryId = ref<string | null>(null)
const selectedTab = computed({
  get: () => props.activeTab ?? DEFAULT_MEMORY_DETAIL_TAB,
  set: (value: string | number) => {
    emit('update:activeTab', String(value) as MemoryDetailTab)
  }
})
const capabilities = computed(() => ({
  canEdit: props.capabilities?.canEdit ?? true,
  canUpload: props.capabilities?.canUpload ?? true,
  canCreateChildMemory: props.capabilities?.canCreateChildMemory ?? true,
  canDelete: props.capabilities?.canDelete ?? true,
  canShare: props.capabilities?.canShare ?? true,
  canManageCover: props.capabilities?.canManageCover ?? true,
  canEditMediaMetadata: props.capabilities?.canEditMediaMetadata ?? true,
  canDeleteMedia: props.capabilities?.canDeleteMedia ?? true,
  canHighlightMedia: props.capabilities?.canHighlightMedia ?? true,
  canDeleteCurrentMemory: props.capabilities?.canDeleteCurrentMemory ?? true,
  canEditJournal: props.capabilities?.canEditJournal ?? true,
  canDownload: props.capabilities?.canDownload ?? true,
  canEditTracks: props.capabilities?.canEditTracks ?? props.capabilities?.canEdit ?? true,
  canEditPlaylists: props.capabilities?.canEditPlaylists ?? props.capabilities?.canEdit ?? true,
  canFavorite: props.capabilities?.canFavorite ?? true,
  canManageParticipants:
    props.capabilities?.canManageParticipants ?? props.capabilities?.canEdit ?? true,
  showInfo: props.capabilities?.showInfo ?? true
}))
const localPrimaryUploader = computed(() => {
  const user = authState.user
  if (!user) return null
  return {
    type: 'USER' as const,
    displayName: user.displayName || user.email,
    permalink: user.username,
    userId: user.userId
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleJournalEmbedTypeOutsidePointer)
  window.removeEventListener('keydown', handleJournalEmbedTypeKeydown, true)
  window.removeEventListener('scroll', positionJournalEmbedTypePicker, true)
  window.removeEventListener('resize', positionJournalEmbedTypePicker)
})

const detailHeader = computed<MemoryHeader>(() => ({
  id: props.memory.id,
  displayName: props.header?.displayName ?? props.memory.displayName,
  path: props.header?.path ?? props.memory.path,
  coverUrl: props.header?.coverUrl ?? props.memory.coverUrl,
  coverCrop: props.header?.coverCrop ?? props.memory.coverCrop,
  startDate: props.header?.startDate ?? props.memory.startDate,
  endDate: props.header?.endDate ?? props.memory.endDate,
  directMediaCount: props.header?.directMediaCount ?? props.memory.mediaCount,
  descendantMediaCount: props.header?.descendantMediaCount ?? 0,
  childMemoryCount: props.header?.childMemoryCount ?? props.memory.childCount,
  favorite: favoriteOverride.value ?? props.header?.favorite ?? props.memory.favorite,
  scope: props.header?.scope ?? props.memory.scope,
  participants: props.header?.participants ?? []
}))
const coverUrl = computed(() => {
  if (detailHeader.value.coverUrl) return `${API_BASE}${detailHeader.value.coverUrl}`
  return ''
})
const parentPath = computed(() => {
  const segments = props.memory.path.split('/').filter(Boolean)
  if (segments.length <= 1) return '/'
  return `/${segments.slice(0, -1).join('/')}`
})
const dateRangeLabel = computed(() => {
  if (detailHeader.value.startDate === detailHeader.value.endDate)
    return formatDate(detailHeader.value.startDate)
  return `${formatDate(detailHeader.value.startDate)} - ${formatDate(detailHeader.value.endDate)}`
})
const totalMediaCount = computed(
  () => detailHeader.value.directMediaCount + detailHeader.value.descendantMediaCount
)
const {
  localTotalMediaCount,
  visibleMedia,
  visibleMediaBuckets,
  reset: resetLocalMediaState,
  mergeUpdatedMedia,
  addUploadedMedia,
  markMediaDeleted: markMediaDeletedLocally,
  addCreatedBucket,
  mergeUpdatedBucket,
  applyDeletedBucket
} = useMemoryDetailMediaState({
  memoryId: computed(() => props.memory.id),
  media: computed(() => props.media),
  mediaBuckets: computed(() => props.mediaBuckets),
  totalMediaCount
})
const mediaCountLabel = computed(() => `${localTotalMediaCount.value} ${t('memory.mediaCount')}`)
const visiblePlaylists = computed(() => props.playlists ?? [])
const visibleTracks = computed(() => props.tracks ?? [])
const journalEmbedPickerItems = computed<JournalEmbedPickerItem[]>(() =>
  journalEmbedOptions(embedPickerKind.value)
)
const journalEmbedTypeOptions = computed<
  Array<{ kind: JournalEmbedKind; label: string; icon: string; disabled: boolean }>
>(() => [
  {
    kind: 'media',
    label: t('memory.journalHelpMedia'),
    icon: 'pi pi-images',
    disabled: visibleMedia.value.length === 0
  },
  {
    kind: 'chapter',
    label: t('memory.journalHelpMemory'),
    icon: 'pi pi-sitemap',
    disabled: props.childMemories.length === 0
  },
  {
    kind: 'playlist',
    label: t('memory.journalHelpPlaylist'),
    icon: 'pi pi-volume-up',
    disabled: visiblePlaylists.value.length === 0
  },
  {
    kind: 'track',
    label: t('memory.journalHelpTrack'),
    icon: 'pi pi-map',
    disabled: visibleTracks.value.length === 0
  }
])
const journalEmbedPickerTitle = computed(() =>
  t(`memory.journalEmbedPicker.${embedPickerKind.value}.title`)
)
const journalEmbedPickerCopy = computed(() =>
  t(`memory.journalEmbedPicker.${embedPickerKind.value}.copy`)
)
const journalEmbedPickerEmpty = computed(() =>
  t(`memory.journalEmbedPicker.${embedPickerKind.value}.empty`)
)
const journalEmbedPickerConfirmLabel = computed(() =>
  t(`memory.journalEmbedPicker.${embedPickerKind.value}.confirm`)
)
const canDeleteCurrentMediaEmbed = computed(() => Boolean(mediaInsertRange.value?.mediaIds?.length))
const canDeleteCurrentJournalEmbed = computed(() => Boolean(embedInsertRange.value?.targetId))
const canDownloadMemory = computed(
  () =>
    capabilities.value.canDownload &&
    totalMediaCount.value > 0 &&
    Boolean(props.downloadUrl || props.tenantName)
)
const directDownloadUrl = computed(() => memoryDownloadUrl(false))
const subtreeDownloadUrl = computed(() => memoryDownloadUrl(true))
const canBatchUpdateMediaMetadata = computed(() =>
  Boolean(props.updateMediaMetadataBatch || props.tenantName)
)
const displayedJournal = computed(() => journalOverride.value ?? props.journal ?? null)
const journalMarkdown = computed(() => displayedJournal.value?.markdown ?? '')
const journalHasContent = computed(() => journalMarkdown.value.trim().length > 0)
const favoriteActionLabel = computed(() =>
  detailHeader.value.favorite ? t('memory.favoriteRemove') : t('memory.favoriteAdd')
)
const secondaryOverflowActionItems = computed<MenuItem[]>(() => {
  const items: Array<MenuItem | null> = [
    canDownloadMemory.value
      ? {
          label: t('memory.download'),
          icon: 'pi pi-download',
          command: () => {
            downloadOpen.value = true
          }
        }
      : null,
    capabilities.value.canEdit
      ? {
          label: t('common.edit'),
          icon: 'pi pi-pencil',
          command: () => {
            editOpen.value = true
          }
        }
      : null,
    capabilities.value.canManageCover
      ? {
          label: t('memory.cover'),
          icon: 'pi pi-image',
          command: () => {
            coverOpen.value = true
          }
        }
      : null,
    capabilities.value.canManageParticipants
      ? {
          label: t('memory.participantsManage'),
          icon: 'pi pi-users',
          command: () => {
            participantsOpen.value = true
          }
        }
      : null,
    capabilities.value.canShare
      ? {
          label: t('share.uploadRequest'),
          icon: 'pi pi-user-plus',
          command: () => {
            uploaderShareOpen.value = true
          }
        }
      : null,
    capabilities.value.canShare
      ? {
          label: t('share.title'),
          icon: 'pi pi-share-alt',
          command: () => {
            shareOpen.value = true
          }
        }
      : null,
    capabilities.value.canDelete && capabilities.value.canDeleteCurrentMemory
      ? {
          label: t('common.delete'),
          icon: 'pi pi-trash',
          class: 'memory-menu-danger',
          command: () => {
            deleteOpen.value = true
          }
        }
      : null
  ]
  return items.filter((item): item is MenuItem => item !== null)
})
const overflowActionItems = computed<MenuItem[]>(() => {
  const items: Array<MenuItem | null> = [
    capabilities.value.canFavorite
      ? {
          label: favoriteActionLabel.value,
          icon: detailHeader.value.favorite ? 'pi pi-star-fill' : 'pi pi-star',
          disabled: favoriteSaving.value,
          command: () => {
            void toggleFavorite()
          }
        }
      : null,
    ...secondaryOverflowActionItems.value
  ]
  return items.filter((item): item is MenuItem => item !== null)
})

watch(
  () => props.journal?.markdown,
  (value) => {
    if (!journalEditing.value) journalDraft.value = value ?? ''
  },
  { immediate: true }
)

watch(
  () => props.journal?.updatedAt,
  (value, previous) => {
    if (previous && value !== previous) journalOverride.value = null
  }
)

watch(
  () => props.memory.id,
  () => {
    journalOverride.value = null
    favoriteOverride.value = null
    uploadMemoryId.value = null
    uploadBucketId.value = null
    resetLocalMediaState()
  }
)

function formatDate(value: string): string {
  return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'de-DE', {
    dateStyle: 'medium'
  }).format(new Date(value))
}

function memoryDownloadUrl(includeChildren: boolean): string {
  if (props.downloadUrl) return props.downloadUrl(props.memory.id, includeChildren)
  const tenant = encodeURIComponent(props.tenantName)
  const memoryId = encodeURIComponent(props.memory.id)
  return `${API_BASE}/api/${tenant}/memories/${memoryId}/download?includeChildren=${includeChildren}&locale=${encodeURIComponent(locale.value)}`
}

async function updateSingleMediaCapturedAt(
  mediaId: string,
  memoryId: string,
  request: UpdateMemoryMediaCapturedAtRequest
): Promise<MemoryMedia> {
  const updated = props.updateMediaCapturedAt
    ? await props.updateMediaCapturedAt(mediaId, memoryId, request)
    : await updateMemoryMediaCapturedAt(props.tenantName, memoryId, mediaId, request)
  mergeUpdatedMedia([updated])
  return updated
}

async function updateSingleMediaHighlight(
  mediaId: string,
  memoryId: string,
  request: UpdateMemoryMediaHighlightRequest
): Promise<MemoryMedia> {
  const updated = props.updateMediaHighlight
    ? await props.updateMediaHighlight(mediaId, memoryId, request)
    : await updateMemoryMediaHighlight(props.tenantName, memoryId, mediaId, request)
  mergeUpdatedMedia([updated])
  return updated
}

async function submitMediaMetadataBatch(
  memoryId: string,
  mediaIds: string[],
  capturedAtOffsetSeconds: number
): Promise<MemoryMedia[]> {
  const request = { mediaIds, capturedAtOffsetSeconds }
  if (!props.updateMediaMetadataBatch && !props.tenantName)
    throw new Error(t('media.batchSaveFailed'))
  const updated = props.updateMediaMetadataBatch
    ? await props.updateMediaMetadataBatch(memoryId, request)
    : await updateTenantMediaMetadataBatch(props.tenantName, memoryId, request)
  mergeUpdatedMedia(updated)
  return updated
}

function openBucketUpload(bucketId: string | null, memoryId: string) {
  uploadBucketId.value = bucketId
  uploadMemoryId.value = memoryId
  uploadOpen.value = true
}

function openMemoryUpload() {
  uploadBucketId.value = null
  uploadMemoryId.value = props.memory.id
  uploadOpen.value = true
}

function dropFiles(event: DragEvent): File[] {
  return mediaFilesFromList(event.dataTransfer?.files ?? [])
}

function hasDroppedMedia(event: DragEvent): boolean {
  return dropFiles(event).length > 0
}

function hasPotentialFileDrop(event: DragEvent): boolean {
  return hasDroppedMedia(event) || Boolean(event.dataTransfer?.types.includes('Files'))
}

function allowMediaDrop(event: DragEvent) {
  if (!capabilities.value.canUpload || !hasPotentialFileDrop(event)) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
}

function startDroppedMemoryUpload(files: File[], memoryId: string, bucketId?: string | null) {
  const batch = startMemoryMediaUpload({
    tenantName: props.tenantName,
    memoryId,
    bucketId,
    files,
    uploadMedia: props.uploadMedia,
    onFileComplete: addUploadedMedia,
    onComplete: () => {
      emit('changed', props.memory)
    }
  })
  return Boolean(batch)
}

function activateGalleryDrop(event: DragEvent) {
  allowMediaDrop(event)
  if (capabilities.value.canUpload && hasPotentialFileDrop(event)) galleryDropActive.value = true
}

function deactivateGalleryDrop() {
  galleryDropActive.value = false
}

function handleGalleryDrop(event: DragEvent) {
  allowMediaDrop(event)
  const files = dropFiles(event)
  galleryDropActive.value = false
  if (!files.length || !capabilities.value.canUpload) return
  startDroppedMemoryUpload(files, props.memory.id)
}

function activateChildDrop(event: DragEvent, memoryId: string) {
  allowMediaDrop(event)
  if (capabilities.value.canUpload && hasPotentialFileDrop(event))
    childDropMemoryId.value = memoryId
}

function deactivateChildDrop(memoryId: string) {
  if (childDropMemoryId.value === memoryId) childDropMemoryId.value = null
}

function handleChildDrop(event: DragEvent, memory: Memory) {
  allowMediaDrop(event)
  const files = dropFiles(event)
  childDropMemoryId.value = null
  if (!files.length || !capabilities.value.canUpload) return
  startDroppedMemoryUpload(files, memory.id)
}

async function submitMediaDelete(mediaId: string, memoryId: string): Promise<void> {
  if (props.deleteMedia) {
    await props.deleteMedia(mediaId, memoryId)
  } else {
    await deleteTenantMemoryMedia(props.tenantName, memoryId, mediaId)
  }
}

function markMediaDeleted(media: MemoryMedia) {
  markMediaDeletedLocally(media)
  emit('mediaDeleted', media)
}

async function submitMediaBucketCreate(
  memoryId: string,
  parentBucketId: string,
  displayName: string
): Promise<MemoryMediaBucket> {
  const request = { parentBucketId, displayName }
  const bucket = props.createMediaBucket
    ? await props.createMediaBucket(memoryId, request)
    : await createTenantMemoryMediaBucket(props.tenantName, memoryId, request)
  if (parentBucketId === SELF_PRIMARY_BUCKET_PLACEHOLDER_ID && bucket.parentBucketId) {
    addCreatedBucket(materializedSelfPrimaryBucket(memoryId, bucket.parentBucketId))
  }
  addCreatedBucket(bucket)
  return bucket
}

function materializedSelfPrimaryBucket(memoryId: string, bucketId: string): MemoryMediaBucket {
  const user = authState.user
  const displayName = user?.displayName || user?.email || t('media.unknownUploader')
  const now = new Date().toISOString()
  return {
    id: bucketId,
    memoryId,
    displayName,
    primary: true,
    uploader: {
      type: 'USER',
      displayName,
      permalink: user?.username,
      userId: user?.userId
    },
    canManage: true,
    mediaCount: 0,
    createdAt: now,
    updatedAt: now
  }
}

async function submitMediaBucketUpdate(
  memoryId: string,
  bucketId: string,
  displayName: string
): Promise<MemoryMediaBucket> {
  const request = { displayName }
  const bucket = props.updateMediaBucket
    ? await props.updateMediaBucket(bucketId, memoryId, request)
    : await updateTenantMemoryMediaBucket(props.tenantName, memoryId, bucketId, request)
  mergeUpdatedBucket(bucket)
  return bucket
}

async function submitMediaBucketDelete(memoryId: string, bucketId: string): Promise<void> {
  if (props.deleteMediaBucket) {
    await props.deleteMediaBucket(bucketId, memoryId)
  } else {
    await deleteTenantMemoryMediaBucket(props.tenantName, memoryId, bucketId)
  }
  applyDeletedBucket(bucketId)
}

async function submitMediaMove(
  memoryId: string,
  mediaIds: string[],
  targetBucketId: string
): Promise<MemoryMedia[]> {
  const request = { mediaIds, targetBucketId }
  const moved = props.moveMedia
    ? await props.moveMedia(memoryId, request)
    : await moveTenantMemoryMedia(props.tenantName, memoryId, request)
  mergeUpdatedMedia(moved)
  return moved
}

async function submitMediaBatchDelete(memoryId: string, mediaIds: string[]): Promise<void> {
  const request = { mediaIds }
  if (props.deleteMediaBatch) {
    await props.deleteMediaBatch(memoryId, request)
  } else {
    await deleteTenantMemoryMediaBatch(props.tenantName, memoryId, request)
  }
  const deleted = visibleMedia.value.filter((media) => mediaIds.includes(media.id))
  deleted.forEach(markMediaDeleted)
}

function submitTrackUpload(request: UploadMemoryTrackRequest): Promise<MemoryTrack> {
  if (props.uploadTrack) return props.uploadTrack(props.memory.id, request)
  return uploadTenantMemoryTrack(props.tenantName, props.memory.id, request)
}

function submitTrackUpdate(
  trackId: string,
  request: UpdateMemoryTrackRequest
): Promise<MemoryTrack> {
  if (props.updateTrack) return props.updateTrack(trackId, props.memory.id, request)
  return updateTenantMemoryTrack(props.tenantName, props.memory.id, trackId, request)
}

function submitTrackDelete(trackId: string): Promise<void> {
  if (props.deleteTrack) return props.deleteTrack(trackId, props.memory.id)
  return deleteTenantMemoryTrack(props.tenantName, props.memory.id, trackId)
}

function submitPlaylistCreate(request: CreateMemoryPlaylistRequest): Promise<MemoryPlaylist> {
  if (props.createPlaylist) return props.createPlaylist(props.memory.id, request)
  return createTenantMemoryPlaylist(props.tenantName, props.memory.id, request)
}

function submitPlaylistUpdate(
  playlistId: string,
  request: UpdateMemoryPlaylistRequest
): Promise<MemoryPlaylist> {
  if (props.updatePlaylist) return props.updatePlaylist(playlistId, props.memory.id, request)
  return updateTenantMemoryPlaylist(props.tenantName, props.memory.id, playlistId, request)
}

function submitPlaylistDelete(playlistId: string): Promise<void> {
  if (props.deletePlaylist) return props.deletePlaylist(playlistId, props.memory.id)
  return deleteTenantMemoryPlaylist(props.tenantName, props.memory.id, playlistId)
}

async function startJournalEdit() {
  journalDraft.value = journalMarkdown.value
  journalError.value = ''
  journalEditing.value = true
  journalLiveStatus.value = ''
  journalLiveSession.value = null
  if (!props.createJournalLiveSession) {
    journalLiveStatus.value = 'fallback'
    return
  }
  try {
    journalLiveSession.value = await props.createJournalLiveSession(props.memory.id)
  } catch {
    journalLiveStatus.value = 'fallback'
  }
}

function fallbackToRestJournalEditor() {
  journalLiveSession.value = null
  journalLiveStatus.value = 'fallback'
}

function cancelJournalEdit() {
  journalDraft.value = journalMarkdown.value
  journalError.value = ''
  journalEditing.value = false
  journalLiveSession.value = null
  journalLiveStatus.value = ''
  closeMediaPicker()
  closeJournalEmbedTypePicker()
  closeJournalEmbedPicker()
}

async function submitJournal() {
  if (!props.saveJournal) return
  journalSaving.value = true
  journalError.value = ''
  try {
    const updatedJournal = await props.saveJournal(props.memory.id, { markdown: journalDraft.value })
    journalOverride.value = updatedJournal
    journalEditing.value = false
    emit('changed', props.memory)
  } catch {
    journalError.value = t('memory.journalSaveFailed')
  } finally {
    journalSaving.value = false
  }
}

function handleJournalLiveStatus(status: string) {
  journalLiveStatus.value = status
  if (status !== 'saved' || !journalLiveSession.value) return
  journalOverride.value = {
    memoryId: props.memory.id,
    markdown: journalDraft.value,
    updatedAt: new Date().toISOString()
  }
}

async function toggleFavorite() {
  if (favoriteSaving.value) return
  favoriteSaving.value = true
  try {
    const updated = detailHeader.value.favorite
      ? await unfavoriteMemory(props.tenantName, props.memory.id)
      : await favoriteMemory(props.tenantName, props.memory.id)
    favoriteOverride.value = updated.favorite
    notifyMemoryFavoritesChanged()
  } finally {
    favoriteSaving.value = false
  }
}

function toggleActionMenu(event: MouseEvent) {
  actionMenu.value?.toggle(event)
}

function toggleSecondaryActionMenu(event: MouseEvent) {
  secondaryActionMenu.value?.toggle(event)
}

function toggleJournalMedia(mediaId: string) {
  selectedJournalMediaIds.value = selectedJournalMediaIds.value.includes(mediaId)
    ? selectedJournalMediaIds.value.filter((id) => id !== mediaId)
    : [...selectedJournalMediaIds.value, mediaId]
  syncMediaPickerCaption()
}

function openMediaPicker(range?: MediaInsertRange) {
  const existingRange = range ?? currentEditorMediaTagRange()
  mediaInsertRange.value =
    existingRange ?? (journalLiveEditor.value ? null : currentTextareaRange())
  selectedJournalMediaIds.value =
    existingRange?.mediaIds?.filter((id) => visibleMedia.value.some((media) => media.id === id)) ??
    []
  mediaPickerCaption.value = existingRange?.caption ?? ''
  mediaPickerCaptionEdited.value = Boolean(existingRange?.caption)
  embedTypePickerOpen.value = false
  mediaPickerOpen.value = true
}

function journalEmbedOptions(kind: JournalEmbedKind): JournalEmbedPickerItem[] {
  if (kind === 'media') return []
  if (kind === 'chapter') {
    return props.childMemories.map((memory) => ({
      id: memory.id,
      title: memory.displayName,
      meta: memory.path,
      icon: 'pi pi-sitemap'
    }))
  }
  if (kind === 'playlist') {
    return visiblePlaylists.value.map((playlist) => ({
      id: playlist.id,
      title: playlist.title,
      meta: playlist.provider,
      icon: 'pi pi-volume-up'
    }))
  }
  return visibleTracks.value.map((track) => ({
    id: track.id,
    title: track.title,
    meta: [formatJournalTrackDistance(track.distanceMeters), track.activityType]
      .filter(Boolean)
      .join(' · '),
    icon: 'pi pi-map'
  }))
}

function openJournalEmbedTypePicker(range?: JournalEmbedTypeRange) {
  embedTypeInsertRange.value = range ?? (journalLiveEditor.value ? null : currentTextareaRange())
  mediaPickerOpen.value = false
  embedPickerOpen.value = false
  embedTypeActiveIndex.value = firstEnabledJournalEmbedTypeIndex()
  positionJournalEmbedTypePicker()
  embedTypePickerOpen.value = true
  void nextTick(() => {
    positionJournalEmbedTypePicker()
    focusJournalEmbedTypePicker()
    document.addEventListener('pointerdown', handleJournalEmbedTypeOutsidePointer)
    window.addEventListener('keydown', handleJournalEmbedTypeKeydown, true)
    window.addEventListener('scroll', positionJournalEmbedTypePicker, true)
    window.addEventListener('resize', positionJournalEmbedTypePicker)
  })
}

function closeJournalEmbedTypePicker() {
  embedTypePickerOpen.value = false
  embedTypeInsertRange.value = null
  document.removeEventListener('pointerdown', handleJournalEmbedTypeOutsidePointer)
  window.removeEventListener('keydown', handleJournalEmbedTypeKeydown, true)
  window.removeEventListener('scroll', positionJournalEmbedTypePicker, true)
  window.removeEventListener('resize', positionJournalEmbedTypePicker)
}

function handleJournalEmbedTypeOutsidePointer(event: PointerEvent) {
  const target = event.target
  if (!(target instanceof Node)) return
  if (embedTypePicker.value?.contains(target)) return
  closeJournalEmbedTypePicker()
}

function positionJournalEmbedTypePicker() {
  const anchor =
    document.querySelector<HTMLElement>('.memory-journal-codemirror .cm-cursor') ??
    journalTextarea.value ??
    document.querySelector<HTMLElement>('.memory-journal-editor')
  if (!anchor) {
    embedTypePickerStyle.value = {}
    return
  }
  const rect = anchor.getBoundingClientRect()
  const width = 260
  const margin = 16
  const left = Math.min(Math.max(rect.left, margin), window.innerWidth - width - margin)
  const top = Math.min(Math.max(rect.bottom + 8, margin), window.innerHeight - 260)
  embedTypePickerStyle.value = {
    left: `${left}px`,
    top: `${top}px`
  }
}

function selectJournalEmbedType(kind: JournalEmbedKind) {
  const range = embedTypeInsertRange.value ?? currentTextareaRange()
  closeJournalEmbedTypePicker()
  if (kind === 'media') {
    openMediaPicker({ start: range.start, end: range.end })
    return
  }
  openJournalEmbedPicker(kind, { start: range.start, end: range.end, kind })
}

function handleJournalEmbedTypeKeydown(event: KeyboardEvent) {
  if (!embedTypePickerOpen.value) return
  const options = enabledJournalEmbedTypeOptions()
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    closeJournalEmbedTypePicker()
    return
  }
  if (options.length === 0) return
  if (event.key === 'ArrowDown' || event.key === 'ArrowRight') {
    event.preventDefault()
    event.stopPropagation()
    moveJournalEmbedTypeActive(1)
  } else if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') {
    event.preventDefault()
    event.stopPropagation()
    moveJournalEmbedTypeActive(-1)
  } else if (event.key === 'Home') {
    event.preventDefault()
    event.stopPropagation()
    setJournalEmbedTypeActive(options[0].index)
  } else if (event.key === 'End') {
    event.preventDefault()
    event.stopPropagation()
    setJournalEmbedTypeActive(options[options.length - 1].index)
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    event.stopPropagation()
    const option = journalEmbedTypeOptions.value[embedTypeActiveIndex.value]
    if (option && !option.disabled) selectJournalEmbedType(option.kind)
  }
}

function focusJournalEmbedTypeOption(index: number) {
  embedTypeActiveIndex.value = index
  focusJournalEmbedTypePicker()
}

function focusJournalEmbedTypePicker() {
  embedTypePicker.value?.focus({ preventScroll: true })
}

function moveJournalEmbedTypeActive(delta: number) {
  const options = enabledJournalEmbedTypeOptions()
  const enabledPosition = options.findIndex((option) => option.index === embedTypeActiveIndex.value)
  const currentPosition = enabledPosition === -1 ? 0 : enabledPosition
  const next = options[(currentPosition + delta + options.length) % options.length]
  if (next) setJournalEmbedTypeActive(next.index)
}

function setJournalEmbedTypeActive(index: number) {
  embedTypeActiveIndex.value = index
  focusJournalEmbedTypeOption(index)
}

function firstEnabledJournalEmbedTypeIndex(): number {
  return enabledJournalEmbedTypeOptions()[0]?.index ?? 0
}

function enabledJournalEmbedTypeOptions(): Array<{ index: number; kind: JournalEmbedKind }> {
  return journalEmbedTypeOptions.value
    .map((option, index) => ({ index, kind: option.kind, disabled: option.disabled }))
    .filter((option) => !option.disabled)
}

function openJournalEmbedPicker(kind: JournalEmbedKind, range?: JournalEmbedRange) {
  const currentRange = range ?? currentEditorJournalEmbedRange()
  const existingRange = currentRange?.kind === kind ? currentRange : null
  const activeKind = existingRange?.kind ?? kind
  embedPickerKind.value = activeKind
  embedInsertRange.value =
    existingRange ?? (journalLiveEditor.value ? null : currentTextareaRange(activeKind))
  const options = journalEmbedOptions(activeKind)
  const existingIds = existingRange?.targetIds ?? (existingRange?.targetId ? [existingRange.targetId] : [])
  selectedJournalEmbedIds.value = existingIds.filter((id) => options.some((item) => item.id === id))
  embedPickerLabel.value = existingRange?.label ?? ''
  embedPickerLabelEdited.value = Boolean(existingRange?.label)
  if (!embedPickerLabelEdited.value) syncJournalEmbedPickerLabel()
  embedTypePickerOpen.value = false
  embedPickerOpen.value = true
}

function closeMediaPicker() {
  mediaPickerOpen.value = false
  selectedJournalMediaIds.value = []
  mediaPickerCaption.value = ''
  mediaPickerCaptionEdited.value = false
  mediaInsertRange.value = null
}

function closeJournalEmbedPicker() {
  embedPickerOpen.value = false
  selectedJournalEmbedIds.value = []
  embedPickerLabel.value = ''
  embedPickerLabelEdited.value = false
  embedInsertRange.value = null
}

function currentTextareaRange(): MediaInsertRange
function currentTextareaRange(kind: JournalEmbedKind): JournalEmbedRange
function currentTextareaRange(kind?: JournalEmbedKind): MediaInsertRange | JournalEmbedRange {
  const textarea = journalTextarea.value
  const position = textarea?.selectionStart ?? journalDraft.value.length
  const range = { start: position, end: textarea?.selectionEnd ?? position }
  return kind ? { ...range, kind } : range
}

function currentEditorMediaTagRange(): MediaInsertRange | null {
  if (journalLiveEditor.value) return journalLiveEditor.value.currentMediaTagRange()
  const textarea = journalTextarea.value
  if (!textarea) return null
  return mediaTagRangeAtPosition(journalDraft.value, textarea.selectionStart)
}

function currentEditorJournalEmbedRange(): JournalEmbedRange | null {
  if (journalLiveEditor.value) return journalLiveEditor.value.currentJournalEmbedRange()
  const textarea = journalTextarea.value
  if (!textarea) return null
  return journalEmbedRangeAtPosition(journalDraft.value, textarea.selectionStart)
}

function syncMediaPickerCaption() {
  if (mediaPickerCaptionEdited.value) return
  if (selectedJournalMediaIds.value.length === 1) {
    mediaPickerCaption.value =
      visibleMedia.value.find((media) => media.id === selectedJournalMediaIds.value[0])?.title || ''
  } else if (selectedJournalMediaIds.value.length > 1) {
    mediaPickerCaption.value = t('memory.journalMediaCarouselCaption')
  } else {
    mediaPickerCaption.value = ''
  }
}

function selectJournalEmbed(itemId: string) {
  if (embedPickerKind.value === 'track') {
    selectedJournalEmbedIds.value = selectedJournalEmbedIds.value.includes(itemId)
      ? selectedJournalEmbedIds.value.filter((id) => id !== itemId)
      : [...selectedJournalEmbedIds.value, itemId]
  } else {
    selectedJournalEmbedIds.value = [itemId]
  }
  syncJournalEmbedPickerLabel()
}

function syncJournalEmbedPickerLabel() {
  if (embedPickerLabelEdited.value) return
  if (selectedJournalEmbedIds.value.length === 1) {
    embedPickerLabel.value =
      journalEmbedPickerItems.value.find((item) => item.id === selectedJournalEmbedIds.value[0])?.title ||
      ''
  } else if (selectedJournalEmbedIds.value.length > 1) {
    embedPickerLabel.value = t('memory.journalTrackSelectionTitle', {
      count: selectedJournalEmbedIds.value.length
    })
  } else {
    embedPickerLabel.value = ''
  }
}

function insertSelectedJournalMedia() {
  if (selectedJournalMediaIds.value.length === 0) return
  const range = mediaInsertRange.value ?? currentTextareaRange()
  const caption =
    mediaPickerCaption.value.trim() ||
    (selectedJournalMediaIds.value.length === 1
      ? visibleMedia.value.find((media) => media.id === selectedJournalMediaIds.value[0])?.title ||
        'Caption'
      : t('memory.journalMediaCarouselCaption'))
  const markdown = journalEmbedMarkdown('media', selectedJournalMediaIds.value, caption)
  if (journalLiveEditor.value) {
    journalLiveEditor.value.insertMarkdown(markdown, range)
    closeMediaPicker()
    return
  }
  journalDraft.value = `${journalDraft.value.slice(0, range.start)}${markdown}${journalDraft.value.slice(range.end)}`
  const nextCursor = range.start + markdown.length
  closeMediaPicker()
  window.setTimeout(() => {
    journalTextarea.value?.focus()
    journalTextarea.value?.setSelectionRange(nextCursor, nextCursor)
  })
}

function deleteSelectedJournalMediaEmbed() {
  const range = mediaInsertRange.value
  if (!range?.mediaIds?.length) return
  if (journalLiveEditor.value) {
    journalLiveEditor.value.insertMarkdown('', range)
    closeMediaPicker()
    return
  }
  journalDraft.value = `${journalDraft.value.slice(0, range.start)}${journalDraft.value.slice(range.end)}`
  const nextCursor = range.start
  closeMediaPicker()
  window.setTimeout(() => {
    journalTextarea.value?.focus()
    journalTextarea.value?.setSelectionRange(nextCursor, nextCursor)
  })
}

function insertSelectedJournalEmbed() {
  if (selectedJournalEmbedIds.value.length === 0) return
  const range = embedInsertRange.value ?? currentTextareaRange(embedPickerKind.value)
  const fallbackLabel =
    journalEmbedPickerItems.value.find((item) => item.id === selectedJournalEmbedIds.value[0])?.title ||
    t(`memory.journalEmbedPicker.${embedPickerKind.value}.fallbackLabel`)
  const label = embedPickerLabel.value.trim() || fallbackLabel
  const markdown = journalEmbedMarkdown(
    embedPickerKind.value,
    selectedJournalEmbedIds.value,
    label
  )
  if (journalLiveEditor.value) {
    journalLiveEditor.value.insertMarkdown(markdown, range)
    closeJournalEmbedPicker()
    return
  }
  journalDraft.value = `${journalDraft.value.slice(0, range.start)}${markdown}${journalDraft.value.slice(range.end)}`
  const nextCursor = range.start + markdown.length
  closeJournalEmbedPicker()
  window.setTimeout(() => {
    journalTextarea.value?.focus()
    journalTextarea.value?.setSelectionRange(nextCursor, nextCursor)
  })
}

function deleteSelectedJournalEmbed() {
  const range = embedInsertRange.value
  if (!range?.targetId) return
  if (journalLiveEditor.value) {
    journalLiveEditor.value.insertMarkdown('', range)
    closeJournalEmbedPicker()
    return
  }
  journalDraft.value = `${journalDraft.value.slice(0, range.start)}${journalDraft.value.slice(range.end)}`
  const nextCursor = range.start
  closeJournalEmbedPicker()
  window.setTimeout(() => {
    journalTextarea.value?.focus()
    journalTextarea.value?.setSelectionRange(nextCursor, nextCursor)
  })
}

function formatJournalTrackDistance(meters: number): string {
  if (meters >= 1000)
    return `${(meters / 1000).toLocaleString(locale.value === 'en' ? 'en-US' : 'de-DE', { maximumFractionDigits: 1 })} km`
  return `${meters.toLocaleString(locale.value === 'en' ? 'en-US' : 'de-DE')} m`
}

function handleJournalInput(event: Event) {
  const textarea = event.target as HTMLTextAreaElement
  const position = textarea.selectionStart
  const typeRange = journalEmbedTypeTriggerRange(journalDraft.value, position)
  if (typeRange) {
    openJournalEmbedTypePicker(typeRange)
    return
  }
  const range = mediaTriggerRange(journalDraft.value, position)
  if (range) {
    if (lastMediaTriggerPosition.value === position) return
    lastMediaTriggerPosition.value = position
    openMediaPicker(range)
    return
  }
  const embedRange = journalEmbedTriggerRange(journalDraft.value, position)
  if (!embedRange) return
  if (lastJournalEmbedTriggerPosition.value === position) return
  lastJournalEmbedTriggerPosition.value = position
  openJournalEmbedPicker(embedRange.kind, embedRange)
}

function handleJournalDoubleClick(event: MouseEvent) {
  const textarea = event.target as HTMLTextAreaElement
  const range = mediaTagRangeAtPosition(journalDraft.value, textarea.selectionStart)
  if (range) {
    openMediaPicker(range)
    return
  }
  const embedRange = journalEmbedRangeAtPosition(journalDraft.value, textarea.selectionStart)
  if (embedRange) openJournalEmbedPicker(embedRange.kind, embedRange)
}
</script>

<template>
  <div class="memory-detail">
    <header class="memory-detail-header">
      <div
        class="memory-detail-cover"
        :class="{ 'memory-detail-cover-has-image': Boolean(coverUrl) }"
      >
        <MemoryCoverImage
          v-if="coverUrl"
          :src="coverUrl"
          :alt="detailHeader.displayName"
          :crop="detailHeader.coverCrop"
        />
        <span v-else class="pi pi-images" />
      </div>
      <div class="memory-detail-heading">
        <div class="memory-detail-title-row">
          <div class="memory-detail-title-copy">
            <h1>{{ detailHeader.displayName }}</h1>
          </div>
          <div class="memory-detail-control-panel">
            <div class="memory-detail-actions">
              <Button
                v-if="capabilities.canFavorite"
                v-tooltip.bottom="favoriteActionLabel"
                type="button"
                class="veyq-icon-button veyq-prime-icon-button memory-action-inline"
                :icon="detailHeader.favorite ? 'pi pi-star-fill' : 'pi pi-star'"
                :aria-label="favoriteActionLabel"
                :severity="detailHeader.favorite ? 'warn' : 'secondary'"
                :outlined="!detailHeader.favorite"
                :loading="favoriteSaving"
                size="small"
                @click="toggleFavorite"
              />
              <Button
                v-if="capabilities.canUpload"
                type="button"
                icon="pi pi-upload"
                :label="t('memory.upload')"
                class="memory-upload-action"
                size="small"
                @click="openMemoryUpload"
              />
              <MemoryHeaderPlaylistPlayer
                v-if="visiblePlaylists.length > 0"
                :playlists="visiblePlaylists"
              />
              <Button
                v-if="canDownloadMemory"
                v-tooltip.bottom="t('memory.download')"
                type="button"
                class="veyq-icon-button veyq-prime-icon-button memory-action-inline memory-action-collapsible"
                icon="pi pi-download"
                :aria-label="t('memory.download')"
                severity="secondary"
                outlined
                size="small"
                @click="downloadOpen = true"
              />
              <Button
                v-if="capabilities.canEdit"
                v-tooltip.bottom="t('common.edit')"
                type="button"
                class="veyq-icon-button veyq-prime-icon-button memory-action-inline memory-action-collapsible"
                icon="pi pi-pencil"
                :aria-label="t('common.edit')"
                severity="secondary"
                outlined
                size="small"
                @click="editOpen = true"
              />
              <Button
                v-if="capabilities.canManageCover"
                v-tooltip.bottom="t('memory.cover')"
                type="button"
                class="veyq-icon-button veyq-prime-icon-button memory-action-inline memory-action-collapsible"
                icon="pi pi-image"
                :aria-label="t('memory.cover')"
                severity="secondary"
                outlined
                size="small"
                @click="coverOpen = true"
              />
              <Button
                v-if="capabilities.canManageParticipants"
                v-tooltip.bottom="t('memory.participantsManage')"
                type="button"
                class="veyq-icon-button veyq-prime-icon-button memory-action-inline memory-action-collapsible"
                icon="pi pi-users"
                :aria-label="t('memory.participantsManage')"
                severity="secondary"
                outlined
                size="small"
                @click="participantsOpen = true"
              />
              <Button
                v-if="capabilities.canShare"
                v-tooltip.bottom="t('share.uploadRequest')"
                type="button"
                class="veyq-icon-button veyq-prime-icon-button memory-action-inline memory-action-collapsible"
                icon="pi pi-user-plus"
                :aria-label="t('share.uploadRequest')"
                severity="secondary"
                outlined
                size="small"
                @click="uploaderShareOpen = true"
              />
              <Button
                v-if="capabilities.canShare"
                v-tooltip.bottom="t('share.title')"
                type="button"
                class="veyq-icon-button veyq-prime-icon-button memory-action-inline memory-action-collapsible"
                icon="pi pi-share-alt"
                :aria-label="t('share.title')"
                severity="secondary"
                outlined
                size="small"
                @click="shareOpen = true"
              />
              <Button
                v-if="capabilities.canDelete && capabilities.canDeleteCurrentMemory"
                v-tooltip.bottom="t('common.delete')"
                type="button"
                class="veyq-icon-button veyq-prime-icon-button memory-danger-action memory-action-inline memory-action-collapsible"
                icon="pi pi-trash"
                :aria-label="t('common.delete')"
                severity="danger"
                outlined
                size="small"
                @click="deleteOpen = true"
              />
              <Button
                v-if="secondaryOverflowActionItems.length"
                v-tooltip.bottom="t('common.more')"
                type="button"
                class="veyq-icon-button veyq-prime-icon-button memory-actions-overflow memory-actions-overflow-secondary"
                icon="pi pi-ellipsis-h"
                :aria-label="t('common.more')"
                severity="secondary"
                outlined
                size="small"
                aria-haspopup="menu"
                @click="toggleSecondaryActionMenu"
              />
              <Menu ref="secondaryActionMenu" :model="secondaryOverflowActionItems" popup />
              <Button
                v-if="overflowActionItems.length"
                v-tooltip.bottom="t('common.more')"
                type="button"
                class="veyq-icon-button veyq-prime-icon-button memory-actions-overflow memory-actions-overflow-mobile"
                icon="pi pi-ellipsis-h"
                :aria-label="t('common.more')"
                severity="secondary"
                outlined
                size="small"
                aria-haspopup="menu"
                @click="toggleActionMenu"
              />
              <Menu ref="actionMenu" :model="overflowActionItems" popup />
            </div>
          </div>
        </div>
        <div class="memory-detail-meta">
          <span><i class="pi pi-calendar" /> {{ dateRangeLabel }}</span>
          <span><i class="pi pi-images" /> {{ mediaCountLabel }}</span>
          <span v-if="detailHeader.childMemoryCount > 0">
            <i class="pi pi-sitemap" /> {{ detailHeader.childMemoryCount }}
            {{ t('memory.children') }}
          </span>
        </div>
        <MemoryParticipantsStrip
          :tenant-name="tenantName"
          :participants="detailHeader.participants"
        />
      </div>
    </header>

    <Tabs v-model:value="selectedTab" class="memory-tabs">
      <TabList>
        <Tab value="gallery">{{ t('memory.gallery') }}</Tab>
        <Tab value="map">{{ t('memory.context') }}</Tab>
        <Tab value="playlists">{{ t('playlist.title') }}</Tab>
        <Tab value="journal">{{ t('memory.journal') }}</Tab>
        <Tab value="children">{{ t('memory.children') }}</Tab>
        <Tab value="contributions">{{ t('media.contributions') }}</Tab>
        <Tab v-if="capabilities.showInfo" value="info">{{ t('memory.info') }}</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="gallery" :pt="nonFocusableTabPanelPt">
          <div
            class="memory-gallery-drop-target"
            :class="{ 'memory-drop-target-active': galleryDropActive }"
            @dragenter="activateGalleryDrop"
            @dragover="allowMediaDrop"
            @dragleave="deactivateGalleryDrop"
            @drop="handleGalleryDrop"
          >
            <div v-if="capabilities.canUpload && galleryDropActive" class="memory-dropzone">
              <span class="pi pi-cloud-upload" />
              <div>
                <strong>{{ t('media.dropzoneMemoryTitle') }}</strong>
                <small>{{ t('media.dropzoneMemoryCopy') }}</small>
              </div>
            </div>
            <div
              v-if="visibleMedia.length === 0 && !mediaLoading"
              class="veyq-empty veyq-empty-action"
            >
              <span class="pi pi-images" />
              <span>{{ t('memory.galleryEmptyAction') }}</span>
              <Button
                v-if="capabilities.canUpload"
                type="button"
                icon="pi pi-upload"
                :label="t('memory.upload')"
                size="small"
                @click="openMemoryUpload"
              />
            </div>
            <MediaGrid
              v-else
              :tenant-name="tenantName"
              :media="visibleMedia"
              :media-buckets="visibleMediaBuckets"
              :streaming="Boolean(mediaLoading)"
              :can-edit-metadata="capabilities.canEditMediaMetadata"
              :can-delete-media="capabilities.canDeleteMedia"
              :can-highlight="capabilities.canHighlightMedia"
              :update-captured-at="updateSingleMediaCapturedAt"
              :update-highlight="updateSingleMediaHighlight"
              :delete-media="submitMediaDelete"
              @updated="mergeUpdatedMedia([$event])"
              @deleted="markMediaDeleted"
            />
          </div>
        </TabPanel>
        <TabPanel value="contributions" :pt="nonFocusableTabPanelPt">
          <MemoryAssetBuckets
            :tenant-name="tenantName"
            :memory-id="memory.id"
            :media="visibleMedia"
            :buckets="visibleMediaBuckets"
            :show-local-primary-bucket="showLocalPrimaryBucket !== false"
            :local-primary-uploader="localPrimaryUploader"
            :can-upload="capabilities.canUpload"
            :can-edit-metadata="capabilities.canEditMediaMetadata"
            :can-delete-media="capabilities.canDeleteMedia"
            :update-metadata-batch="
              canBatchUpdateMediaMetadata ? submitMediaMetadataBatch : undefined
            "
            :create-bucket="submitMediaBucketCreate"
            :update-bucket="submitMediaBucketUpdate"
            :delete-bucket="submitMediaBucketDelete"
            :move-media="submitMediaMove"
            :delete-media-batch="submitMediaBatchDelete"
            @updated="mergeUpdatedMedia"
            @upload-bucket="openBucketUpload"
            @drop-bucket="
              (files, bucketId, memoryId) => startDroppedMemoryUpload(files, memoryId, bucketId)
            "
          />
        </TabPanel>
        <TabPanel value="map" :pt="nonFocusableTabPanelPt">
          <MemoryContextPanel
            :tenant-name="tenantName"
            :media="visibleMedia"
            :tracks="visibleTracks"
            :can-edit-metadata="capabilities.canEditMediaMetadata"
            :can-delete-media="capabilities.canDeleteMedia"
            :can-edit-tracks="capabilities.canEditTracks"
            :update-captured-at="updateSingleMediaCapturedAt"
            :update-highlight="updateSingleMediaHighlight"
            :delete-media="submitMediaDelete"
            :upload-track="capabilities.canEditTracks ? submitTrackUpload : undefined"
            :update-track="capabilities.canEditTracks ? submitTrackUpdate : undefined"
            :delete-track="capabilities.canEditTracks ? submitTrackDelete : undefined"
            @deleted="markMediaDeleted"
            @changed="$emit('changed', memory)"
          />
        </TabPanel>
        <TabPanel value="playlists" :pt="nonFocusableTabPanelPt">
          <MemoryPlaylistsPanel
            :playlists="visiblePlaylists"
            :can-edit="capabilities.canEditPlaylists"
            :create-playlist="capabilities.canEditPlaylists ? submitPlaylistCreate : undefined"
            :update-playlist="capabilities.canEditPlaylists ? submitPlaylistUpdate : undefined"
            :delete-playlist="capabilities.canEditPlaylists ? submitPlaylistDelete : undefined"
            @changed="$emit('changed', memory)"
          />
        </TabPanel>
        <TabPanel value="journal" :pt="nonFocusableTabPanelPt">
          <section class="memory-journal">
            <div class="memory-journal-toolbar">
              <div>
                <h2>{{ t('memory.journal') }}</h2>
                <p>
                  {{
                    t('memory.journalUpdated', {
                      date: displayedJournal ? formatDate(displayedJournal.updatedAt) : '-'
                    })
                  }}
                </p>
              </div>
              <div class="memory-journal-actions">
                <Button
                  v-if="capabilities.canEditJournal && journalEditing"
                  type="button"
                  icon="pi pi-images"
                  :label="t('memory.journalInsertMedia')"
                  severity="secondary"
                  outlined
                  size="small"
                  @click="openMediaPicker()"
                />
                <Button
                  v-if="capabilities.canEditJournal && journalEditing && childMemories.length"
                  type="button"
                  icon="pi pi-sitemap"
                  :label="t('memory.journalInsertChapter')"
                  severity="secondary"
                  outlined
                  size="small"
                  @click="openJournalEmbedPicker('chapter')"
                />
                <Button
                  v-if="capabilities.canEditJournal && journalEditing && visiblePlaylists.length"
                  type="button"
                  icon="pi pi-volume-up"
                  :label="t('memory.journalInsertPlaylist')"
                  severity="secondary"
                  outlined
                  size="small"
                  @click="openJournalEmbedPicker('playlist')"
                />
                <Button
                  v-if="capabilities.canEditJournal && journalEditing && visibleTracks.length"
                  type="button"
                  icon="pi pi-map"
                  :label="t('memory.journalInsertTrack')"
                  severity="secondary"
                  outlined
                  size="small"
                  @click="openJournalEmbedPicker('track')"
                />
                <Button
                  v-if="capabilities.canEditJournal && !journalEditing"
                  type="button"
                  icon="pi pi-pencil"
                  :label="journalHasContent ? t('common.edit') : t('memory.journalStart')"
                  severity="secondary"
                  outlined
                  size="small"
                  @click="startJournalEdit"
                />
              </div>
            </div>

            <Message v-if="journalError" severity="error" :closable="false">{{
              journalError
            }}</Message>

            <div v-if="journalEditing" class="memory-journal-editor">
              <div class="memory-journal-editor-grid">
                <MemoryJournalLiveEditor
                  v-if="journalLiveSession"
                  ref="journalLiveEditor"
                  v-model="journalDraft"
                  :session="journalLiveSession"
                  :media="visibleMedia"
                  :tracks="visibleTracks"
                  :playlists="visiblePlaylists"
                  :child-memories="childMemories"
                  :placeholder="t('memory.journalPlaceholder')"
                  @status="handleJournalLiveStatus"
                  @error="journalError = $event"
                  @fallback="fallbackToRestJournalEditor"
                  @media-trigger="openMediaPicker"
                  @media-tag-edit="openMediaPicker"
                  @embed-trigger="openJournalEmbedPicker($event.kind, $event)"
                  @embed-tag-edit="openJournalEmbedPicker($event.kind, $event)"
                  @open-memory="$emit('openMemory', $event)"
                />
                <label v-else class="memory-journal-field">
                  <span>{{ t('memory.journalMarkdown') }}</span>
                  <textarea
                    ref="journalTextarea"
                    v-model="journalDraft"
                    rows="14"
                    :placeholder="t('memory.journalPlaceholder')"
                    @input="handleJournalInput"
                    @dblclick="handleJournalDoubleClick"
                  />
                </label>
                <aside class="memory-journal-help" :aria-label="t('memory.journalHelpTitle')">
                  <div>
                    <h3>{{ t('memory.journalHelpTitle') }}</h3>
                    <p>{{ t('memory.journalHelpCopy') }}</p>
                  </div>
                  <div>
                    <h4>{{ t('memory.journalHelpBasics') }}</h4>
                    <ul>
                      <li>
                        <code># {{ t('memory.journalHelpHeadingExample') }}</code>
                      </li>
                      <li>
                        <code>**{{ t('memory.journalHelpStrongExample') }}**</code>
                      </li>
                      <li>
                        <code>- {{ t('memory.journalHelpListExample') }}</code>
                      </li>
                      <li>
                        <code>[{{ t('memory.journalHelpLinkLabel') }}](https://example.com)</code>
                      </li>
                    </ul>
                  </div>
                  <div>
                    <h4>{{ t('memory.journalHelpEmbeds') }}</h4>
                    <dl>
                      <div class="memory-journal-help-row">
                        <dt>{{ t('memory.journalHelpMedia') }}</dt>
                        <dd><code>::media:mediaId Caption</code></dd>
                      </div>
                      <div class="memory-journal-help-row">
                        <dt>{{ t('memory.journalHelpCarousel') }}</dt>
                        <dd><code>::media:media1,media2 Caption</code></dd>
                      </div>
                      <div class="memory-journal-help-row">
                        <dt>{{ t('memory.journalHelpMemory') }}</dt>
                        <dd><code>::chapter:chapterId Title</code></dd>
                      </div>
                      <div class="memory-journal-help-row">
                        <dt>{{ t('memory.journalHelpPlaylist') }}</dt>
                        <dd><code>::playlist:playlistId Playlist</code></dd>
                      </div>
                      <div class="memory-journal-help-row">
                        <dt>{{ t('memory.journalHelpTrack') }}</dt>
                        <dd><code>::track:trackId Track</code></dd>
                      </div>
                    </dl>
                    <p>{{ t('memory.journalHelpMediaEdit') }}</p>
                  </div>
                </aside>
              </div>
              <Message v-if="journalLiveStatus === 'fallback'" severity="warn" :closable="false">
                {{ t('memory.journalLiveFallback') }}
              </Message>
              <div v-if="!journalLiveSession" class="memory-journal-preview">
                <JournalRenderer
                  v-if="journalDraft.trim()"
                  :tenant-name="tenantName"
                  :markdown="journalDraft"
                  :media="visibleMedia"
                  :tracks="visibleTracks"
                  :playlists="visiblePlaylists"
                  :child-memories="childMemories"
                  @open-memory="$emit('openMemory', $event)"
                />
                <p v-else class="veyq-empty">{{ t('memory.journalEmptyPreview') }}</p>
              </div>
              <div class="memory-journal-editor-actions">
                <Button
                  v-if="!journalLiveSession"
                  type="button"
                  icon="pi pi-check"
                  :label="t('common.save')"
                  :loading="journalSaving"
                  :disabled="!props.saveJournal"
                  @click="submitJournal"
                />
                <Button
                  type="button"
                  icon="pi pi-times"
                  :label="t('common.cancel')"
                  severity="secondary"
                  outlined
                  :disabled="journalSaving"
                  @click="cancelJournalEdit"
                />
              </div>
            </div>

            <JournalRenderer
              v-else-if="journalHasContent"
              :tenant-name="tenantName"
              :markdown="journalMarkdown"
              :media="visibleMedia"
              :tracks="visibleTracks"
              :playlists="visiblePlaylists"
              :child-memories="childMemories"
              @open-memory="$emit('openMemory', $event)"
            />

            <div v-else class="memory-journal-empty">
              <span class="pi pi-book" />
              <p>{{ t('memory.journalEmpty') }}</p>
              <Button
                v-if="capabilities.canEditJournal"
                type="button"
                icon="pi pi-pencil"
                :label="t('memory.journalStart')"
                severity="secondary"
                outlined
                size="small"
                @click="startJournalEdit"
              />
            </div>
          </section>
        </TabPanel>
        <TabPanel value="children" :pt="nonFocusableTabPanelPt">
          <div class="memory-card-grid memory-card-grid-memories">
            <div
              v-for="child in childMemories"
              :key="child.id"
              class="memory-child-drop-target"
              :class="{ 'memory-drop-target-active': childDropMemoryId === child.id }"
              @dragenter="activateChildDrop($event, child.id)"
              @dragover="allowMediaDrop"
              @dragleave="deactivateChildDrop(child.id)"
              @drop="handleChildDrop($event, child)"
            >
              <MemoryCard :memory="child" @open="$emit('openMemory', $event)" />
              <div
                v-if="capabilities.canUpload && childDropMemoryId === child.id"
                class="memory-child-drop-hint"
              >
                <span class="pi pi-cloud-upload" />
                <span>{{ t('media.dropzoneChapterTitle') }}</span>
              </div>
            </div>
            <button
              v-if="capabilities.canCreateChildMemory"
              class="veyq-card veyq-card-interactive memory-create-card memory-create-card-memory"
              type="button"
              @click="$emit('createChildMemory')"
            >
              <span class="pi pi-plus" />
              <strong>{{ t('memory.childCreate') }}</strong>
            </button>
          </div>
          <div
            v-if="childMemories.length === 0 && !capabilities.canCreateChildMemory"
            class="veyq-empty"
          >
            <span class="pi pi-sitemap" />
            <span>{{ t('memory.childrenEmpty') }}</span>
          </div>
        </TabPanel>
        <TabPanel value="info" :pt="nonFocusableTabPanelPt">
          <dl class="memory-info-list">
            <dt>{{ t('memory.dateRange') }}</dt>
            <dd>{{ dateRangeLabel }}</dd>
            <dt>{{ t('common.permalink') }}</dt>
            <dd>{{ detailHeader.path }}</dd>
            <dt>{{ t('memory.mediaCount') }}</dt>
            <dd>{{ localTotalMediaCount }}</dd>
            <template v-if="detailHeader.childMemoryCount > 0">
              <dt>{{ t('memory.children') }}</dt>
              <dd>{{ detailHeader.childMemoryCount }}</dd>
            </template>
          </dl>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <EditMemoryDialog
      v-model:visible="editOpen"
      :tenant-name="tenantName"
      :memory="memory"
      :save-memory="saveMemory"
      @saved="$emit('changed', $event)"
    />
    <MemoryCoverDialog
      v-model:visible="coverOpen"
      :tenant-name="tenantName"
      :memory="memory"
      :media="visibleMedia"
      :set-cover-override="setCover"
      :remove-cover-override="removeCover"
      @saved="$emit('changed', $event)"
    />
    <MemoryParticipantsDialog
      v-model:visible="participantsOpen"
      :tenant-name="tenantName"
      :memory-id="memory.id"
      :participants="detailHeader.participants"
      @saved="$emit('changed', memory)"
    />
    <DeleteMemoryDialog
      v-model:visible="deleteOpen"
      :tenant-name="tenantName"
      :memory="memory"
      :delete-memory-override="deleteMemory"
      @deleted="$emit('deleted', parentPath)"
    />
    <MemoryShareDialog v-model:visible="shareOpen" :tenant-name="tenantName" :memory="memory" />
    <MemoryUploaderShareDialog
      v-model:visible="uploaderShareOpen"
      :tenant-name="tenantName"
      :memory="memory"
    />
    <MemoryUploadDialog
      v-model:visible="uploadOpen"
      :tenant-name="tenantName"
      :memory-id="uploadMemoryId || memory.id"
      :bucket-id="uploadBucketId"
      :upload-media="uploadMedia"
      @uploaded="addUploadedMedia"
      @saved="$emit('changed', memory)"
    />
    <Dialog
      v-model:visible="downloadOpen"
      modal
      :header="t('memory.download')"
      class="memory-download-dialog"
    >
      <div class="memory-download-options">
        <a class="memory-download-option" :href="directDownloadUrl" @click="downloadOpen = false">
          <span class="pi pi-download" />
          <span>
            <strong>{{ t('memory.downloadDirect') }}</strong>
            <small>{{
              t('memory.downloadDirectCopy', { count: detailHeader.directMediaCount })
            }}</small>
          </span>
        </a>
        <a class="memory-download-option" :href="subtreeDownloadUrl" @click="downloadOpen = false">
          <span class="pi pi-sitemap" />
          <span>
            <strong>{{ t('memory.downloadSubtree') }}</strong>
            <small>{{ t('memory.downloadSubtreeCopy', { count: totalMediaCount }) }}</small>
          </span>
        </a>
      </div>
    </Dialog>
    <JournalMediaPickerDialog
      v-model:visible="mediaPickerOpen"
      :media="visibleMedia"
      :caption="mediaPickerCaption"
      :selected-ids="selectedJournalMediaIds"
      :can-delete="canDeleteCurrentMediaEmbed"
      @update:caption="mediaPickerCaption = $event"
      @caption-input="mediaPickerCaptionEdited = true"
      @toggle="toggleJournalMedia"
      @cancel="closeMediaPicker"
      @confirm="insertSelectedJournalMedia"
      @delete="deleteSelectedJournalMediaEmbed"
    />
    <div
      v-if="embedTypePickerOpen"
      ref="embedTypePicker"
      class="memory-journal-type-picker"
      role="listbox"
      tabindex="-1"
      :style="embedTypePickerStyle"
      :aria-label="t('memory.journalEmbedTypePicker')"
      :aria-activedescendant="`journal-embed-type-${journalEmbedTypeOptions[embedTypeActiveIndex]?.kind ?? 'none'}`"
      @keydown="handleJournalEmbedTypeKeydown"
    >
      <button
        v-for="(option, index) in journalEmbedTypeOptions"
        :id="`journal-embed-type-${option.kind}`"
        :key="option.kind"
        type="button"
        class="memory-journal-type-option"
        :class="{ 'memory-journal-type-option-active': embedTypeActiveIndex === index }"
        :disabled="option.disabled"
        tabindex="-1"
        :aria-selected="embedTypeActiveIndex === index"
        @mouseenter="embedTypeActiveIndex = index"
        @click="selectJournalEmbedType(option.kind)"
      >
        <span :class="option.icon" />
        <span>{{ option.label }}</span>
      </button>
      <button
        type="button"
        class="memory-journal-type-option memory-journal-type-option-secondary"
        tabindex="-1"
        @click="closeJournalEmbedTypePicker"
      >
        <span class="pi pi-times" />
        <span>{{ t('common.cancel') }}</span>
      </button>
    </div>
    <JournalEmbedPickerDialog
      v-model:visible="embedPickerOpen"
      :title="journalEmbedPickerTitle"
      :copy="journalEmbedPickerCopy"
      :empty="journalEmbedPickerEmpty"
      :confirm-label="journalEmbedPickerConfirmLabel"
      :field-label="t('memory.journalEmbedLabel')"
      :embed-label="embedPickerLabel"
      :label-placeholder="t('memory.journalEmbedLabelPlaceholder')"
      :selected-ids="selectedJournalEmbedIds"
      :items="journalEmbedPickerItems"
      :multiple="embedPickerKind === 'track'"
      :can-delete="canDeleteCurrentJournalEmbed"
      @update:embed-label="embedPickerLabel = $event"
      @label-input="embedPickerLabelEdited = true"
      @select="selectJournalEmbed"
      @cancel="closeJournalEmbedPicker"
      @confirm="insertSelectedJournalEmbed"
      @delete="deleteSelectedJournalEmbed"
    />
  </div>
</template>
