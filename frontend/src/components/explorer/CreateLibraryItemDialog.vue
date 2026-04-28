<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { createAlbum, createFolder, type LibraryItemType } from '../../api/explorerClient'
import { toPermalink } from '../../composables/usePermalink'
import { getErrorMessage } from '../../shared/utils/errors'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  type: LibraryItemType
  tenantName: string
  parentPath: string
}>()
const emit = defineEmits<{
  saved: []
}>()

const displayName = ref('')
const name = ref('')
const permalinkTouched = ref(false)
const error = ref('')
const loading = ref(false)
const title = computed(() => (props.type === 'folder' ? 'Ordner erstellen' : 'Album erstellen'))

watch(displayName, (value) => {
  if (!permalinkTouched.value) name.value = toPermalink(value)
})

watch(visible, (isVisible) => {
  if (!isVisible) return
  displayName.value = ''
  name.value = ''
  permalinkTouched.value = false
  error.value = ''
})

function updatePermalink(value: string | undefined) {
  permalinkTouched.value = true
  name.value = toPermalink(value || '')
}

async function submit() {
  const request = {
    parentPath: props.parentPath || '/',
    displayName: displayName.value.trim(),
    name: name.value.trim()
  }
  if (!request.name || !request.displayName) return

  error.value = ''
  loading.value = true
  try {
    if (props.type === 'folder') {
      await createFolder(props.tenantName, request)
    } else {
      await createAlbum(props.tenantName, request)
    }
    visible.value = false
    emit('saved')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Eintrag konnte nicht erstellt werden')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="title" modal class="tenant-dialog">
    <form class="form" @submit.prevent="submit">
      <label>
        Name
        <InputText v-model="displayName" autocomplete="off" required />
      </label>
      <label>
        Permalink
        <InputText
          :model-value="name"
          autocomplete="off"
          required
          @update:model-value="updatePermalink"
        />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button type="submit" label="Erstellen" icon="pi pi-plus" :loading="loading" />
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
