<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { authState } from '../authState'
import { serverState } from '../serverState'
import { login } from '../api/authClient'
import { setupAdmin } from '../api/setupClient'
import { createTenant, tenants } from '../api/tenantClient'
import { toPermalink } from '../composables/usePermalink'

const router = useRouter()
const step = ref<'admin' | 'tenant'>('admin')
const tenantDisplayName = ref('Friends')
const tenantName = ref('friends')
const tenantPermalinkTouched = ref(false)
const password = ref('')
const displayName = ref('Administrator')
const email = ref('')
const error = ref('')

watch(tenantDisplayName, (value) => {
  if (!tenantPermalinkTouched.value) {
    tenantName.value = toPermalink(value)
  }
})

function updateTenantPermalink(value: string | undefined) {
  tenantPermalinkTouched.value = true
  tenantName.value = toPermalink(value || '')
}

async function submitAdmin() {
  error.value = ''
  try {
    await setupAdmin({
      password: password.value,
      displayName: displayName.value,
      email: email.value
    })
    const user = await login(email.value, password.value)
    authState.isAuthenticated = true
    authState.userLabel = user.displayName || user.email
    authState.user = {
      userId: user.userId,
      email: user.email,
      displayName: user.displayName,
      serverRole: user.serverRole
    }
    authState.tenants = await tenants()
    serverState.setupCompleted = true
    step.value = 'tenant'
  } catch (e: any) {
    error.value = e.message || 'Admin konnte nicht erstellt werden'
  }
}

async function submitTenant() {
  error.value = ''
  try {
    const tenant = await createTenant(tenantName.value, tenantDisplayName.value)
    authState.tenants = await tenants()
    await router.push(`/${tenant.name}`)
  } catch (e: any) {
    error.value = e.message || 'Tenant konnte nicht erstellt werden'
  }
}

async function skipTenant() {
  await router.push('/')
}
</script>

<template>
  <Card class="login-card">
    <template #title>Setup</template>
    <template #content>
    <form v-if="step === 'admin'" class="form" @submit.prevent="submitAdmin">
      <label>
        E-Mail
        <InputText v-model="email" type="email" autocomplete="email" required />
      </label>
      <label>
        Passwort
        <Password v-model="password" autocomplete="new-password" required minlength="8" toggle-mask fluid />
      </label>
      <label>
        Anzeigename
        <InputText v-model="displayName" required />
      </label>
      <Button type="submit" label="Admin erstellen" icon="pi pi-user-plus" />
    </form>

    <form v-else class="form" @submit.prevent="submitTenant">
      <label>
        Tenant Name
        <InputText v-model="tenantDisplayName" required />
      </label>
      <label>
        Permalink
        <InputText :model-value="tenantName" autocomplete="off" required @update:model-value="updateTenantPermalink" />
      </label>
      <div class="button-row">
        <Button type="submit" label="Tenant erstellen" icon="pi pi-plus" />
        <Button type="button" label="Überspringen" severity="secondary" outlined @click="skipTenant" />
      </div>
    </form>
    <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
    </template>
  </Card>
</template>
