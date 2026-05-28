<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import type { MemoryMedia, MemoryMediaBucket } from '../api'
import { updateMemoryMediaMetadataBatch } from '../api'
import { API_BASE } from '../../../api/httpClient'

type BucketRow = MemoryMediaBucket & {
  depth: number
}

type CameraBucket = {
  key: string
  label: string
  count: number
}

const props = defineProps<{
  tenantName: string
  memoryId: string
  media: MemoryMedia[]
  buckets: MemoryMediaBucket[]
  canUpload?: boolean
  canEditMetadata?: boolean
  canDeleteMedia?: boolean
  updateMetadataBatch?: (
    memoryId: string,
    mediaIds: string[],
    capturedAtOffsetSeconds: number
  ) => Promise<MemoryMedia[]>
  createBucket?: (memoryId: string, parentBucketId: string, displayName: string) => Promise<MemoryMediaBucket>
  updateBucket?: (memoryId: string, bucketId: string, displayName: string) => Promise<MemoryMediaBucket>
  deleteBucket?: (memoryId: string, bucketId: string) => Promise<void>
  moveMedia?: (memoryId: string, mediaIds: string[], targetBucketId: string) => Promise<MemoryMedia[]>
  deleteMediaBatch?: (memoryId: string, mediaIds: string[]) => Promise<void>
}>()

const emit = defineEmits<{
  updated: [media: MemoryMedia[]]
  uploadBucket: [bucketId: string, memoryId: string]
}>()

const { locale, t } = useI18n()
const activeBucketId = ref('')
const activeCameraKey = ref('')
const selectedMediaIds = ref<Set<string>>(new Set())
const correctedReferenceTime = ref('')
const isOffsetDialogOpen = ref(false)
const isBucketDialogOpen = ref(false)
const isDeleteBucketDialogOpen = ref(false)
const isDeleteMediaDialogOpen = ref(false)
const isMoveDialogOpen = ref(false)
const selectionActionMenu = ref<InstanceType<typeof Menu> | null>(null)
const mediaActionMenu = ref<InstanceType<typeof Menu> | null>(null)
const activeMediaActionId = ref('')
const bucketDialogMode = ref<'create' | 'rename'>('create')
const bucketName = ref('')
const deleteBucketName = ref('')
const isSaving = ref(false)
const saveError = ref('')
const moveTargetBucketId = ref('')
const deleteMediaIds = ref<string[]>([])

const bucketRows = computed<BucketRow[]>(() => {
  const children = new Map<string | null, MemoryMediaBucket[]>()
  props.buckets.forEach((bucket) => {
    const key = bucket.parentBucketId ?? null
    children.set(key, [...(children.get(key) ?? []), bucket])
  })
  children.forEach((items) => items.sort((a, b) => a.displayName.localeCompare(b.displayName, locale.value)))
  const rows: BucketRow[] = []
  const visit = (bucket: MemoryMediaBucket, depth: number) => {
    rows.push({ ...bucket, depth })
    ;(children.get(bucket.id) ?? []).forEach((child) => visit(child, depth + 1))
  }
  ;(children.get(null) ?? []).forEach((bucket) => visit(bucket, 0))
  return rows
})

const activeBucket = computed(() =>
  props.buckets.find((bucket) => bucket.id === activeBucketId.value) ?? props.buckets[0] ?? null
)

const directBucketMedia = computed(() => {
  if (!activeBucket.value) return []
  return props.media.filter((media) => media.bucketId === activeBucket.value?.id)
})

const cameraBuckets = computed<CameraBucket[]>(() => {
  const cameras = new Map<string, CameraBucket>()
  directBucketMedia.value.forEach((media) => {
    const key = cameraKey(media)
    const existing = cameras.get(key)
    if (existing) {
      existing.count += 1
    } else {
      cameras.set(key, { key, label: cameraLabel(media), count: 1 })
    }
  })
  return [...cameras.values()].sort((a, b) => a.label.localeCompare(b.label, locale.value))
})

