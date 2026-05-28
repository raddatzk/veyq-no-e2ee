<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import type { PasskeySummary } from '../../api/generated'

const props = defineProps<{
  visible: boolean
  passkey: PasskeySummary | null
  saving?: boolean
}>()

const emit = defineEmits<{
  'update:visible': [visible: boolean]
  save: [displayName: string]
  skip: []
}>()

const { t } = useI18n()
const displayName = ref('')
const error = ref('')

watch(
  () => [props.visible, props.passkey?.passkeyId] as const,
  () => {
    if (!props.visible || !props.passkey) return
    displayName.value = props.passkey.displayName
    error.value = ''
  },
  { immediate: true }
)

function save() {
  const nextDisplayName = displayName.value.trim()
  if (!nextDisplayName) {
    error.value = t('auth.passkeyDisplayNameRequired')
    return
  }
  emit('save', nextDisplayName)
}

function skip() {
  error.value = ''
  emit('skip')
  emit('update:visible', false)
}

function updateVisible(visible: boolean) {
  emit('update:visible', visible)
  if (!visible) emit('skip')
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="t('profile.passkeyNameDialogTitle')"
    modal
    class="tenant-dialog"
    @update:visible="updateVisible"
  >
    <div class="veyq-form">
      <Message severity="info" :closable="false">
        {{ t('profile.passkeyNameHelp') }}
      </Message>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <label>
        <span>{{ t('auth.passkeyDisplayName') }}</span>
        <InputText v-model="displayName" autocomplete="off" required />
      </label>
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('profile.passkeyNameSkip')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          @click="skip"
        />
        <Button
          type="button"
          :label="t('common.save')"
          icon="pi pi-save"
          :loading="saving"
          @click="save"
        />
      </div>
    </div>
  </Dialog>
</template>
