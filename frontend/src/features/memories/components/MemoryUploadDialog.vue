<script setup lang="ts">
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
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

const files = ref<File[]>([])
const error = ref('')
const loading = ref(false)

watch(visible, (isVisible) => {
  if (!isVisible) return
  files.value = []
  error.value = ''
})

function updateFiles(event: Event) {
  const input = event.target as HTMLInputElement
  files.value = Array.from(input.files || [])
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
    emit('saved')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Upload konnte nicht abgeschlossen werden')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" header="Medien hochladen" modal class="tenant-dialog">
    <form class="form" @submit.prevent="submit">
      <label>
        Dateien
        <input type="file" multiple accept="image/*,video/*" @change="updateFiles" />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button
          type="submit"
          label="Hochladen"
          icon="pi pi-upload"
          :disabled="files.length === 0"
          :loading="loading"
        />
        <Button
          type="button"
          label="Abbrechen"
          severity="secondary"
          outlined
          @click="visible = false"
        />
      </div>
    </form>
  </Dialog>
</template>
