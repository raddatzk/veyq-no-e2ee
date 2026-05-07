<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import {
  deleteLibraryItem,
  type ExplorerAlbum,
  type ExplorerFolder,
  type LibraryItemType
} from '../../api/explorerClient'
import { getErrorMessage } from '../../shared/utils/errors'

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
const confirmation = ref('')
const loading = ref(false)
const itemLabel = computed(() => props.item?.displayName || 'Eintrag')
const expectedName = computed(() => props.item?.displayName.trim() || '')
const canDelete = computed(() => confirmation.value.trim() === expectedName.value && !loading.value)

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    confirmation.value = ''
  }
})

async function submit() {
  if (!props.item || !canDelete.value) return
  error.value = ''
  loading.value = true
  try {
    await deleteLibraryItem(props.tenantName, props.type, props.item.path)
    visible.value = false
    emit('deleted')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Eintrag konnte nicht gelöscht werden')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" header="Löschen" modal class="tenant-dialog">
    <div class="form">
      <p>
        Möchtest du <strong>{{ itemLabel }}</strong> wirklich löschen?
      </p>
      <label>
        Gib {{ itemLabel }} erneut ein zur Bestätigung
        <InputText v-model="confirmation" autocomplete="off" :placeholder="expectedName" />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button
          label="Löschen"
          icon="pi pi-trash"
          severity="danger"
          :disabled="!canDelete"
          :loading="loading"
          @click="submit"
        />
        <Button label="Abbrechen" severity="secondary" outlined @click="visible = false" />
      </div>
    </div>
  </Dialog>
</template>
