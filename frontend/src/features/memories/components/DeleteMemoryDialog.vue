<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { getErrorMessage } from '../../../shared/utils/errors'
import { deleteMemory, type Memory } from '../api'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  memory: Memory
  deleteMemoryOverride?: (memoryId: string) => Promise<void>
}>()
const emit = defineEmits<{
  deleted: []
}>()

const { t } = useI18n()
const error = ref('')
const loading = ref(false)
const deleteConfirmation = ref('')
const canConfirmDelete = computed(
  () => deleteConfirmation.value.trim() === props.memory.displayName.trim() && !loading.value
)

watch(visible, (isVisible) => {
  if (!isVisible) return
  error.value = ''
  deleteConfirmation.value = ''
})

async function confirmDelete() {
  if (!canConfirmDelete.value) return
  error.value = ''
  loading.value = true
  try {
    if (props.deleteMemoryOverride) await props.deleteMemoryOverride(props.memory.id)
    else await deleteMemory(props.tenantName, props.memory.id)
    visible.value = false
    emit('deleted')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('memory.deleteFailed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('memory.delete')" modal class="tenant-dialog">
    <div class="form">
      <p class="memory-dialog-copy">
        {{ t('memory.deleteCopy', { name: memory.displayName }) }}
      </p>
      <label>
        {{ t('settings.confirmationDisplayName') }}
        <InputText
          v-model="deleteConfirmation"
          autocomplete="off"
          :placeholder="memory.displayName"
        />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button
          type="button"
          :label="t('memory.delete')"
          icon="pi pi-trash"
          severity="danger"
          :disabled="!canConfirmDelete"
          :loading="loading"
          @click="confirmDelete"
        />
        <Button
          type="button"
          :label="t('common.cancel')"
          severity="secondary"
          outlined
          @click="visible = false"
        />
      </div>
    </div>
  </Dialog>
</template>
