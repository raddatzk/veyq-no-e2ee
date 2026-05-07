<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import ProgressBar from 'primevue/progressbar'
import {
  abortMediaUpload,
  listMediaUploads,
  type PendingMediaUploadSession
} from '../../features/explorer/api'
import { uploadMedia } from '../../features/explorer/composables/useMediaUpload'
import { getErrorMessage } from '../../shared/utils/errors'

type UploadStatus = 'resumable' | 'pending' | 'uploading' | 'done' | 'error' | 'aborting'

type UploadItem = {
  id: string
  file: File | null
  session: PendingMediaUploadSession | null
  uploadId: string | null
  status: UploadStatus
  error: string
  progress: number
  controller: AbortController | null
}

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  albumPath: string
}>()
const emit = defineEmits<{
  saved: []
}>()

const items = ref<UploadItem[]>([])
const error = ref('')
const uploading = ref(false)
const loadingSessions = ref(false)
const uploadableCount = computed(
  () => items.value.filter((item) => item.file && item.status === 'pending').length
)
const doneCount = computed(() => items.value.filter((item) => item.status === 'done').length)
const totalProgress = computed(() => {
  if (!items.value.length) return 0
  const sum = items.value.reduce((total, item) => total + item.progress, 0)
  return Math.round(sum / items.value.length)
})

watch(visible, (isVisible) => {
  if (!isVisible) return
  error.value = ''
  uploading.value = false
  void loadPendingUploads()
})

async function loadPendingUploads() {
  loadingSessions.value = true
  try {
    const sessions = await listMediaUploads()
    items.value = sessions
      .filter(
        (session) =>
          session.tenantName === props.tenantName && session.albumPath === props.albumPath
      )
      .map((session) => resumableItem(session))
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Offene Uploads konnten nicht geladen werden')
    items.value = []
  } finally {
    loadingSessions.value = false
  }
}

function selectFiles(event: Event) {
  const input = event.target as HTMLInputElement
  error.value = ''
  const selected = Array.from(input.files || [])

  selected.forEach((file) => {
    const existingIndex = items.value.findIndex(
      (item) => item.file?.name === file.name && item.file.size === file.size
    )
    const matches = items.value.filter((item) => item.session && matchesSession(item.session, file))

    const item = createFileItem(file)
    if (matches.length === 1) {
      const match = matches[0]
      item.id = match.id
      item.session = match.session
      item.uploadId = match.uploadId
      item.progress = match.progress
    } else if (matches.length > 1) {
      item.status = 'error'
      item.error = 'Mehrere offene Uploads passen zu dieser Datei. Bitte alte Sessions abbrechen.'
    }

    if (existingIndex >= 0) {
      items.value.splice(existingIndex, 1, item)
    } else if (matches.length === 1) {
      const matchIndex = items.value.indexOf(matches[0])
      items.value.splice(matchIndex, 1, item)
    } else {
      items.value.push(item)
    }
  })

  input.value = ''
}

async function submit() {
  const pending = items.value.filter((item) => item.file && item.status === 'pending')
  if (!pending.length) {
    error.value = items.value.some((item) => item.status === 'resumable')
      ? 'Wähle die passende lokale Datei aus, um den Upload fortzusetzen.'
      : 'Bitte wähle mindestens eine Bild- oder Videodatei aus.'
    return
  }

  error.value = ''
  uploading.value = true
  for (const item of pending) {
    if (!item.file) continue
    item.status = 'uploading'
    item.controller = new AbortController()
    try {
      await uploadMedia(props.tenantName, {
        albumPath: props.albumPath,
        file: item.file,
        existingUploadId: item.uploadId || undefined,
        signal: item.controller.signal,
        onSession: (session) => {
          item.uploadId = session.uploadId
        },
        onProgress: (uploadedBytes, totalBytes) => {
          item.progress = Math.round((uploadedBytes / totalBytes) * 100)
        }
      })
      item.status = 'done'
      item.progress = 100
      item.error = ''
    } catch (unknownError) {
      item.status = 'error'
      item.error = getErrorMessage(unknownError, 'Upload fehlgeschlagen')
    } finally {
      item.controller = null
    }
  }
  uploading.value = false

  if (items.value.some((item) => item.status === 'done')) emit('saved')
  if (items.value.length && items.value.every((item) => item.status === 'done'))
    visible.value = false
}

