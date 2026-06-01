<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import FileUpload, { type FileUploadSelectEvent } from 'primevue/fileupload'
import Message from 'primevue/message'
import { getErrorMessage } from '../../../shared/utils/errors'
import { type MemoryMedia, type UploadMediaOptions, type UploadMemoryMediaRequest } from '../api'
import { mediaFilesFromList, startMemoryMediaUpload } from '../composables/useMemoryMediaUpload'

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
const picker = ref<{ clear?: () => void } | null>(null)

watch(visible, (isVisible) => {
  if (!isVisible) return
  clearFiles()
  error.value = ''
})

function updateFiles(event: Event) {
  if (loading.value) return
  const input = event.target as HTMLInputElement
  files.value = mediaFilesFromList(input.files || [])
}

function selectFiles(event: FileUploadSelectEvent) {
  if (loading.value) return
  files.value = mediaFilesFromList(event.files)
}

function clearFiles() {
  if (loading.value) return
  files.value = []
  picker.value?.clear?.()
}

function removeFile(index: number) {
  if (loading.value) return
  files.value = files.value.filter((_, fileIndex) => fileIndex !== index)
  if (files.value.length === 0) picker.value?.clear?.()
}

async function submit() {
  if (!props.memoryId || files.value.length === 0) return
  error.value = ''
  loading.value = true
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
    picker.value?.clear?.()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('memory.uploadFailed'))
  } finally {
    loading.value = false
  }
}

function emitUploadedMedia(media: MemoryMedia) {
  emit('uploaded', media)
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('media.uploadTitle')" modal class="tenant-dialog">
    <form class="veyq-form" @submit.prevent="submit">
      <div class="memory-upload-picker">
        <FileUpload
          ref="picker"
          class="memory-upload-prime-picker"
          mode="basic"
          accept="image/*,video/*"
          :choose-label="t('media.chooseFiles')"
          custom-upload
          multiple
          :disabled="loading"
          @select="selectFiles"
        >
          <template #filelabel />
        </FileUpload>
        <input
          class="memory-upload-native-fallback"
          type="file"
          multiple
          accept="image/*,video/*"
          :disabled="loading"
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
