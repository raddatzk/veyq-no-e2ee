<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import { deleteLibraryItem, type ExplorerAlbum, type ExplorerFolder, type LibraryItemType } from '../../api/explorerClient'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  type: LibraryItemType
  item: ExplorerFolder | ExplorerAlbum | null
}>()
const emit = defineEmits<{
  deleted: []
}>()

const error = ref('')
const loading = ref(false)
const itemLabel = computed(() => props.item?.displayName || 'Eintrag')

watch(visible, (isVisible) => {
  if (isVisible) error.value = ''
})

async function submit() {
  if (!props.item) return
  error.value = ''
  loading.value = true
  try {
    await deleteLibraryItem(props.tenantName, props.type, props.item.path)
    visible.value = false
    emit('deleted')
  } catch (e: any) {
    error.value = e.message || 'Eintrag konnte nicht gelöscht werden'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" header="Löschen" modal class="tenant-dialog">
    <div class="form">
      <p>
        <strong>{{ itemLabel }}</strong> wirklich löschen?
      </p>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button label="Löschen" icon="pi pi-trash" severity="danger" :loading="loading" @click="submit" />
        <Button label="Abbrechen" severity="secondary" outlined @click="visible = false" />
      </div>
    </div>
  </Dialog>
</template>
