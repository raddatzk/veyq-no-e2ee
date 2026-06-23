<script setup lang="ts">
// Create a tenant shell and owner invite. Ownership is claimed only when the invite is opened, so creating
// the shell never makes the current Server Owner a tenant member or key-holder.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { createTenantOwnerInvite } from '../../features/admin/createTenantInviteOwner/tenant'
import { reloadSpaces } from '../../app/spaces'
import { requireSession, session } from '../../app/session'
import { getErrorMessage } from '../../shared/utils/errors'

const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{
  invited: [message: string]
}>()
const { t } = useI18n()
const displayName = ref('')
const domain = ref('')
const ownerEmail = ref('')
const error = ref('')
const message = ref('')
const loading = ref(false)
const canSubmit = computed(() => displayName.value.trim().length > 0 && domain.value.trim().length > 0 && ownerEmail.value.trim().length > 0)

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    message.value = ''
    displayName.value = ''
    domain.value = ''
    ownerEmail.value = ''
  }
})

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  message.value = ''
  loading.value = true
  try {
    await createTenantOwnerInvite(session.apiBase, requireSession().sessionToken, displayName.value.trim(), domain.value.trim(), ownerEmail.value.trim())
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
      <label>
        {{ t('setup.tenantDomain') }}
        <InputText v-model="domain" autocomplete="off" required />
        <span class="veyq-form-hint">{{ t('setup.tenantDomainHint') }}</span>
      </label>
      <label>
        {{ t('setup.tenantOwnerEmail') }}
        <InputText v-model="ownerEmail" type="email" autocomplete="email" required />
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
