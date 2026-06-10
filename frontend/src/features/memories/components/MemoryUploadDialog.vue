<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import { getErrorMessage } from '../../../shared/utils/errors'
import { type MemoryMedia, type UploadMediaOptions, type UploadMemoryMediaRequest } from '../api'
import { mediaFileAccept, mediaFilesFromList, startMemoryMediaUpload } from '../composables/useMemoryMediaUpload'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  memoryId: string | null
  bucketId?: string | null
  uploadMedia?: (
    memoryId: string,
    request: UploadMemoryMediaRequest,
    options?: UploadMediaOptions
  ) => Promise<MemoryMedia>
}>()
const emit = defineEmits<{
  uploaded: [media: MemoryMedia]
  saved: []
}>()

const { t } = useI18n()
const files = ref<File[]>([])
const error = ref('')
const loading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const uploadInputId = `memory-upload-${Math.random().toString(36).slice(2)}`
const isTestMode = import.meta.env.MODE === 'test' || navigator.userAgent.includes('HappyDOM')
const fileSelectionRetryDelays = isTestMode ? [5, 15, 25] : [75, 250, 750, 1500, 3000, 6000, 10000]
const fileSelectionMinimumPreparationMs = isTestMode ? 25 : 6000
const fileSelectionQuietMs = isTestMode ? 5 : 1200
let fileSelectionToken = 0
let fileSelectionTimers: number[] = []
let fileSelectionBest: File[] = []
let fileSelectionRemovedSignatures = new Set<string>()
let fileSelectionPendingPickerOpen = false
let fileSelectionStartedAt = 0
let fileSelectionLastChangedAt = 0

watch(visible, (isVisible) => {
  if (!isVisible) return
  clearFiles()
  error.value = ''
})

onBeforeUnmount(() => {
  clearFileSelectionTimers()
})

function updateFiles(event: Event) {
  if (loading.value) return
  const input = event.target as HTMLInputElement
  readFileSelection(input)
}

function clearFiles() {
  if (loading.value) return
  clearFileSelectionTimers()
  fileSelectionBest = []
  fileSelectionRemovedSignatures = new Set()
  fileSelectionPendingPickerOpen = false
  fileSelectionStartedAt = 0
  fileSelectionLastChangedAt = 0
  files.value = []
  if (fileInput.value) fileInput.value.value = ''
}

function removeFile(index: number) {
  if (loading.value) return
  clearFileSelectionTimers()
  const removedFile = files.value[index]
  if (removedFile) fileSelectionRemovedSignatures.add(fileSignature(removedFile))
  files.value = files.value.filter((_, fileIndex) => fileIndex !== index)
  fileSelectionBest = files.value
  if (files.value.length === 0 && fileInput.value) fileInput.value.value = ''
}

async function submit() {
  error.value = ''
  loading.value = true
  if (fileInput.value) await waitForPreparedFileSelection(fileInput.value)
  if (!props.memoryId || files.value.length === 0) {
    loading.value = false
    return
  }
  try {
    startMemoryMediaUpload({
      tenantName: props.tenantName,
      memoryId: props.memoryId,
      files: [...files.value],
      bucketId: props.bucketId,
      uploadMedia: props.uploadMedia,
      onFileComplete: emitUploadedMedia,
      onComplete: () => {
        emit('saved')
      }
    })
    visible.value = false
    files.value = []
    clearFileSelectionTimers()
    fileSelectionBest = []
    fileSelectionRemovedSignatures = new Set()
    fileSelectionPendingPickerOpen = false
    fileSelectionStartedAt = 0
    fileSelectionLastChangedAt = 0
    if (fileInput.value) fileInput.value.value = ''
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('memory.uploadFailed'))
  } finally {
    loading.value = false
  }
}

function emitUploadedMedia(media: MemoryMedia) {
  emit('uploaded', media)
}

function prepareForNextFileSelection() {
  if (loading.value) return
  clearFileSelectionTimers()
  fileSelectionBest = files.value
  fileSelectionRemovedSignatures = new Set()
  fileSelectionPendingPickerOpen = true
  fileSelectionStartedAt = 0
  fileSelectionLastChangedAt = 0
  if (fileInput.value) fileInput.value.value = ''
}

