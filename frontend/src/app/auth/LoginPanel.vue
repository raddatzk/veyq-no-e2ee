<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../../features/account/login/login'
import { t } from '../../i18n/i18n'
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
    error.value = t('login.failed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="auth-screen">
    <div class="auth-card">
      <p class="auth-wordmark">veyq</p>
      <h1 class="auth-title">{{ t('login.title') }}</h1>
      <p class="auth-sub">{{ host }}</p>
      <form class="veyq-form" @submit.prevent="submit">
        <label>
          {{ t('common.email') }}
          <input v-model="email" type="email" autocomplete="username" required autofocus />
        </label>
        <label>
          {{ t('common.password') }}
          <input v-model="password" type="password" autocomplete="current-password" required />
        </label>
        <label>
          {{ t('common.secretKey') }}
          <input v-model="secretKey" type="password" autocomplete="off" required />
          <span class="field-hint">{{ t('login.secretHint') }}</span>
        </label>
        <button class="btn btn-primary" type="submit" :disabled="busy">
          {{ busy ? t('login.submitting') : t('login.submit') }}
        </button>
      </form>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
    </div>
  </div>
</template>
