<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import { updateMemoryMediaMetadataBatch, type MemoryMedia } from '../api'
import { API_BASE } from '../../../api/httpClient'

type UploaderBucket = {
  key: string
  label: string
  media: MemoryMedia[]
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
  updateMetadataBatch?: (
    memoryId: string,
    mediaIds: string[],
    capturedAtOffsetSeconds: number
  ) => Promise<MemoryMedia[]>
}>()

const emit = defineEmits<{
  updated: [media: MemoryMedia[]]
}>()

const { locale, t } = useI18n()
const activeUploaderKey = ref('')
const activeCameraKey = ref('')
const selectedMediaIds = ref<Set<string>>(new Set())
const correctedReferenceTime = ref('')
const isOffsetDialogOpen = ref(false)
const isSaving = ref(false)
const saveError = ref('')

const uploaderBuckets = computed<UploaderBucket[]>(() => {
  const buckets = new Map<string, UploaderBucket>()
  props.media.forEach((media) => {
    const key = uploaderKey(media)
    const existing = buckets.get(key)
    if (existing) {
      existing.media.push(media)
    } else {
      buckets.set(key, { key, label: uploaderLabel(media), media: [media] })
    }
  })

  return [...buckets.values()].sort((a, b) => a.label.localeCompare(b.label, locale.value))
})

const activeUploader = computed(() =>
  uploaderBuckets.value.find((bucket) => bucket.key === activeUploaderKey.value) ?? uploaderBuckets.value[0] ?? null
)

const cameraBuckets = computed<CameraBucket[]>(() => {
  const cameras = new Map<string, CameraBucket>()
  activeUploader.value?.media.forEach((media) => {
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
  if (!activeUploader.value) return []
  if (!activeCameraKey.value) return activeUploader.value.media
  return activeUploader.value.media.filter((media) => cameraKey(media) === activeCameraKey.value)
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
const canSubmit = computed(
  () =>
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

watch(
  uploaderBuckets,
  (buckets) => {
    if (!buckets.length) {
      activeUploaderKey.value = ''
      activeCameraKey.value = ''
      return
    }
    if (!buckets.some((bucket) => bucket.key === activeUploaderKey.value)) {
      activeUploaderKey.value = buckets[0].key
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

function uploaderKey(media: MemoryMedia): string {
  if (media.uploader.type === 'USER' && media.uploader.userId !== null && media.uploader.userId !== undefined) {
    return `USER:${media.uploader.userId}`
  }
  if (media.uploader.type === 'ANONYMOUS' && media.uploader.displayName) {
    return `ANONYMOUS:${normalizeKey(media.uploader.displayName)}`
  }
  return 'UNKNOWN'
}

function uploaderLabel(media: MemoryMedia): string {
  return media.uploader.displayName?.trim() || t('media.unknownUploader')
}

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
  const weeks = Math.floor(remaining / 604_800)
  remaining -= weeks * 604_800
  const days = Math.floor(remaining / 86_400)
  remaining -= days * 86_400
  const hours = Math.floor(remaining / 3_600)
  remaining -= hours * 3_600
  const minutes = Math.floor(remaining / 60)
  const parts = [
    weeks ? `${weeks}W` : '',
    days ? `${days}T` : '',
    hours ? `${hours}h` : '',
    minutes ? `${minutes}m` : ''
  ].filter(Boolean)
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

function openMediaOptions(mediaId: string) {
  selectedMediaIds.value = new Set([mediaId])
  openOffsetDialog()
}

async function applyOffset() {
  if (!canSubmit.value) return
  isSaving.value = true
  saveError.value = ''
  try {
    const mediaIds = [...selectedMediaIds.value]
    const updated = props.updateMetadataBatch
      ? await props.updateMetadataBatch(props.memoryId, mediaIds, offsetSeconds.value)
      : await updateMemoryMediaMetadataBatch(props.tenantName, props.memoryId, {
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
</script>

<template>
  <section class="memory-section asset-buckets">
    <div class="memory-section-header">
      <h2>{{ t('media.buckets') }}</h2>
      <span>{{ media.length }}</span>
    </div>

    <div v-if="uploaderBuckets.length" class="asset-bucket-layout">
      <nav class="asset-bucket-list" :aria-label="t('media.uploaderBuckets')">
        <button
          v-for="bucket in uploaderBuckets"
          :key="bucket.key"
          class="asset-bucket-button"
          :class="{ 'asset-bucket-button-active': bucket.key === activeUploaderKey }"
          type="button"
          @click="activeUploaderKey = bucket.key; activeCameraKey = ''"
        >
          <span class="pi pi-user" />
          <strong>{{ bucket.label }}</strong>
          <small>{{ bucket.media.length }}</small>
        </button>
      </nav>

      <div class="asset-bucket-detail">
        <div class="asset-camera-filter" :aria-label="t('media.cameraFilters')">
          <button
            class="asset-camera-chip"
            :class="{ 'asset-camera-chip-active': !activeCameraKey }"
            type="button"
            @click="activeCameraKey = ''"
          >
            {{ t('media.allCameras') }}
            <span>{{ activeUploader?.media.length ?? 0 }}</span>
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
            @click="openOffsetDialog"
          >
            <span class="pi pi-ellipsis-v" />
          </button>
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
              class="asset-card-options-button"
              type="button"
              :aria-label="t('media.options')"
              @click="openMediaOptions(item.id)"
            >
              <span class="pi pi-ellipsis-v" />
            </button>
          </article>
        </div>
      </div>
    </div>

    <div v-else class="memory-empty">{{ t('media.empty') }}</div>

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
        <button type="submit" :disabled="!canSubmit">
          {{ t('media.applyOffset') }}
        </button>
      </form>
      <p v-if="saveError" class="asset-batch-error">{{ saveError }}</p>
    </Dialog>
  </section>
</template>
