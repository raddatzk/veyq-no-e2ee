<script setup lang="ts">
// Create a new shared space (tenant + claim ownership in one E2EE flow). The creator mints the tenant scope
// keypair and self-grants; all crypto lives in features/spaces/invite/groups.createGroup.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { createGroup } from '../../features/spaces/invite/groups'
import { reloadSpaces } from '../../app/spaces'
import { requireSession, session } from '../../app/session'
import { getErrorMessage } from '../../shared/utils/errors'

const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{
  invited: [message: string]
}>()
const { t } = useI18n()
const displayName = ref('')
const error = ref('')
const message = ref('')
const loading = ref(false)
const canSubmit = computed(() => displayName.value.trim().length > 0)

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    message.value = ''
    displayName.value = ''
  }
})

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  message.value = ''
  loading.value = true
  try {
    await createGroup(session.apiBase, requireSession(), displayName.value.trim())
    await reloadSpaces()
    message.value = t('setup.tenantInviteSent')
    visible.value = false
    emit('invited', message.value)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('setup.tenantCreateFailed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('setup.tenantCreate')" modal class="tenant-dialog">
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('setup.tenantName') }}
        <InputText v-model="displayName" autocomplete="off" required />
      </label>
      <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('common.cancel')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          @click="visible = false"
        />
        <Button
          type="submit"
          :label="t('common.create')"
          icon="pi pi-plus"
          :loading="loading"
          :disabled="!canSubmit"
        />
      </div>
    </form>
  </Dialog>
</template>
