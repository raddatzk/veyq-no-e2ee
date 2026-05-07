<script setup lang="ts">
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import {
  moveLibraryItem,
  type ExplorerAlbum,
  type ExplorerFolder,
  type LibraryItemType
} from '../../api/explorerClient'
import { toPermalink } from '../../composables/usePermalink'
import { getErrorMessage } from '../../shared/utils/errors'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  type: LibraryItemType
  item: ExplorerFolder | ExplorerAlbum | null
}>()
const emit = defineEmits<{
  saved: []
}>()

const newParentPath = ref('/')
const name = ref('')
const error = ref('')
const loading = ref(false)

watch(visible, (isVisible) => {
  if (!isVisible || !props.item) return
  newParentPath.value = parentPath(props.item.path)
  name.value = props.item.name
  error.value = ''
})

async function submit() {
  if (!props.item) return
  error.value = ''
  loading.value = true
  try {
    await moveLibraryItem(props.tenantName, props.type, {
      path: props.item.path,
      newParentPath: normalizePath(newParentPath.value),
      name: toPermalink(name.value)
    })
    visible.value = false
    emit('saved')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Eintrag konnte nicht verschoben werden')
  } finally {
    loading.value = false
  }
}

function parentPath(path: string): string {
  const parts = path.split('/').filter(Boolean)
  parts.pop()
  return parts.length ? `/${parts.join('/')}` : '/'
}

function normalizePath(path: string): string {
  const trimmed = path.trim()
  if (!trimmed || trimmed === '/') return '/'
  return `/${trimmed.split('/').filter(Boolean).join('/')}`
}
</script>

<template>
  <Dialog v-model:visible="visible" header="Verschieben" modal class="tenant-dialog">
    <form class="form" @submit.prevent="submit">
      <label>
        Zielpfad
        <InputText v-model="newParentPath" autocomplete="off" placeholder="/" />
      </label>
      <label>
        Permalink
        <InputText v-model="name" autocomplete="off" required />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button
          type="submit"
          label="Verschieben"
          icon="pi pi-arrow-right-arrow-left"
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
