<script setup lang="ts">
import { ref } from 'vue'
import { registerServerOwner } from '../../features/admin/setupServer/setupServer'
import { session } from '../session'

const emit = defineEmits<{ done: [email: string, secretKey: string] }>()
const displayName = ref('')
const email = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const kit = ref<string | null>(null)
let kitEmail = ''

async function submit() {
  error.value = ''
  busy.value = true
  try {
    const { emergencyKit } = await registerServerOwner(session.apiBase, email.value, displayName.value, password.value)
    kitEmail = email.value
    kit.value = emergencyKit.secretKey
  } catch {
    error.value = 'Einrichtung fehlgeschlagen. Möglicherweise ist die Instanz schon eingerichtet.'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="auth-screen">
    <div class="auth-card">
      <p class="auth-wordmark">veyq</p>

      <template v-if="!kit">
        <h1 class="auth-title">Server einrichten</h1>
        <p class="auth-sub">Diese Instanz ist noch leer — lege den ersten Server-Owner an.</p>
        <form class="veyq-form" @submit.prevent="submit">
          <label>
            Anzeigename
            <input v-model="displayName" type="text" required autofocus />
          </label>
          <label>
            E-Mail
            <input v-model="email" type="email" autocomplete="username" required />
          </label>
          <label>
            Passwort
            <input v-model="password" type="password" autocomplete="new-password" required />
          </label>
          <button class="btn btn-primary" type="submit" :disabled="busy">
            {{ busy ? 'Richte ein…' : 'Server einrichten' }}
          </button>
        </form>
      </template>

      <template v-else>
        <h1 class="auth-title">Emergency Kit sichern</h1>
        <p class="auth-sub">
          Dein Secret Key. Ohne ihn ist keine Anmeldung möglich — sicher speichern, er wird nicht erneut angezeigt.
        </p>
        <div class="kit-secret">{{ kit }}</div>
        <button class="btn btn-primary" type="button" style="margin-top: 1rem; width: 100%" @click="emit('done', kitEmail, kit!)">
          Gespeichert — weiter zur Anmeldung
        </button>
      </template>

      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
    </div>
  </div>
</template>
