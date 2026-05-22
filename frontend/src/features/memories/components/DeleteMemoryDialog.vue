<script setup lang="ts">
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import { getErrorMessage } from '../../../shared/utils/errors'
import { deleteMemory, type Memory } from '../api'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  memory: Memory
}>()
const emit = defineEmits<{
  deleted: []
}>()

const error = ref('')
const loading = ref(false)

watch(visible, (isVisible) => {
  if (isVisible) error.value = ''
})

async function confirmDelete() {
  error.value = ''
  loading.value = true
  try {
    await deleteMemory(props.tenantName, props.memory.id)
    visible.value = false
    emit('deleted')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Memory konnte nicht gelöscht werden')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" header="Memory löschen" modal class="tenant-dialog">
    <div class="form">
      <p class="memory-dialog-copy">
        Diese Aktion löscht "{{ memory.displayName }}", alle untergeordneten Memories und alle
        Medien, die diesen Memories direkt gehören.
      </p>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button
          type="button"
          label="Memory löschen"
          icon="pi pi-trash"
          severity="danger"
          :loading="loading"
          @click="confirmDelete"
        />
        <Button
          type="button"
          label="Abbrechen"
          severity="secondary"
          outlined
          @click="visible = false"
        />
      </div>
    </div>
  </Dialog>
</template>
