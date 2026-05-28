<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
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
const router = useRouter()
const { t } = useI18n()
const { setTenants } = useAuthState()
const name = ref('')
const displayName = ref('')
const domain = ref('')
const permalinkTouched = ref(false)
const error = ref('')
const loading = ref(false)

watch(displayName, (value) => {
  if (!permalinkTouched.value) {
    name.value = toPermalink(value)
  }
})

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    name.value = ''
    displayName.value = ''
    domain.value = window.location.host
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
  if (!tenantName) return

  error.value = ''
  loading.value = true
  try {
    const tenant = await createTenant(tenantName, tenantDisplayName, domain.value)
    setTenants(await tenants())
    visible.value = false
    await router.push(`/${tenant.name}`)
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
        <Button type="submit" :label="t('common.create')" icon="pi pi-plus" :loading="loading" />
      </div>
    </form>
  </Dialog>
</template>
