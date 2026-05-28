<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
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
  deleteMemoryMedia as deleteTenantMemoryMedia,
  favoriteMemory,
  unfavoriteMemory,
  updateMemoryMediaMetadata,
  updateMemoryMediaMetadataBatch as updateTenantMediaMetadataBatch,
  uploadMemoryTrack as uploadTenantMemoryTrack,
  updateMemoryTrack as updateTenantMemoryTrack,
  deleteMemoryTrack as deleteTenantMemoryTrack,
  createMemoryPlaylist as createTenantMemoryPlaylist,
  updateMemoryPlaylist as updateTenantMemoryPlaylist,
  deleteMemoryPlaylist as deleteTenantMemoryPlaylist
} from '../api'
import { notifyMemoryFavoritesChanged } from '../composables/useMemoryFavoritesRefresh'
import type {
  Memory,
  MemoryHeader,
  MemoryJournal,
  MemoryMedia,
  MemoryPlaylist,
  MemoryTrack,
  CreateMemoryPlaylistRequest,
  SetMemoryCoverRequest,
  UpdateMemoryMediaMetadataBatchRequest,
  UpdateMemoryJournalRequest,
  UpdateMemoryMediaMetadataRequest,
  UpdateMemoryPlaylistRequest,
  UpdateMemoryTrackRequest,
  UpdateMemoryRequest,
  UploadMediaOptions,
  UploadMemoryMediaRequest,
  UploadMemoryTrackRequest
} from '../api'
import DeleteMemoryDialog from './DeleteMemoryDialog.vue'
import EditMemoryDialog from './EditMemoryDialog.vue'
import JournalMediaPickerDialog from './JournalMediaPickerDialog.vue'
import JournalRenderer from './JournalRenderer.vue'
import MemoryAssetBuckets from './MemoryAssetBuckets.vue'
import MemoryCard from './MemoryCard.vue'
import MemoryCoverImage from './MemoryCoverImage.vue'
import MemoryCoverDialog from './MemoryCoverDialog.vue'
import MemoryHeaderPlaylistPlayer from './MemoryHeaderPlaylistPlayer.vue'
import MemoryParticipantsDialog from './MemoryParticipantsDialog.vue'
import MemoryParticipantsStrip from './MemoryParticipantsStrip.vue'
import MemoryPlaylistsPanel from './MemoryPlaylistsPanel.vue'
import MemoryShareDialog from './MemoryShareDialog.vue'
import MemoryTracksPanel from './MemoryTracksPanel.vue'
import MemoryUploaderShareDialog from './MemoryUploaderShareDialog.vue'
import MemoryUploadDialog from './MemoryUploadDialog.vue'
import { DEFAULT_MEMORY_DETAIL_TAB, type MemoryDetailTab } from '../utils/memoryTabs'

const MemoryMediaMap = defineAsyncComponent(() => import('./MemoryMediaMap.vue'))

type MemoryDetailCapabilities = {
  canEdit?: boolean
  canUpload?: boolean
  canCreateChildMemory?: boolean
  canDelete?: boolean
  canShare?: boolean
  canManageCover?: boolean
  canEditMediaMetadata?: boolean
  canDeleteMedia?: boolean
  canDeleteCurrentMemory?: boolean
  canEditJournal?: boolean
  canDownload?: boolean
  canEditTracks?: boolean
  canEditPlaylists?: boolean
  canFavorite?: boolean
  canManageParticipants?: boolean
}

type MediaInsertRange = {
  start: number
  end: number
  caption?: string
  mediaIds?: string[]
}

