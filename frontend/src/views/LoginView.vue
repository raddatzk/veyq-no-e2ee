<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { authState } from '../authState'
import { login, tenants } from '../api/authClient'

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
  <section class="card login-card">
    <h1>Login</h1>
    <form class="form" @submit.prevent="submit">
      <label>
        E-Mail
        <input v-model="email" type="email" autocomplete="email" required />
      </label>
      <label>
        Passwort
        <input v-model="password" type="password" autocomplete="current-password" required />
      </label>
      <button type="submit">Einloggen</button>
    </form>
    <p v-if="error" class="error">{{ error }}</p>
  </section>
</template>
