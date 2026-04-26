<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { authState } from '../authState'
import { login } from '../api/authClient'

const router = useRouter()
const username = ref('')
const password = ref('')
const error = ref('')

async function submit() {
  error.value = ''
  try {
    const user = await login(username.value, password.value)
    authState.isAuthenticated = true
    authState.username = user.displayName || user.username
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
        Username
        <input v-model="username" autocomplete="username" required />
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
