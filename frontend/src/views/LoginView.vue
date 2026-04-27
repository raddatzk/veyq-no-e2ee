<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { authState } from '../authState'
import { login } from '../api/authClient'
import { tenants } from '../api/tenantClient'

const router = useRouter()
const email = ref('')
const password = ref('')
const error = ref('')

async function submit() {
  error.value = ''
  try {
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
    await router.push('/')
  } catch (e: any) {
    error.value = e.message || 'Login fehlgeschlagen'
  }
}

onMounted(async () => {
  if (authState.isAuthenticated) {
    await router.push('/')
  }
})
</script>

<template>
  <Card class="login-card">
    <template #title>Login</template>
    <template #content>
    <form class="form" @submit.prevent="submit">
      <label>
        E-Mail
        <InputText v-model="email" type="email" autocomplete="email" required />
      </label>
      <label>
        Passwort
        <Password v-model="password" autocomplete="current-password" required :feedback="false" toggle-mask fluid />
      </label>
      <Button type="submit" label="Einloggen" icon="pi pi-sign-in" />
    </form>
    <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
    </template>
  </Card>
</template>
