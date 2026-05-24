<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
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

watch(visible, (isVisible) => {
  if (isVisible) error.value = ''
})

async function confirmDelete() {
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
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button
          type="button"
          :label="t('memory.delete')"
          icon="pi pi-trash"
          severity="danger"
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
