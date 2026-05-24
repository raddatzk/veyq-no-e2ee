<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import FileUpload, { type FileUploadSelectEvent } from 'primevue/fileupload'
import Message from 'primevue/message'
import { getErrorMessage } from '../../../shared/utils/errors'
import { uploadMemoryMedia, type MemoryMedia, type UploadMemoryMediaRequest } from '../api'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  memoryId: string | null
  uploadMedia?: (memoryId: string, request: UploadMemoryMediaRequest) => Promise<MemoryMedia>
}>()
const emit = defineEmits<{
  saved: []
}>()

const { t } = useI18n()
const files = ref<File[]>([])
const error = ref('')
const loading = ref(false)
const picker = ref<{ clear: () => void } | null>(null)

watch(visible, (isVisible) => {
  if (!isVisible) return
  clearFiles()
  error.value = ''
})

function updateFiles(event: Event) {
  const input = event.target as HTMLInputElement
  files.value = Array.from(input.files || [])
}

function selectFiles(event: FileUploadSelectEvent) {
  files.value = event.files
}

function clearFiles() {
  files.value = []
  picker.value?.clear()
}

async function submit() {
  if (!props.memoryId || files.value.length === 0) return
  error.value = ''
  loading.value = true
  try {
    for (const file of files.value) {
      if (props.uploadMedia) await props.uploadMedia(props.memoryId, { file })
      else await uploadMemoryMedia(props.tenantName, props.memoryId, { file })
    }
    visible.value = false
    clearFiles()
    emit('saved')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('memory.uploadFailed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('media.uploadTitle')" modal class="tenant-dialog">
    <form class="form" @submit.prevent="submit">
      <div class="memory-upload-picker">
        <FileUpload
          ref="picker"
          mode="basic"
          accept="image/*,video/*"
          :choose-label="t('media.chooseFiles')"
          custom-upload
          multiple
          @select="selectFiles"
        />
        <input class="memory-upload-native-fallback" type="file" multiple accept="image/*,video/*" @change="updateFiles" />
        <span>{{ files.length ? t('media.selectedCount', { count: files.length }) : t('media.noneSelected') }}</span>
      </div>
      <ul v-if="files.length" class="memory-upload-file-list">
        <li v-for="file in files" :key="`${file.name}-${file.size}`">
          <span class="pi pi-file" />
          <span>{{ file.name }}</span>
        </li>
      </ul>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button
          type="submit"
          :label="t('common.upload')"
          icon="pi pi-upload"
          :disabled="files.length === 0"
          :loading="loading"
        />
        <Button
          type="button"
          :label="t('common.cancel')"
          severity="secondary"
          outlined
          @click="visible = false"
        />
      </div>
    </form>
  </Dialog>
</template>
