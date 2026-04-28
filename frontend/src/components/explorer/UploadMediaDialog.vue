<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import ProgressBar from 'primevue/progressbar'
import { uploadMedia } from '../../api/explorerClient'

type UploadItem = {
  id: string
  file: File
  status: 'pending' | 'uploading' | 'done' | 'error'
  error: string
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
const hasFiles = computed(() => items.value.length > 0)
const doneCount = computed(() => items.value.filter((item) => item.status === 'done').length)
const progress = computed(() => hasFiles.value ? Math.round((doneCount.value / items.value.length) * 100) : 0)

watch(visible, (isVisible) => {
  if (!isVisible) return
  items.value = []
  error.value = ''
  uploading.value = false
})

function selectFiles(event: Event) {
  const input = event.target as HTMLInputElement
  error.value = ''
  const selected = Array.from(input.files || [])
  items.value = selected.map((file) => ({
    id: `${file.name}-${file.size}-${file.lastModified}`,
    file,
    status: isSupported(file) ? 'pending' : 'error',
    error: isSupported(file) ? '' : 'Nur Bilder und Videos werden unterstützt'
  }))
  input.value = ''
}

async function submit() {
  const pending = items.value.filter((item) => item.status === 'pending')
  if (!pending.length) {
    error.value = 'Bitte wähle mindestens eine Bild- oder Videodatei aus.'
    return
  }

  error.value = ''
  uploading.value = true
  for (const item of pending) {
    item.status = 'uploading'
    try {
      await uploadMedia(props.tenantName, { albumPath: props.albumPath, file: item.file })
      item.status = 'done'
      item.error = ''
    } catch (e: any) {
      item.status = 'error'
      item.error = e.message || 'Upload fehlgeschlagen'
    }
  }
  uploading.value = false

  if (items.value.some((item) => item.status === 'done')) emit('saved')
  if (items.value.every((item) => item.status === 'done')) visible.value = false
}

function isSupported(file: File): boolean {
  return file.type.startsWith('image/') || file.type.startsWith('video/')
}

function formatBytes(value: number): string {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${Math.round(value / 1024)} KB`
  return `${(value / (1024 * 1024)).toFixed(1)} MB`
}
</script>

<template>
  <Dialog v-model:visible="visible" header="Medien hochladen" modal class="tenant-dialog">
    <form class="form" @submit.prevent="submit">
      <label class="upload-dropzone">
        <span class="pi pi-upload" />
        <strong>Dateien auswählen</strong>
        <small>Bilder und Videos</small>
        <input type="file" accept="image/*,video/*" multiple :disabled="uploading" @change="selectFiles" />
      </label>

      <div v-if="items.length" class="upload-list">
        <div v-for="item in items" :key="item.id" class="upload-row">
          <span :class="[
            'pi',
            item.status === 'done' ? 'pi-check-circle' : item.status === 'error' ? 'pi-exclamation-circle' : 'pi-file'
          ]" />
          <div>
            <strong>{{ item.file.name }}</strong>
            <small>{{ formatBytes(item.file.size) }}</small>
            <small v-if="item.error" class="upload-error">{{ item.error }}</small>
          </div>
        </div>
      </div>

      <ProgressBar v-if="uploading || doneCount" :value="progress" />
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

      <div class="button-row">
        <Button type="submit" label="Hochladen" icon="pi pi-upload" :loading="uploading" :disabled="!hasFiles" />
        <Button type="button" label="Abbrechen" severity="secondary" outlined :disabled="uploading" @click="visible = false" />
      </div>
    </form>
  </Dialog>
</template>