function readFileSelection(input: HTMLInputElement) {
  clearFileSelectionTimers()
  fileSelectionPendingPickerOpen = false
  fileSelectionBest = []
  fileSelectionStartedAt = Date.now()
  fileSelectionLastChangedAt = fileSelectionStartedAt
  const token = ++fileSelectionToken
  applyBestFileSelection(input)
  const finalDelay = fileSelectionRetryDelays.at(-1)
  fileSelectionRetryDelays.forEach((delay) => {
    const timer = window.setTimeout(() => {
      fileSelectionTimers = fileSelectionTimers.filter((activeTimer) => activeTimer !== timer)
      if (token !== fileSelectionToken) return
      applyBestFileSelection(input)
      if (delay === finalDelay) input.value = ''
    }, delay)
    fileSelectionTimers.push(timer)
  })
}

function applyBestFileSelection(input: HTMLInputElement) {
  const nextFiles = mediaFilesFromList(input.files || []).filter(
    (file) => !fileSelectionRemovedSignatures.has(fileSignature(file))
  )
  if (nextFiles.length >= fileSelectionBest.length) {
    if (nextFiles.length > fileSelectionBest.length) fileSelectionLastChangedAt = Date.now()
    fileSelectionBest = nextFiles
    files.value = nextFiles
  }
}

function fileSignature(file: File) {
  return `${file.name}:${file.size}:${file.lastModified}:${file.type}`
}

function clearFileSelectionTimers() {
  fileSelectionTimers.forEach((timer) => window.clearTimeout(timer))
  fileSelectionTimers = []
}

async function waitForPreparedFileSelection(input: HTMLInputElement) {
  if (fileSelectionPendingPickerOpen) return
  const token = fileSelectionToken
  while (token === fileSelectionToken && shouldWaitForFileSelection()) {
    applyBestFileSelection(input)
    await delay(250)
  }
  applyBestFileSelection(input)
}

function shouldWaitForFileSelection() {
  if (!fileSelectionStartedAt) return false
  const now = Date.now()
  const preparing = now - fileSelectionStartedAt < fileSelectionMinimumPreparationMs
  const recentlyChanged = now - fileSelectionLastChangedAt < fileSelectionQuietMs
  const stillScheduled = fileSelectionTimers.length > 0
  return preparing || recentlyChanged || stillScheduled
}

async function delay(ms: number) {
  await new Promise((resolve) => window.setTimeout(resolve, ms))
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('media.uploadTitle')" modal class="tenant-dialog">
    <form class="veyq-form" @submit.prevent="submit">
      <div class="memory-upload-picker">
        <label
          class="p-button p-component memory-upload-choose-label"
          :class="{ 'p-disabled': loading }"
          :for="uploadInputId"
          :aria-disabled="loading"
        >
          <span class="p-button-icon pi pi-plus" />
          <span class="p-button-label">{{ t('media.chooseFiles') }}</span>
        </label>
        <input
          :id="uploadInputId"
          ref="fileInput"
          class="memory-upload-native-fallback"
          type="file"
          multiple
          :accept="mediaFileAccept"
          :disabled="loading"
          @click="prepareForNextFileSelection"
          @change="updateFiles"
        />
        <span>{{ files.length ? t('media.selectedCount', { count: files.length }) : t('media.noneSelected') }}</span>
      </div>
      <div v-if="files.length" class="memory-upload-selection">
        <button type="button" class="memory-upload-clear-button" :disabled="loading" @click="clearFiles">
          {{ t('media.clearSelectedFiles') }}
        </button>
        <ul class="memory-upload-file-list">
          <li v-for="(file, index) in files" :key="`${file.name}-${file.size}-${index}`">
            <span class="pi pi-file" />
            <span>{{ file.name }}</span>
            <button
              type="button"
              class="memory-upload-file-remove"
              :disabled="loading"
              :aria-label="t('media.removeSelectedFile', { file: file.name })"
              @click="removeFile(index)"
            >
              <span class="pi pi-times" />
            </button>
          </li>
        </ul>
      </div>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('common.cancel')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          :disabled="loading"
          @click="visible = false"
        />
        <Button
          type="submit"
          :label="t('common.upload')"
          icon="pi pi-upload"
          :disabled="files.length === 0 || loading"
          :loading="loading"
        />
      </div>
    </form>
  </Dialog>
</template>