const filteredMedia = computed(() => {
  if (!activeCameraKey.value) return directBucketMedia.value
  return directBucketMedia.value.filter((media) => cameraKey(media) === activeCameraKey.value)
})

const selectedMedia = computed(() => props.media.filter((media) => selectedMediaIds.value.has(media.id)))
const referenceMedia = computed(() =>
  filteredMedia.value.find((media) => selectedMediaIds.value.has(media.id)) ?? selectedMedia.value[0] ?? null
)
const referenceTimestamp = computed(() => referenceMedia.value ? mediaTimestamp(referenceMedia.value) : null)
const offsetSeconds = computed(() => {
  if (!referenceTimestamp.value || !correctedReferenceTime.value) return 0
  const corrected = new Date(correctedReferenceTime.value).getTime()
  if (Number.isNaN(corrected)) return 0
  return Math.trunc((corrected - referenceTimestamp.value.getTime()) / 1000)
})
const canUpdateBatch = computed(() => Boolean(props.updateMetadataBatch || props.tenantName))
const canSubmitOffset = computed(
  () =>
    Boolean(props.canEditMetadata) &&
    canUpdateBatch.value &&
    selectedMediaIds.value.size > 0 &&
    Boolean(referenceMedia.value) &&
    Boolean(correctedReferenceTime.value) &&
    offsetSeconds.value !== 0 &&
    !isSaving.value
)
const selectedCountLabel = computed(() =>
  t('media.bucketSelectedCount', { selected: selectedMediaIds.value.size, visible: filteredMedia.value.length })
)
const hasVisibleSelection = computed(() => filteredMedia.value.some((media) => selectedMediaIds.value.has(media.id)))
const offsetPreview = computed(() => formatOffset(offsetSeconds.value))
const moveTargets = computed(() =>
  bucketRows.value.filter((bucket) => bucket.memoryId === activeBucket.value?.memoryId && bucket.id !== activeBucket.value?.id)
)
const canSubmitBucketDelete = computed(
  () =>
    Boolean(activeBucket.value) &&
    Boolean(props.canDeleteMedia) &&
    Boolean(props.deleteBucket) &&
    deleteBucketName.value.trim() === activeBucket.value?.displayName &&
    !isSaving.value
)
const deleteMediaCount = computed(() => deleteMediaIds.value.length)
const selectionActionItems = computed<MenuItem[]>(() => [
  {
    label: t('media.shiftCapturedAt'),
    icon: 'pi pi-clock',
    disabled: !selectedMediaIds.value.size || !props.canEditMetadata,
    command: openOffsetDialog
  },
  {
    label: t('media.moveSelected'),
    icon: 'pi pi-arrow-right-arrow-left',
    disabled: !selectedMediaIds.value.size || !props.canEditMetadata || !moveTargets.value.length || !props.moveMedia,
    command: openMoveDialog
  },
  {
    label: t('media.deleteSelected'),
    icon: 'pi pi-trash',
    disabled: !selectedMediaIds.value.size || !props.canDeleteMedia || !props.deleteMediaBatch,
    command: () => {
      openDeleteSelectedMediaDialog()
    }
  }
])
const mediaActionItems = computed<MenuItem[]>(() => [
  {
    label: t('media.shiftCapturedAt'),
    icon: 'pi pi-clock',
    disabled: !props.canEditMetadata,
    command: () => {
      openSingleMediaOffsetDialog()
    }
  },
  {
    label: t('media.moveMedia'),
    icon: 'pi pi-arrow-right-arrow-left',
    disabled: !props.canEditMetadata || !moveTargets.value.length || !props.moveMedia,
    command: () => {
      openSingleMediaMoveDialog()
    }
  },
  {
    label: t('media.deleteMedia'),
    icon: 'pi pi-trash',
    disabled: !props.canDeleteMedia || !props.deleteMediaBatch,
    command: () => {
      openSingleMediaDeleteDialog()
    }
  }
])

