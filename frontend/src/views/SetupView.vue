<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { authState } from '../authState'
import { createTenant, login, setupAdmin, tenants } from '../api/authClient'

const router = useRouter()
const step = ref<'admin' | 'tenant'>('admin')
const tenantName = ref('friends')
const password = ref('')
const displayName = ref('Administrator')
const email = ref('')
const error = ref('')

async function submitAdmin() {
  error.value = ''
  try {
    await setupAdmin({
      password: password.value,
      displayName: displayName.value,
      email: email.value
    })
    const user = await login(email.value, password.value)
    authState.setupRequired = false
    authState.isAuthenticated = true
    authState.userLabel = user.displayName || user.email
    authState.tenants = await tenants()
    step.value = 'tenant'
  } catch (e: any) {
    error.value = e.message || 'Admin konnte nicht erstellt werden'
  }
}

async function submitTenant() {
  error.value = ''
  try {
    const tenant = await createTenant(tenantName.value, tenantName.value)
    authState.tenants = await tenants()
    await router.push(`/${tenant.slug}`)
  } catch (e: any) {
    error.value = e.message || 'Tenant konnte nicht erstellt werden'
  }
}

async function skipTenant() {
  await router.push('/')
}
</script>

<template>
  <section class="card login-card">
    <h1>Setup</h1>
    <form v-if="step === 'admin'" class="form" @submit.prevent="submitAdmin">
      <label>
        E-Mail
        <input v-model="email" type="email" autocomplete="email" required />
      </label>
      <label>
        Passwort
        <input v-model="password" type="password" autocomplete="new-password" required minlength="8" />
      </label>
      <label>
        Anzeigename
        <input v-model="displayName" required />
      </label>
      <button type="submit">Admin erstellen</button>
    </form>

    <form v-else class="form" @submit.prevent="submitTenant">
      <label>
        Tenant Slug
        <input v-model="tenantName" autocomplete="off" required />
      </label>
      <label>
        Tenant Name
        <input v-model="tenantName" required />
      </label>
      <div class="button-row">
        <button type="submit">Tenant erstellen</button>
        <button type="button" class="secondary-button" @click="skipTenant">Überspringen</button>
      </div>
    </form>
    <p v-if="error" class="error">{{ error }}</p>
  </section>
</template>