const props = defineProps<{
  tenantName: string
  memory: Memory
  header?: MemoryHeader | null
  journal?: MemoryJournal | null
  childMemories: Memory[]
  media: MemoryMedia[]
  mediaLoading?: boolean
  playlists?: MemoryPlaylist[]
  tracks?: MemoryTrack[]
  activeTab?: MemoryDetailTab
  capabilities?: MemoryDetailCapabilities
  saveMemory?: (memoryId: string, request: UpdateMemoryRequest) => Promise<Memory>
  saveJournal?: (memoryId: string, request: UpdateMemoryJournalRequest) => Promise<MemoryJournal>
  setCover?: (memoryId: string, request: SetMemoryCoverRequest) => Promise<Memory>
  removeCover?: (memoryId: string) => Promise<Memory>
  uploadMedia?: (
    memoryId: string,
    request: UploadMemoryMediaRequest,
    options?: UploadMediaOptions
  ) => Promise<MemoryMedia>
  deleteMemory?: (memoryId: string) => Promise<void>
  updateMediaMetadata?: (
    mediaId: string,
    memoryId: string,
    request: UpdateMemoryMediaMetadataRequest
  ) => Promise<MemoryMedia>
  updateMediaMetadataBatch?: (
    memoryId: string,
    request: UpdateMemoryMediaMetadataBatchRequest
  ) => Promise<MemoryMedia[]>
  deleteMedia?: (mediaId: string, memoryId: string) => Promise<void>
  uploadTrack?: (memoryId: string, request: UploadMemoryTrackRequest) => Promise<MemoryTrack>
  updateTrack?: (trackId: string, memoryId: string, request: UpdateMemoryTrackRequest) => Promise<MemoryTrack>
  deleteTrack?: (trackId: string, memoryId: string) => Promise<void>
  createPlaylist?: (memoryId: string, request: CreateMemoryPlaylistRequest) => Promise<MemoryPlaylist>
  updatePlaylist?: (playlistId: string, memoryId: string, request: UpdateMemoryPlaylistRequest) => Promise<MemoryPlaylist>
  deletePlaylist?: (playlistId: string, memoryId: string) => Promise<void>
  downloadUrl?: (memoryId: string, includeChildren: boolean) => string
}>()
const { locale, t } = useI18n()

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
const downloadOpen = ref(false)
const actionMenu = ref<InstanceType<typeof Menu> | null>(null)
const secondaryActionMenu = ref<InstanceType<typeof Menu> | null>(null)
const favoriteSaving = ref(false)
const favoriteOverride = ref<boolean | null>(null)
const mediaOverrides = ref<Record<string, MemoryMedia>>({})
const uploadedMedia = ref<Record<string, MemoryMedia>>({})
const deletedMediaIds = ref<Set<string>>(new Set())
const journalEditing = ref(false)
const journalSaving = ref(false)
const journalDraft = ref('')
const journalError = ref('')
const journalTextarea = ref<HTMLTextAreaElement | null>(null)
const mediaPickerOpen = ref(false)
const mediaPickerCaption = ref('')
const mediaPickerCaptionEdited = ref(false)
const selectedJournalMediaIds = ref<string[]>([])
const mediaInsertRange = ref<MediaInsertRange | null>(null)
const lastMediaTriggerPosition = ref<number | null>(null)
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
  canDeleteCurrentMemory: props.capabilities?.canDeleteCurrentMemory ?? true,
  canEditJournal: props.capabilities?.canEditJournal ?? props.capabilities?.canEdit ?? true,
  canDownload: props.capabilities?.canDownload ?? true,
  canEditTracks: props.capabilities?.canEditTracks ?? props.capabilities?.canEdit ?? true,
  canEditPlaylists: props.capabilities?.canEditPlaylists ?? props.capabilities?.canEdit ?? true,
  canFavorite: props.capabilities?.canFavorite ?? true,
  canManageParticipants: props.capabilities?.canManageParticipants ?? props.capabilities?.canEdit ?? true
}))

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
  if (detailHeader.value.startDate === detailHeader.value.endDate) return formatDate(detailHeader.value.startDate)
  return `${formatDate(detailHeader.value.startDate)} - ${formatDate(detailHeader.value.endDate)}`
})
const totalMediaCount = computed(
  () => detailHeader.value.directMediaCount + detailHeader.value.descendantMediaCount
)
const uploadedMediaList = computed(() =>
  Object.values(uploadedMedia.value).filter((media) =>
    !props.media.some((item) => item.id === media.id) && !deletedMediaIds.value.has(media.id)
  )
)
const deletedVisibleMediaCount = computed(() =>
  props.media.filter((media) => deletedMediaIds.value.has(media.id)).length
)
const localTotalMediaCount = computed(() =>
  Math.max(0, totalMediaCount.value - deletedVisibleMediaCount.value + uploadedMediaList.value.length)
)
const mediaCountLabel = computed(() => `${localTotalMediaCount.value} ${t('memory.mediaCount')}`)
const visibleMedia = computed(() => {
  const merged = [...props.media, ...uploadedMediaList.value].filter((media) => !deletedMediaIds.value.has(media.id))
  return merged.map((media) => mediaOverrides.value[media.id] || media)
})
const visiblePlaylists = computed(() => props.playlists ?? [])
const visibleTracks = computed(() => props.tracks ?? [])
const canDownloadMemory = computed(() =>
  capabilities.value.canDownload && totalMediaCount.value > 0 && Boolean(props.downloadUrl || props.tenantName)
)
const directDownloadUrl = computed(() => memoryDownloadUrl(false))
const subtreeDownloadUrl = computed(() => memoryDownloadUrl(true))
const canBatchUpdateMediaMetadata = computed(() => Boolean(props.updateMediaMetadataBatch || props.tenantName))
const journalMarkdown = computed(() => props.journal?.markdown ?? '')
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
  () => props.memory.id,
  () => {
    favoriteOverride.value = null
    uploadedMedia.value = {}
    mediaOverrides.value = {}
    deletedMediaIds.value = new Set()
  }
)

