<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { toPermalink } from '../../../composables/usePermalink'
import { getErrorMessage } from '../../../shared/utils/errors'
import { updateMemory, type Memory, type UpdateMemoryRequest } from '../api'
import { isValidMemoryDateRange } from '../utils/memoryDateRange'

const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{
  tenantName: string
  memory: Memory
  saveMemory?: (memoryId: string, request: UpdateMemoryRequest) => Promise<Memory>
}>()
const emit = defineEmits<{
  saved: [memory: Memory]
}>()

const { t } = useI18n()
const displayName = ref('')
const name = ref('')
const startDate = ref('')
const endDate = ref('')
const permalinkTouched = ref(false)
const error = ref('')
const loading = ref(false)

watch(
  () => props.memory,
  () => {
    if (visible.value) resetForm()
  }
)

watch(displayName, (value) => {
  if (!permalinkTouched.value) name.value = toPermalink(value)
})

watch(
  visible,
  (isVisible) => {
    if (isVisible) resetForm()
  },
  { immediate: true }
)

function resetForm() {
  displayName.value = props.memory.displayName
  name.value = props.memory.name
  startDate.value = props.memory.startDate
  endDate.value = props.memory.endDate
  permalinkTouched.value = false
  error.value = ''
}

function updatePermalink(value: string | undefined) {
  permalinkTouched.value = true
  name.value = toPermalink(value || '')
}

async function submit() {
  const request = {
    displayName: displayName.value.trim(),
    name: name.value.trim(),
    startDate: startDate.value,
    endDate: endDate.value
  }
  if (!request.name || !request.displayName || !isValidMemoryDateRange(request.startDate, request.endDate)) return

  error.value = ''
  loading.value = true
  try {
    const updated = props.saveMemory
      ? await props.saveMemory(props.memory.id, request)
      : await updateMemory(props.tenantName, props.memory.id, request)
    visible.value = false
    emit('saved', updated)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('memory.saveFailed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('memory.edit')" modal class="tenant-dialog">
    <form class="form" @submit.prevent="submit">
      <label>
        {{ t('common.name') }}
        <InputText v-model="displayName" autocomplete="off" required />
      </label>
      <label>
        {{ t('common.permalink') }}
        <InputText
          :model-value="name"
          autocomplete="off"
          required
          @update:model-value="updatePermalink"
        />
      </label>
      <label>
        {{ t('memory.startDate') }}
        <InputText v-model="startDate" type="date" required />
      </label>
      <label>
        {{ t('memory.endDate') }}
        <InputText v-model="endDate" type="date" required />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button type="submit" :label="t('common.save')" icon="pi pi-check" :loading="loading" />
        <Button
          type="button"
          :label="t('common.cancel')"
          severity="secondary"
          outlined
          @click="visible = false"
        />
      </div>
    </form>
  </Dialog>
</template>
