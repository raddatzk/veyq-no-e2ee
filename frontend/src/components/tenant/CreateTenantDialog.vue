<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { createTenant, tenants } from '../../api/tenantClient'
import { toPermalink } from '../../composables/usePermalink'
import { useAuthState } from '../../shared/state/useAuthState'
import { getErrorMessage } from '../../shared/utils/errors'

const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{
  invited: [message: string]
}>()
const { t } = useI18n()
const { setTenants } = useAuthState()
const name = ref('')
const displayName = ref('')
const domain = ref('')
const ownerEmail = ref('')
const permalinkTouched = ref(false)
const error = ref('')
const message = ref('')
const loading = ref(false)
const canSubmit = computed(
  () =>
    name.value.trim().length > 0 &&
    displayName.value.trim().length > 0 &&
    domain.value.trim().length > 0 &&
    ownerEmail.value.trim().length > 0
)

watch(displayName, (value) => {
  if (!permalinkTouched.value) {
    name.value = toPermalink(value)
  }
})

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    message.value = ''
    name.value = ''
    displayName.value = ''
    domain.value = window.location.host
    ownerEmail.value = ''
    permalinkTouched.value = false
  }
})

function updatePermalink(value: string | undefined) {
  permalinkTouched.value = true
  name.value = toPermalink(value || '')
}

async function submit() {
  const tenantName = name.value.trim()
  const tenantDisplayName = displayName.value.trim() || tenantName
  const tenantOwnerEmail = ownerEmail.value.trim()
  if (!canSubmit.value) return

  error.value = ''
  message.value = ''
  loading.value = true
  try {
    await createTenant(tenantName, tenantDisplayName, domain.value, tenantOwnerEmail)
    setTenants(await tenants())
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
        {{ t('common.permalink') }}
        <InputText
          :model-value="name"
          autocomplete="off"
          required
          @update:model-value="updatePermalink"
        />
      </label>
      <label>
        {{ t('common.domain') }}
        <InputText v-model="domain" autocomplete="off" required />
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
