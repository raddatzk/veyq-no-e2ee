<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import FileUpload, { type FileUploadSelectEvent } from 'primevue/fileupload'
import Message from 'primevue/message'
import { getErrorMessage } from '../../../shared/utils/errors'
import { removeMemoryCover, setMemoryCover, type Memory } from '../api'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  memory: Memory
}>()
const emit = defineEmits<{
  saved: [memory: Memory]
}>()

const selectedFile = ref<File | null>(null)
const error = ref('')
const loading = ref(false)
const hasCover = computed(() => Boolean(props.memory.coverUrl || props.memory.coverMediaId))

watch(visible, (isVisible) => {
  if (!isVisible) return
  selectedFile.value = null
  error.value = ''
})

async function saveCover() {
  if (!selectedFile.value) return
  error.value = ''
  loading.value = true
  try {
    const updated = await setMemoryCover(props.tenantName, props.memory.id, {
      file: selectedFile.value
    })
    visible.value = false
    emit('saved', updated)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Cover konnte nicht gespeichert werden')
  } finally {
    loading.value = false
  }
}

function selectCover(event: FileUploadSelectEvent) {
  selectedFile.value = event.files[0] || null
}

async function removeCover() {
  error.value = ''
  loading.value = true
  try {
    const updated = await removeMemoryCover(props.tenantName, props.memory.id)
    visible.value = false
    emit('saved', updated)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Cover konnte nicht entfernt werden')
  } finally {
    loading.value = false
  }
}

</script>

<template>
  <Dialog v-model:visible="visible" header="Cover wählen" modal class="tenant-dialog">
    <div class="form">
      <p class="memory-dialog-copy">
        Wähle ein Bild als Cover. Es wird nur als Memory-Cover gespeichert und nicht als Medium in
        die Galerie übernommen.
      </p>
      <FileUpload
        mode="basic"
        accept="image/*"
        choose-label="Bild auswählen"
        custom-upload
        @select="selectCover"
      />
      <strong v-if="selectedFile" class="memory-dialog-copy">{{ selectedFile.name }}</strong>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button
          type="button"
          label="Cover speichern"
          icon="pi pi-image"
          :disabled="!selectedFile"
          :loading="loading"
          @click="saveCover"
        />
        <Button
          type="button"
          label="Cover entfernen"
          icon="pi pi-times"
          severity="secondary"
          outlined
          :disabled="!hasCover"
          :loading="loading"
          @click="removeCover"
        />
        <Button
          type="button"
          label="Abbrechen"
          severity="secondary"
          text
          @click="visible = false"
        />
      </div>
    </div>
  </Dialog>
</template>
