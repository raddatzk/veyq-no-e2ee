<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ConfirmationDialog from '../../../shared/components/ConfirmationDialog.vue'
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
  <ConfirmationDialog
    v-model:visible="visible"
    :title="t('memory.delete')"
    :confirm-label="t('memory.delete')"
    confirm-icon="pi pi-trash"
    confirm-severity="danger"
    :confirm-disabled="!canConfirmDelete"
    :loading="loading"
    @confirm="confirmDelete"
  >
    <template #default="{ disabled }">
      <p class="memory-dialog-copy">
        {{ t('memory.deleteCopy', { name: memory.displayName }) }}
      </p>
      <label>
        {{ t('settings.confirmationDisplayName') }}
        <InputText
          v-model="deleteConfirmation"
          autocomplete="off"
          :placeholder="memory.displayName"
          :disabled="disabled"
        />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    </template>
  </ConfirmationDialog>
</template>