async function abortItem(item: UploadItem) {
  item.controller?.abort()
  if (!item.uploadId) {
    items.value = items.value.filter((candidate) => candidate !== item)
    return
  }

  const previousStatus = item.status
  item.status = 'aborting'
  item.error = ''
  try {
    await abortMediaUpload(item.uploadId)
    items.value = items.value.filter((candidate) => candidate !== item)
  } catch (unknownError) {
    item.status = previousStatus === 'aborting' ? 'error' : previousStatus
    item.error = getErrorMessage(unknownError, 'Upload konnte nicht abgebrochen werden')
  }
}

function createFileItem(file: File): UploadItem {
  const supported = isSupported(file)
  return {
    id: `${file.name}-${file.size}-${file.lastModified}`,
    file,
    session: null,
    uploadId: null,
    status: supported ? 'pending' : 'error',
    error: supported ? '' : 'Nur Bilder und Videos werden unterstützt',
    progress: 0,
    controller: null
  }
}

function resumableItem(session: PendingMediaUploadSession): UploadItem {
  return {
    id: session.uploadId,
    file: null,
    session,
    uploadId: session.uploadId,
    status: 'resumable',
    error: '',
    progress: Math.round((session.uploadedBytes / session.sizeBytes) * 100),
    controller: null
  }
}

function matchesSession(session: PendingMediaUploadSession, file: File): boolean {
  return (
    session.filename === file.name &&
    session.sizeBytes === file.size &&
    session.mimeType === file.type
  )
}

function isSupported(file: File): boolean {
  return file.type.startsWith('image/') || file.type.startsWith('video/')
}

function formatBytes(value: number): string {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${Math.round(value / 1024)} KB`
  return `${(value / (1024 * 1024)).toFixed(1)} MB`
}

function itemName(item: UploadItem): string {
  return item.file?.name || item.session?.filename || 'Upload'
}

function itemSize(item: UploadItem): number {
  return item.file?.size || item.session?.sizeBytes || 0
}
</script>

<template>
  <Dialog v-model:visible="visible" header="Medien hochladen" modal class="tenant-dialog">
    <form class="form" @submit.prevent="submit">
      <label class="upload-dropzone">
        <span class="pi pi-upload" />
        <strong>Dateien auswählen</strong>
        <small>Bilder und Videos</small>
        <input
          type="file"
          accept="image/*,video/*"
          multiple
          :disabled="uploading"
          @change="selectFiles"
        />
      </label>

      <Message v-if="loadingSessions" severity="info" :closable="false">
        Offene Uploads werden geladen...
      </Message>

      <div v-if="items.length" class="upload-list">
        <div v-for="item in items" :key="item.id" class="upload-row">
          <span
            :class="[
              'pi',
              item.status === 'done'
                ? 'pi-check-circle'
                : item.status === 'error'
                  ? 'pi-exclamation-circle'
                  : item.status === 'resumable'
                    ? 'pi-history'
                    : 'pi-file'
            ]"
          />
          <div>
            <strong>{{ itemName(item) }}</strong>
            <small>
              {{ formatBytes(itemSize(item)) }}
              <template v-if="item.status === 'resumable'">
                · fortsetzbar · {{ item.progress }}%</template
              >
              <template v-else-if="item.status === 'uploading'"> · {{ item.progress }}%</template>
              <template v-else-if="item.status === 'aborting'"> · wird abgebrochen</template>
            </small>
            <small v-if="item.error" class="upload-error">{{ item.error }}</small>
          </div>
          <Button
            v-if="item.status !== 'done'"
            type="button"
            icon="pi pi-times"
            severity="secondary"
            text
            rounded
            :disabled="item.status === 'aborting'"
            @click="abortItem(item)"
          />
        </div>
      </div>

      <ProgressBar v-if="uploading || doneCount" :value="totalProgress" />
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

      <div class="button-row">
        <Button
          type="submit"
          label="Hochladen"
          icon="pi pi-upload"
          :loading="uploading"
          :disabled="uploading || uploadableCount === 0"
        />
        <Button
          type="button"
          label="Schließen"
          severity="secondary"
          outlined
          :disabled="uploading"
          @click="visible = false"
        />
      </div>
    </form>
  </Dialog>
</template>
