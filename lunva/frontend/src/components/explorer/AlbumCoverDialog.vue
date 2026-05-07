<script setup lang="ts">
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { setAlbumCover, type ExplorerAlbum } from '../../api/explorerClient'
import { getErrorMessage } from '../../shared/utils/errors'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  album: ExplorerAlbum | null
}>()
const emit = defineEmits<{
  saved: []
}>()

const mediaId = ref('')
const hidden = ref(false)
const error = ref('')
const loading = ref(false)

watch(visible, (isVisible) => {
  if (!isVisible) return
  mediaId.value = props.album?.coverMediaId || ''
  hidden.value = false
  error.value = ''
})

async function submit() {
  if (!props.album || !mediaId.value.trim()) return
  error.value = ''
  loading.value = true
  try {
    await setAlbumCover(props.tenantName, props.album.path, mediaId.value.trim(), hidden.value)
    visible.value = false
    emit('saved')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Cover konnte nicht gesetzt werden')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" header="Album-Cover" modal class="tenant-dialog">
    <form class="form" @submit.prevent="submit">
      <label>
        Media-ID
        <InputText v-model="mediaId" autocomplete="off" required />
      </label>
      <label class="checkbox-row">
        <Checkbox v-model="hidden" input-id="cover-hidden" binary />
        Cover-only verstecken
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button type="submit" label="Cover setzen" icon="pi pi-image" :loading="loading" />
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