watch(
  bucketRows,
  (rows) => {
    if (!rows.length) {
      activeBucketId.value = ''
      activeCameraKey.value = ''
      return
    }
    if (!rows.some((bucket) => bucket.id === activeBucketId.value)) {
      activeBucketId.value = rows[0].id
      activeCameraKey.value = ''
    }
  },
  { immediate: true }
)

watch(cameraBuckets, (buckets) => {
  if (activeCameraKey.value && !buckets.some((bucket) => bucket.key === activeCameraKey.value)) {
    activeCameraKey.value = ''
  }
})

watch(
  () => filteredMedia.value.map((media) => media.id).join('|'),
  () => {
    const visibleIds = new Set(filteredMedia.value.map((media) => media.id))
    selectedMediaIds.value = new Set([...selectedMediaIds.value].filter((mediaId) => visibleIds.has(mediaId)))
  }
)

function cameraKey(media: MemoryMedia): string {
  const label = cameraName(media)
  return label ? normalizeKey(label) : 'UNKNOWN'
}

function cameraLabel(media: MemoryMedia): string {
  return cameraName(media) || t('media.unknownCamera')
}

function cameraName(media: MemoryMedia): string {
  return [media.cameraMake, media.cameraModel].filter(Boolean).join(' ').trim()
}

function normalizeKey(value: string): string {
  return value.trim().toLocaleLowerCase().replace(/\s+/g, ' ')
}

function previewUrl(media: MemoryMedia): string {
  return `${API_BASE}${media.thumbnailUrl || media.previewUrl || media.contentUrl}`
}

