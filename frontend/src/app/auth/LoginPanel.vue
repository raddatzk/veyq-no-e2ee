<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../../features/account/login/login'
import { session, setUnlocked } from '../session'

const props = defineProps<{ prefill?: { email?: string; secretKey?: string } }>()
const email = ref(props.prefill?.email ?? '')
const password = ref('')
const secretKey = ref(props.prefill?.secretKey ?? '')
const busy = ref(false)
const error = ref('')
const host = window.location.host

async function submit() {
  error.value = ''
  busy.value = true
  try {
    setUnlocked(await login(session.apiBase, email.value, password.value, secretKey.value))
  } catch {
    error.value = 'Anmeldung fehlgeschlagen. Prüfe E-Mail, Passwort und Secret Key.'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="auth-screen">
    <div class="auth-card">
      <p class="auth-wordmark">veyq</p>
      <h1 class="auth-title">Anmelden</h1>
      <p class="auth-sub">{{ host }}</p>
      <form class="veyq-form" @submit.prevent="submit">
        <label>
          E-Mail
          <input v-model="email" type="email" autocomplete="username" required autofocus />
        </label>
        <label>
          Passwort
          <input v-model="password" type="password" autocomplete="current-password" required />
        </label>
        <label>
          Secret Key
          <input v-model="secretKey" type="password" autocomplete="off" required />
          <span class="field-hint">Aus deinem Emergency Kit.</span>
        </label>
        <button class="btn btn-primary" type="submit" :disabled="busy">
          {{ busy ? 'Melde an…' : 'Anmelden' }}
        </button>
      </form>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
    </div>
  </div>
</template>
