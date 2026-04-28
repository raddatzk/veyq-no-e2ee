<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { updateLibraryItem, type ExplorerAlbum, type ExplorerFolder, type LibraryItemType } from '../../api/explorerClient'
import { toPermalink } from '../../composables/usePermalink'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  type: LibraryItemType
  item: ExplorerFolder | ExplorerAlbum | null
}>()
const emit = defineEmits<{
  saved: []
}>()

const displayName = ref('')
const name = ref('')
const error = ref('')
const loading = ref(false)
const title = computed(() => props.type === 'folder' ? 'Ordner bearbeiten' : 'Album bearbeiten')

watch(visible, (isVisible) => {
  if (!isVisible || !props.item) return
  displayName.value = props.item.displayName
  name.value = props.item.name
  error.value = ''
})

function updatePermalink(value: string | undefined) {
  name.value = toPermalink(value || '')
}

async function submit() {
  if (!props.item) return
  error.value = ''
  loading.value = true
  try {
    await updateLibraryItem(props.tenantName, props.type, {
      path: props.item.path,
      displayName: displayName.value.trim(),
      name: name.value.trim()
    })
    visible.value = false
    emit('saved')
  } catch (e: any) {
    error.value = e.message || 'Eintrag konnte nicht gespeichert werden'
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
        <InputText :model-value="name" autocomplete="off" required @update:model-value="updatePermalink" />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button type="submit" label="Speichern" icon="pi pi-check" :loading="loading" />
        <Button type="button" label="Abbrechen" severity="secondary" outlined @click="visible = false" />
      </div>
    </form>
  </Dialog>
</template>