function formatDate(value: string): string {
  return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'de-DE', { dateStyle: 'medium' }).format(new Date(value))
}

function memoryDownloadUrl(includeChildren: boolean): string {
  if (props.downloadUrl) return props.downloadUrl(props.memory.id, includeChildren)
  const tenant = encodeURIComponent(props.tenantName)
  const memoryId = encodeURIComponent(props.memory.id)
  return `${API_BASE}/api/${tenant}/memories/${memoryId}/download?includeChildren=${includeChildren}&locale=${encodeURIComponent(locale.value)}`
}

async function updateSingleMediaMetadata(
  mediaId: string,
  memoryId: string,
  request: UpdateMemoryMediaMetadataRequest
): Promise<MemoryMedia> {
  const updated = props.updateMediaMetadata
    ? await props.updateMediaMetadata(mediaId, memoryId, request)
    : await updateMemoryMediaMetadata(props.tenantName, memoryId, mediaId, request)
  mergeUpdatedMedia([updated])
  return updated
}

async function submitMediaMetadataBatch(
  memoryId: string,
  mediaIds: string[],
  capturedAtOffsetSeconds: number
): Promise<MemoryMedia[]> {
  const request = { mediaIds, capturedAtOffsetSeconds }
  if (!props.updateMediaMetadataBatch && !props.tenantName) throw new Error(t('media.batchSaveFailed'))
  const updated = props.updateMediaMetadataBatch
    ? await props.updateMediaMetadataBatch(memoryId, request)
    : await updateTenantMediaMetadataBatch(props.tenantName, memoryId, request)
  mergeUpdatedMedia(updated)
  return updated
}

function mergeUpdatedMedia(updated: MemoryMedia[]) {
  mediaOverrides.value = {
    ...mediaOverrides.value,
    ...Object.fromEntries(updated.map((media) => [media.id, media]))
  }
}

function addUploadedMedia(media: MemoryMedia) {
  if (media.memoryId !== props.memory.id) return
  deletedMediaIds.value = new Set([...deletedMediaIds.value].filter((mediaId) => mediaId !== media.id))
  uploadedMedia.value = { ...uploadedMedia.value, [media.id]: media }
}

async function submitMediaDelete(mediaId: string, memoryId: string): Promise<void> {
  if (props.deleteMedia) {
    await props.deleteMedia(mediaId, memoryId)
  } else {
    await deleteTenantMemoryMedia(props.tenantName, memoryId, mediaId)
  }
}

