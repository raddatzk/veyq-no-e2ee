<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
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
    error.value = getErrorMessage(unknownError, 'Tenant konnte nicht erstellt werden')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" header="Tenant erstellen" modal class="tenant-dialog">
    <form class="form" @submit.prevent="submit">
      <label>
        Tenant Name
        <InputText v-model="displayName" autocomplete="off" required />
      </label>
      <label>
        Permalink
        <InputText
          :model-value="name"
          autocomplete="off"
          required
          @update:model-value="updatePermalink"
        />
      </label>
      <label>
        Domain
        <InputText v-model="domain" autocomplete="off" required />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="button-row">
        <Button type="submit" label="Erstellen" icon="pi pi-plus" :loading="loading" />
        <Button
          type="button"
          label="Abbrechen"
          severity="secondary"
          outlined
          @click="visible = false"
        />
      </div>
    </form>
  </Dialog>
</template>