function formatDate(value: string | null): string {
  if (!value) return t('media.noCapturedAt')
  return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'de-DE', {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(new Date(value))
}

function mediaTimestamp(media: MemoryMedia): Date {
  return new Date(media.capturedAt || media.createdAt)
}

function isoToDatetimeLocal(date: Date): string {
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

function formatOffset(seconds: number): string {
  const sign = seconds < 0 ? '-' : '+'
  let remaining = Math.abs(seconds)
  const days = Math.floor(remaining / 86_400)
  remaining -= days * 86_400
  const hours = Math.floor(remaining / 3_600)
  remaining -= hours * 3_600
  const minutes = Math.floor(remaining / 60)
  const parts = [days ? `${days}T` : '', hours ? `${hours}h` : '', minutes ? `${minutes}m` : ''].filter(Boolean)
  return parts.length ? `${sign}${parts.join(' ')}` : '+0m'
}

function toggleSelection(mediaId: string, selected: boolean) {
  const next = new Set(selectedMediaIds.value)
  if (selected) {
    next.add(mediaId)
  } else {
    next.delete(mediaId)
  }
  selectedMediaIds.value = next
}

function selectVisibleMedia() {
  selectedMediaIds.value = new Set(filteredMedia.value.map((media) => media.id))
}

function clearVisibleSelection() {
  const visibleIds = new Set(filteredMedia.value.map((media) => media.id))
  selectedMediaIds.value = new Set([...selectedMediaIds.value].filter((mediaId) => !visibleIds.has(mediaId)))
}

function openOffsetDialog() {
  saveError.value = ''
  const timestamp = referenceTimestamp.value
  correctedReferenceTime.value = timestamp ? isoToDatetimeLocal(timestamp) : ''
  isOffsetDialogOpen.value = true
}

function selectSingleMediaActionTarget(): boolean {
  if (!activeMediaActionId.value) return false
  selectedMediaIds.value = new Set([activeMediaActionId.value])
  return true
}

function openSingleMediaOffsetDialog() {
  if (!selectSingleMediaActionTarget()) return
  openOffsetDialog()
}

function openSingleMediaMoveDialog() {
  if (!selectSingleMediaActionTarget()) return
  openMoveDialog()
}

function openSingleMediaDeleteDialog() {
  if (!selectSingleMediaActionTarget()) return
  openDeleteSelectedMediaDialog()
}

function openMediaOptions(event: MouseEvent, mediaId: string) {
  activeMediaActionId.value = mediaId
  mediaActionMenu.value?.toggle(event)
}

async function applyOffset() {
  if (!canSubmitOffset.value || !activeBucket.value) return
  isSaving.value = true
  saveError.value = ''
  try {
    const memoryId = activeBucket.value.memoryId
    const mediaIds = [...selectedMediaIds.value]
    const updated = props.updateMetadataBatch
      ? await props.updateMetadataBatch(memoryId, mediaIds, offsetSeconds.value)
      : await updateMemoryMediaMetadataBatch(props.tenantName, memoryId, {
          mediaIds,
          capturedAtOffsetSeconds: offsetSeconds.value
        })
    emit('updated', updated)
    isOffsetDialogOpen.value = false
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : t('media.batchSaveFailed')
  } finally {
    isSaving.value = false
  }
}

function openCreateBucketDialog() {
  if (!activeBucket.value || !activeBucket.value.primary) return
  bucketDialogMode.value = 'create'
  bucketName.value = ''
  saveError.value = ''
  isBucketDialogOpen.value = true
}

function openRenameBucketDialog() {
  if (!activeBucket.value || activeBucket.value.primary) return
  bucketDialogMode.value = 'rename'
  bucketName.value = activeBucket.value.displayName
  saveError.value = ''
  isBucketDialogOpen.value = true
}

async function submitBucketDialog() {
  if (!activeBucket.value || !bucketName.value.trim()) return
  isSaving.value = true
  saveError.value = ''
  try {
    if (bucketDialogMode.value === 'create') {
      await props.createBucket?.(activeBucket.value.memoryId, activeBucket.value.id, bucketName.value)
    } else {
      await props.updateBucket?.(activeBucket.value.memoryId, activeBucket.value.id, bucketName.value)
    }
    isBucketDialogOpen.value = false
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : t('media.bucketSaveFailed')
  } finally {
    isSaving.value = false
  }
}

function openDeleteBucketDialog() {
  if (!activeBucket.value || !props.canDeleteMedia || !props.deleteBucket) return
  deleteBucketName.value = ''
  saveError.value = ''
  isDeleteBucketDialogOpen.value = true
}

async function deleteActiveBucket() {
  if (!activeBucket.value || !canSubmitBucketDelete.value || !props.deleteBucket) return
  isSaving.value = true
  saveError.value = ''
  try {
    await props.deleteBucket(activeBucket.value.memoryId, activeBucket.value.id)
    isDeleteBucketDialogOpen.value = false
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : t('media.bucketDeleteFailed')
  } finally {
    isSaving.value = false
  }
}

function openMoveDialog() {
  if (!props.canEditMetadata || !selectedMediaIds.value.size) return
  moveTargetBucketId.value = moveTargets.value[0]?.id ?? ''
  saveError.value = ''
  isMoveDialogOpen.value = true
}

function toggleSelectionActionMenu(event: MouseEvent) {
  selectionActionMenu.value?.toggle(event)
}

async function submitMove() {
  if (!props.moveMedia || !activeBucket.value || !moveTargetBucketId.value || !selectedMediaIds.value.size) return
  isSaving.value = true
  saveError.value = ''
  try {
    const updated = await props.moveMedia(activeBucket.value.memoryId, [...selectedMediaIds.value], moveTargetBucketId.value)
    emit('updated', updated)
    selectedMediaIds.value = new Set()
    isMoveDialogOpen.value = false
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : t('media.moveFailed')
  } finally {
    isSaving.value = false
  }
}

function openDeleteSelectedMediaDialog() {
  if (!props.canDeleteMedia || !props.deleteMediaBatch || !selectedMediaIds.value.size) return
  deleteMediaIds.value = [...selectedMediaIds.value]
  saveError.value = ''
  isDeleteMediaDialogOpen.value = true
}

async function deleteSelectedMedia() {
  if (!props.canDeleteMedia || !props.deleteMediaBatch || !deleteMediaIds.value.length) return
  isSaving.value = true
  saveError.value = ''
  try {
    const memoryId = activeBucket.value?.memoryId ?? props.memoryId
    await props.deleteMediaBatch(memoryId, deleteMediaIds.value)
    const deletedIds = new Set(deleteMediaIds.value)
    selectedMediaIds.value = new Set([...selectedMediaIds.value].filter((mediaId) => !deletedIds.has(mediaId)))
    deleteMediaIds.value = []
    isDeleteMediaDialogOpen.value = false
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : t('media.deleteFailed')
  } finally {
    isSaving.value = false
  }
}
</script>

<template>
  <section class="veyq-section memory-section asset-buckets">
    <div class="veyq-section-header memory-section-header">
      <h2>{{ t('media.buckets') }}</h2>
      <span>{{ media.length }}</span>
    </div>

    <div v-if="bucketRows.length" class="asset-bucket-layout">
      <nav class="asset-bucket-list" :aria-label="t('media.uploaderBuckets')">
        <button
          v-for="bucket in bucketRows"
          :key="bucket.id"
          class="asset-bucket-button"
          :class="{ 'asset-bucket-button-active': bucket.id === activeBucketId, 'asset-bucket-button-child': bucket.depth > 0 }"
          type="button"
          :style="{ marginLeft: `${bucket.depth * 28}px`, width: `calc(100% - ${bucket.depth * 28}px)` }"
          @click="activeBucketId = bucket.id; activeCameraKey = ''"
        >
          <span class="pi" :class="bucket.primary ? 'pi-user' : 'pi-folder'" />
          <strong>{{ bucket.displayName }}</strong>
          <small>{{ bucket.mediaCount }}</small>
        </button>
      </nav>

      <div class="asset-bucket-detail">
        <div class="asset-bucket-action-row">
          <strong>{{ activeBucket?.displayName }}</strong>
          <div>
            <button v-if="canUpload && activeBucket" class="asset-bucket-upload-button" type="button" @click="$emit('uploadBucket', activeBucket.id, activeBucket.memoryId)">
              <span class="pi pi-upload" /> {{ t('common.upload') }}
            </button>
            <button v-if="canUpload && activeBucket?.primary && createBucket" type="button" @click="openCreateBucketDialog">
              <span class="pi pi-folder-plus" /> {{ t('media.createBucket') }}
            </button>
            <button v-if="canEditMetadata && activeBucket && !activeBucket.primary && updateBucket" type="button" @click="openRenameBucketDialog">
              <span class="pi pi-pencil" /> {{ t('common.edit') }}
            </button>
            <button v-if="canDeleteMedia && activeBucket && deleteBucket" type="button" @click="openDeleteBucketDialog">
              <span class="pi pi-trash" /> {{ t('common.delete') }}
            </button>
          </div>
        </div>

        <div class="asset-camera-filter" :aria-label="t('media.cameraFilters')">
          <button
            class="asset-camera-chip"
            :class="{ 'asset-camera-chip-active': !activeCameraKey }"
            type="button"
            @click="activeCameraKey = ''"
          >
            {{ t('media.allCameras') }}
            <span>{{ directBucketMedia.length }}</span>
          </button>
          <button
            v-for="camera in cameraBuckets"
            :key="camera.key"
            class="asset-camera-chip"
            :class="{ 'asset-camera-chip-active': camera.key === activeCameraKey }"
            type="button"
            @click="activeCameraKey = camera.key"
          >
            {{ camera.label }}
            <span>{{ camera.count }}</span>
          </button>
        </div>

        <div class="asset-selection-toolbar">
          <span>{{ selectedCountLabel }}</span>
          <button type="button" :disabled="!filteredMedia.length" @click="selectVisibleMedia">
            {{ t('media.selectAllVisible') }}
          </button>
          <button type="button" :disabled="!hasVisibleSelection" @click="clearVisibleSelection">
            {{ t('media.clearVisibleSelection') }}
          </button>
          <button
            class="asset-options-button asset-selection-options-button"
            type="button"
            :aria-label="t('media.options')"
            :disabled="!selectedMediaIds.size"
            aria-haspopup="menu"
            @click="toggleSelectionActionMenu"
          >
            <span class="pi pi-ellipsis-v" />
          </button>
          <Menu ref="selectionActionMenu" :model="selectionActionItems" popup />
        </div>

        <div class="asset-bucket-media">
          <article
            v-for="item in filteredMedia"
            :key="item.id"
            class="asset-bucket-media-item"
            :class="{ 'asset-bucket-media-item-selected': selectedMediaIds.has(item.id) }"
          >
            <label class="asset-media-checkbox">
              <input
                type="checkbox"
                :checked="selectedMediaIds.has(item.id)"
                :aria-label="item.title"
                @change="toggleSelection(item.id, ($event.target as HTMLInputElement).checked)"
              />
            </label>
            <img v-if="item.mimeType.startsWith('image/')" :src="previewUrl(item)" :alt="item.title" loading="lazy" />
            <span v-else class="pi pi-video" />
            <div>
              <strong>{{ item.title }}</strong>
              <small>{{ formatDate(item.capturedAt) }}</small>
            </div>
            <button
              v-if="canEditMetadata || canDeleteMedia"
              class="asset-card-options-button"
              type="button"
              :aria-label="t('media.options')"
              aria-haspopup="menu"
              @click="openMediaOptions($event, item.id)"
            >
              <span class="pi pi-ellipsis-v" />
            </button>
          </article>
          <Menu ref="mediaActionMenu" :model="mediaActionItems" popup />
        </div>
      </div>
    </div>

    <div v-else class="veyq-empty">{{ t('media.empty') }}</div>

    <Dialog
      v-model:visible="isOffsetDialogOpen"
      :header="t('media.shiftCapturedAt')"
      modal
      class="tenant-dialog asset-offset-dialog"
    >
      <form class="asset-batch-form" @submit.prevent="applyOffset">
        <div v-if="referenceMedia" class="asset-reference-media">
          <img
            v-if="referenceMedia.mimeType.startsWith('image/')"
            :src="previewUrl(referenceMedia)"
            :alt="referenceMedia.title"
          />
          <span v-else class="pi pi-video" />
          <div>
            <strong>{{ referenceMedia.title }}</strong>
            <small>{{ t('media.currentCapturedAt') }}: {{ formatDate(referenceMedia.capturedAt || referenceMedia.createdAt) }}</small>
          </div>
        </div>
        <label for="asset-corrected-time">{{ t('media.correctedCapturedAt') }}</label>
        <input id="asset-corrected-time" v-model="correctedReferenceTime" type="datetime-local" />
        <span>{{ selectedMediaIds.size ? t('media.selectedCount', { count: selectedMediaIds.size }) : t('media.noneSelected') }}</span>
        <span>{{ t('media.offsetPreview') }}: {{ offsetPreview }}</span>
        <button type="submit" :disabled="!canSubmitOffset">
          {{ t('media.applyOffset') }}
        </button>
      </form>
      <p v-if="saveError" class="asset-batch-error">{{ saveError }}</p>
    </Dialog>

    <Dialog v-model:visible="isBucketDialogOpen" :header="t('media.bucketDialogTitle')" modal class="tenant-dialog asset-action-dialog">
      <form class="veyq-form asset-dialog-form" @submit.prevent="submitBucketDialog">
        <label for="asset-bucket-name">{{ t('media.bucketName') }}</label>
        <input id="asset-bucket-name" v-model="bucketName" type="text" />
        <div class="veyq-dialog-action-row">
          <button class="veyq-dialog-button veyq-dialog-button-secondary" type="button" :disabled="isSaving" @click="isBucketDialogOpen = false">
            <span class="pi pi-times" aria-hidden="true" />
            <span>{{ t('common.cancel') }}</span>
          </button>
          <button class="veyq-dialog-button veyq-dialog-button-primary" type="submit" :disabled="!bucketName.trim() || isSaving">
            <span class="pi pi-save" aria-hidden="true" />
            <span>{{ t('common.save') }}</span>
          </button>
        </div>
      </form>
      <p v-if="saveError" class="asset-batch-error">{{ saveError }}</p>
    </Dialog>

    <Dialog
      v-model:visible="isDeleteBucketDialogOpen"
      :header="t('media.deleteBucketTitle')"
      modal
      class="tenant-dialog asset-action-dialog"
    >
      <form class="veyq-form asset-dialog-form" @submit.prevent="deleteActiveBucket">
        <p class="asset-dialog-copy">
          {{ t('media.deleteBucketCopy', { bucket: activeBucket?.displayName }) }}
        </p>
        <label for="asset-delete-bucket-name">{{ t('media.deleteBucketNameLabel') }}</label>
        <input
          id="asset-delete-bucket-name"
          v-model="deleteBucketName"
          type="text"
          :placeholder="activeBucket?.displayName"
          autocomplete="off"
        />
        <div class="veyq-dialog-action-row">
          <button class="veyq-dialog-button veyq-dialog-button-secondary" type="button" :disabled="isSaving" @click="isDeleteBucketDialogOpen = false">
            <span class="pi pi-times" aria-hidden="true" />
            <span>{{ t('common.cancel') }}</span>
          </button>
          <button class="veyq-dialog-button veyq-dialog-button-danger" type="submit" :disabled="!canSubmitBucketDelete">
            <span class="pi pi-trash" aria-hidden="true" />
            <span>{{ t('media.deleteBucketConfirmAction') }}</span>
          </button>
        </div>
      </form>
      <p v-if="saveError" class="asset-batch-error">{{ saveError }}</p>
    </Dialog>

    <Dialog v-model:visible="isMoveDialogOpen" :header="t('media.moveSelected')" modal class="tenant-dialog asset-action-dialog">
      <form class="veyq-form asset-dialog-form" @submit.prevent="submitMove">
        <label for="asset-move-target">{{ t('media.moveTargetBucket') }}</label>
        <select id="asset-move-target" v-model="moveTargetBucketId">
          <option v-for="bucket in moveTargets" :key="bucket.id" :value="bucket.id">
            {{ '— '.repeat(bucket.depth) }}{{ bucket.displayName }}
          </option>
        </select>
        <div class="veyq-dialog-action-row">
          <button class="veyq-dialog-button veyq-dialog-button-secondary" type="button" :disabled="isSaving" @click="isMoveDialogOpen = false">
            <span class="pi pi-times" aria-hidden="true" />
            <span>{{ t('common.cancel') }}</span>
          </button>
          <button class="veyq-dialog-button veyq-dialog-button-primary" type="submit" :disabled="!moveTargetBucketId || isSaving">
            <span class="pi pi-arrow-right-arrow-left" aria-hidden="true" />
            <span>{{ t('media.moveSelected') }}</span>
          </button>
        </div>
      </form>
      <p v-if="saveError" class="asset-batch-error">{{ saveError }}</p>
    </Dialog>

    <Dialog
      v-model:visible="isDeleteMediaDialogOpen"
      :header="t('media.deleteMediaTitle')"
      modal
      class="tenant-dialog asset-action-dialog"
    >
      <form class="veyq-form asset-dialog-form" @submit.prevent="deleteSelectedMedia">
        <p class="asset-dialog-copy">
          {{ t('media.deleteMediaCopy', { count: deleteMediaCount }) }}
        </p>
        <div class="veyq-dialog-action-row">
          <button class="veyq-dialog-button veyq-dialog-button-secondary" type="button" :disabled="isSaving" @click="isDeleteMediaDialogOpen = false">
            <span class="pi pi-times" aria-hidden="true" />
            <span>{{ t('common.cancel') }}</span>
          </button>
          <button class="veyq-dialog-button veyq-dialog-button-danger" type="submit" :disabled="!deleteMediaCount || isSaving">
            <span class="pi pi-trash" aria-hidden="true" />
            <span>{{ t('media.deleteMediaConfirmAction') }}</span>
          </button>
        </div>
      </form>
      <p v-if="saveError" class="asset-batch-error">{{ saveError }}</p>
    </Dialog>
  </section>
</template>