function markMediaDeleted(media: MemoryMedia) {
  deletedMediaIds.value = new Set([...deletedMediaIds.value, media.id])
  uploadedMedia.value = Object.fromEntries(
    Object.entries(uploadedMedia.value).filter(([mediaId]) => mediaId !== media.id)
  )
  mediaOverrides.value = Object.fromEntries(
    Object.entries(mediaOverrides.value).filter(([mediaId]) => mediaId !== media.id)
  )
  emit('mediaDeleted', media)
}

function submitTrackUpload(request: UploadMemoryTrackRequest): Promise<MemoryTrack> {
  if (props.uploadTrack) return props.uploadTrack(props.memory.id, request)
  return uploadTenantMemoryTrack(props.tenantName, props.memory.id, request)
}

function submitTrackUpdate(trackId: string, request: UpdateMemoryTrackRequest): Promise<MemoryTrack> {
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

function submitPlaylistUpdate(playlistId: string, request: UpdateMemoryPlaylistRequest): Promise<MemoryPlaylist> {
  if (props.updatePlaylist) return props.updatePlaylist(playlistId, props.memory.id, request)
  return updateTenantMemoryPlaylist(props.tenantName, props.memory.id, playlistId, request)
}

function submitPlaylistDelete(playlistId: string): Promise<void> {
  if (props.deletePlaylist) return props.deletePlaylist(playlistId, props.memory.id)
  return deleteTenantMemoryPlaylist(props.tenantName, props.memory.id, playlistId)
}

function startJournalEdit() {
  journalDraft.value = journalMarkdown.value
  journalError.value = ''
  journalEditing.value = true
}

function cancelJournalEdit() {
  journalDraft.value = journalMarkdown.value
  journalError.value = ''
  journalEditing.value = false
  closeMediaPicker()
}

async function submitJournal() {
  if (!props.saveJournal) return
  journalSaving.value = true
  journalError.value = ''
  try {
    await props.saveJournal(props.memory.id, { markdown: journalDraft.value })
    journalEditing.value = false
    emit('changed', props.memory)
  } catch {
    journalError.value = t('memory.journalSaveFailed')
  } finally {
    journalSaving.value = false
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
    emit('changed', updated)
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
  mediaInsertRange.value = range ?? currentTextareaRange()
  selectedJournalMediaIds.value = range?.mediaIds?.filter((id) => visibleMedia.value.some((media) => media.id === id)) ?? []
  mediaPickerCaption.value = range?.caption ?? ''
  mediaPickerCaptionEdited.value = Boolean(range?.caption)
  mediaPickerOpen.value = true
}

function closeMediaPicker() {
  mediaPickerOpen.value = false
  selectedJournalMediaIds.value = []
  mediaPickerCaption.value = ''
  mediaPickerCaptionEdited.value = false
  mediaInsertRange.value = null
}

function currentTextareaRange(): MediaInsertRange {
  const textarea = journalTextarea.value
  const position = textarea?.selectionStart ?? journalDraft.value.length
  return { start: position, end: textarea?.selectionEnd ?? position }
}

function syncMediaPickerCaption() {
  if (mediaPickerCaptionEdited.value) return
  if (selectedJournalMediaIds.value.length === 1) {
    mediaPickerCaption.value = visibleMedia.value.find((media) => media.id === selectedJournalMediaIds.value[0])?.title || ''
  } else if (selectedJournalMediaIds.value.length > 1) {
    mediaPickerCaption.value = t('memory.journalMediaCarouselCaption')
  } else {
    mediaPickerCaption.value = ''
  }
}

function insertSelectedJournalMedia() {
  if (selectedJournalMediaIds.value.length === 0) return
  const range = mediaInsertRange.value ?? currentTextareaRange()
  const caption = mediaPickerCaption.value.trim() || (
    selectedJournalMediaIds.value.length === 1
      ? visibleMedia.value.find((media) => media.id === selectedJournalMediaIds.value[0])?.title || 'Caption'
      : t('memory.journalMediaCarouselCaption')
  )
  const markdown = `![${caption}](${`veyq:media/${selectedJournalMediaIds.value.join(',')}`})`
  journalDraft.value = `${journalDraft.value.slice(0, range.start)}${markdown}${journalDraft.value.slice(range.end)}`
  const nextCursor = range.start + markdown.length
  closeMediaPicker()
  window.setTimeout(() => {
    journalTextarea.value?.focus()
    journalTextarea.value?.setSelectionRange(nextCursor, nextCursor)
  })
}

function handleJournalInput(event: Event) {
  const textarea = event.target as HTMLTextAreaElement
  const position = textarea.selectionStart
  const range = mediaTriggerRange(journalDraft.value, position)
  if (!range) return
  if (lastMediaTriggerPosition.value === position) return
  lastMediaTriggerPosition.value = position
  openMediaPicker(range)
}

function handleJournalDoubleClick(event: MouseEvent) {
  const textarea = event.target as HTMLTextAreaElement
  const range = mediaTagRangeAtPosition(journalDraft.value, textarea.selectionStart)
  if (range) openMediaPicker(range)
}

function mediaTriggerRange(markdown: string, position: number): MediaInsertRange | null {
  const beforeCursor = markdown.slice(0, position)
  const match = beforeCursor.match(/(!\[([^\]\n]*)]\()?veyq:media\/$/)
  if (!match) return null
  const end = markdown[position] === ')' ? position + 1 : position
  return {
    start: position - match[0].length,
    end,
    caption: match[2] || undefined
  }
}

function mediaTagRangeAtPosition(markdown: string, position: number): MediaInsertRange | null {
  const mediaTag = /!\[([^\]\n]*)]\(veyq:media\/([^)]+)\)/g
  for (const match of markdown.matchAll(mediaTag)) {
    const start = match.index ?? 0
    const end = start + match[0].length
    if (position < start || position > end) continue
    const mediaIds = match[2].split(',').map((id) => id.trim()).filter(Boolean)
    if (mediaIds.length === 0) return null
    return {
      start,
      end,
      caption: match[1] || undefined,
      mediaIds
    }
  }
  return null
}

</script>

<template>
  <div class="memory-detail">
    <header class="memory-detail-header">
      <div class="memory-detail-cover" :class="{ 'memory-detail-cover-has-image': Boolean(coverUrl) }">
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
                class="memory-icon-action memory-action-inline"
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
                icon="pi pi-upload"
                :label="t('memory.upload')"
                class="memory-upload-action"
                size="small"
                @click="uploadOpen = true"
              />
              <MemoryHeaderPlaylistPlayer
                v-if="visiblePlaylists.length > 0"
                :playlists="visiblePlaylists"
              />
              <Button
                v-if="canDownloadMemory"
                v-tooltip.bottom="t('memory.download')"
                class="memory-icon-action memory-action-inline memory-action-collapsible"
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
                class="memory-icon-action memory-action-inline memory-action-collapsible"
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
                class="memory-icon-action memory-action-inline memory-action-collapsible"
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
                class="memory-icon-action memory-action-inline memory-action-collapsible"
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
                class="memory-icon-action memory-action-inline memory-action-collapsible"
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
                class="memory-icon-action memory-action-inline memory-action-collapsible"
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
                class="memory-icon-action memory-action-inline memory-action-collapsible"
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
                class="memory-icon-action memory-actions-overflow memory-actions-overflow-secondary"
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
                class="memory-icon-action memory-actions-overflow memory-actions-overflow-mobile"
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
            <i class="pi pi-sitemap" /> {{ detailHeader.childMemoryCount }} {{ t('memory.children') }}
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
        <Tab value="buckets">{{ t('media.buckets') }}</Tab>
        <Tab value="map">{{ t('map.title') }}</Tab>
        <Tab value="tracks">{{ t('track.title') }}</Tab>
        <Tab value="playlists">{{ t('playlist.title') }}</Tab>
        <Tab value="journal">{{ t('memory.journal') }}</Tab>
        <Tab value="children">{{ t('memory.children') }}</Tab>
        <Tab value="info">{{ t('memory.info') }}</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="gallery">
          <div v-if="visibleMedia.length === 0 && !mediaLoading" class="memory-empty memory-empty-action">
            <span class="pi pi-images" />
            <span>{{ t('memory.galleryEmptyAction') }}</span>
            <Button
              v-if="capabilities.canUpload"
              icon="pi pi-upload"
              :label="t('memory.upload')"
              size="small"
              @click="uploadOpen = true"
            />
          </div>
          <MediaGrid
            v-else
            :tenant-name="tenantName"
            :media="visibleMedia"
            :streaming="Boolean(mediaLoading)"
            :can-edit-metadata="capabilities.canEditMediaMetadata"
            :can-delete-media="capabilities.canDeleteMedia"
            :update-metadata="updateSingleMediaMetadata"
            :delete-media="submitMediaDelete"
            @deleted="markMediaDeleted"
          />
        </TabPanel>
        <TabPanel value="buckets">
          <MemoryAssetBuckets
            :tenant-name="tenantName"
            :memory-id="memory.id"
            :media="visibleMedia"
            :update-metadata-batch="canBatchUpdateMediaMetadata ? submitMediaMetadataBatch : undefined"
            @updated="mergeUpdatedMedia"
          />
        </TabPanel>
        <TabPanel value="map">
          <MemoryMediaMap
            :tenant-name="tenantName"
            :media="visibleMedia"
            :can-edit-metadata="capabilities.canEditMediaMetadata"
            :can-delete-media="capabilities.canDeleteMedia"
            :update-metadata="updateSingleMediaMetadata"
            :delete-media="submitMediaDelete"
            @deleted="markMediaDeleted"
          />
        </TabPanel>
        <TabPanel value="tracks">
          <MemoryTracksPanel
            :tracks="visibleTracks"
            :can-edit="capabilities.canEditTracks"
            :upload-track="capabilities.canEditTracks ? submitTrackUpload : undefined"
            :update-track="capabilities.canEditTracks ? submitTrackUpdate : undefined"
            :delete-track="capabilities.canEditTracks ? submitTrackDelete : undefined"
            @changed="$emit('changed', memory)"
          />
        </TabPanel>
        <TabPanel value="playlists">
          <MemoryPlaylistsPanel
            :playlists="visiblePlaylists"
            :can-edit="capabilities.canEditPlaylists"
            :create-playlist="capabilities.canEditPlaylists ? submitPlaylistCreate : undefined"
            :update-playlist="capabilities.canEditPlaylists ? submitPlaylistUpdate : undefined"
            :delete-playlist="capabilities.canEditPlaylists ? submitPlaylistDelete : undefined"
            @changed="$emit('changed', memory)"
          />
        </TabPanel>
        <TabPanel value="journal">
          <section class="memory-journal">
            <div class="memory-journal-toolbar">
              <div>
                <h2>{{ t('memory.journal') }}</h2>
                <p>{{ t('memory.journalUpdated', { date: props.journal ? formatDate(props.journal.updatedAt) : '-' }) }}</p>
              </div>
              <div class="memory-journal-actions">
                <Button
                  v-if="capabilities.canEditJournal && journalEditing"
                  icon="pi pi-images"
                  :label="t('memory.journalInsertMedia')"
                  severity="secondary"
                  outlined
                  size="small"
                  @click="openMediaPicker()"
                />
                <Button
                  v-if="capabilities.canEditJournal && !journalEditing"
                  icon="pi pi-pencil"
                  :label="journalHasContent ? t('common.edit') : t('memory.journalStart')"
                  severity="secondary"
                  outlined
                  size="small"
                  @click="startJournalEdit"
                />
              </div>
            </div>

            <Message v-if="journalError" severity="error" :closable="false">{{ journalError }}</Message>

            <div v-if="journalEditing" class="memory-journal-editor">
              <div class="memory-journal-editor-grid">
                <label class="memory-journal-field">
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
                      <li><code># {{ t('memory.journalHelpHeadingExample') }}</code></li>
                      <li><code>**{{ t('memory.journalHelpStrongExample') }}**</code></li>
                      <li><code>- {{ t('memory.journalHelpListExample') }}</code></li>
                      <li><code>[{{ t('memory.journalHelpLinkLabel') }}](https://example.com)</code></li>
                    </ul>
                  </div>
                  <div>
                    <h4>{{ t('memory.journalHelpEmbeds') }}</h4>
                    <dl>
                      <dt>{{ t('memory.journalHelpMedia') }}</dt>
                      <dd><code>![Caption](veyq:media/mediaId)</code></dd>
                      <dt>{{ t('memory.journalHelpCarousel') }}</dt>
                      <dd><code>![Caption](veyq:media/media1,media2)</code></dd>
                      <dt>{{ t('memory.journalHelpMemory') }}</dt>
                      <dd><code>[Title](veyq:memory/memoryId)</code></dd>
                      <dt>{{ t('memory.journalHelpMemoryPath') }}</dt>
                      <dd><code>[Title](veyq:memory-path/Day%202)</code></dd>
                      <dt>{{ t('memory.journalHelpPlaylist') }}</dt>
                      <dd><code>[Playlist](veyq:playlist/{playlistId})</code></dd>
                      <dt>{{ t('memory.journalHelpMap') }}</dt>
                      <dd><code>[Place](veyq:map?lat=52.5&amp;lng=13.4&amp;zoom=12)</code></dd>
                      <dt>{{ t('memory.journalHelpTrack') }}</dt>
                      <dd><code>[Track](veyq:track/{trackId})</code></dd>
                    </dl>
                    <p>{{ t('memory.journalHelpMediaEdit') }}</p>
                  </div>
                </aside>
              </div>
              <div class="memory-journal-preview">
                <JournalRenderer
                  v-if="journalDraft.trim()"
                  :markdown="journalDraft"
                  :media="visibleMedia"
                  :tracks="visibleTracks"
                  :playlists="visiblePlaylists"
                  :child-memories="childMemories"
                  @open-memory="$emit('openMemory', $event)"
                />
                <p v-else class="memory-empty">{{ t('memory.journalEmptyPreview') }}</p>
              </div>
              <div class="memory-journal-editor-actions">
                <Button
                  icon="pi pi-check"
                  :label="t('common.save')"
                  :loading="journalSaving"
                  :disabled="!props.saveJournal"
                  @click="submitJournal"
                />
                <Button
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
        <TabPanel value="children">
          <div class="memory-card-grid memory-card-grid-memories">
            <MemoryCard
              v-for="child in childMemories"
              :key="child.id"
              :memory="child"
              @open="$emit('openMemory', $event)"
            />
            <button
              v-if="capabilities.canCreateChildMemory"
              class="memory-create-card"
              type="button"
              @click="$emit('createChildMemory')"
            >
              <span class="pi pi-plus" />
              <strong>{{ t('memory.childCreate') }}</strong>
            </button>
          </div>
          <div v-if="childMemories.length === 0 && !capabilities.canCreateChildMemory" class="memory-empty">
            <span class="pi pi-sitemap" />
            <span>{{ t('memory.childrenEmpty') }}</span>
          </div>
        </TabPanel>
        <TabPanel value="info">
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
    <MemoryUploaderShareDialog v-model:visible="uploaderShareOpen" :tenant-name="tenantName" :memory="memory" />
    <MemoryUploadDialog
      v-model:visible="uploadOpen"
      :tenant-name="tenantName"
      :memory-id="memory.id"
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
            <small>{{ t('memory.downloadDirectCopy', { count: detailHeader.directMediaCount }) }}</small>
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
      @update:caption="mediaPickerCaption = $event"
      @caption-input="mediaPickerCaptionEdited = true"
      @toggle="toggleJournalMedia"
      @cancel="closeMediaPicker"
      @confirm="insertSelectedJournalMedia"
    />
  </div>
</template>
