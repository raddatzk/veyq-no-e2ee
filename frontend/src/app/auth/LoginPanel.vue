<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../../features/account/login/login'
import { t } from '../../i18n/i18n'
import { session, setUnlocked } from '../session'
import { deviceUnlockEnabled, deviceUnlockSupported, enableDeviceUnlock, unlockWithDevice } from '../../features/account/deviceUnlock/deviceUnlock'
import { parseKit } from '../../features/account/emergencyKit/emergencyKit'

const props = defineProps<{ prefill?: { email?: string; secretKey?: string }; embedded?: boolean }>()
const email = ref(props.prefill?.email ?? '')
const password = ref('')
const secretKey = ref(props.prefill?.secretKey ?? '')
const remember = ref(false)
const busy = ref(false)
const error = ref('')
const host = window.location.host
const canRemember = deviceUnlockSupported()
const hasDeviceUnlock = ref(deviceUnlockEnabled())

async function submit() {
  error.value = ''
  busy.value = true
  try {
    setUnlocked(await login(session.apiBase, email.value, password.value, secretKey.value))
    // Set up biometric/passkey unlock for next time (best-effort — a cancelled passkey doesn't block login).
    if (remember.value && canRemember) {
      try { await enableDeviceUnlock({ email: email.value, password: password.value, secretKey: secretKey.value }) } catch { /* user cancelled */ }
    }
  } catch {
    error.value = t('login.failed')
  } finally {
    busy.value = false
  }
}

async function importKit(e: Event) {
  error.value = ''
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  // The payload lives as ASCII in the QR text, the .veyqkit body, or the PDF's Keywords metadata —
  // reading the file as text surfaces it regardless of container.
  const kit = parseKit(await file.text())
  input.value = ''
  if (!kit) { error.value = t('kit.importFailed'); return }
  email.value = kit.email
  secretKey.value = kit.secretKey
}

async function unlock() {
  error.value = ''
  busy.value = true
  try {
    const creds = await unlockWithDevice()
    setUnlocked(await login(session.apiBase, creds.email, creds.password, creds.secretKey))
  } catch {
    error.value = t('login.unlockFailed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div :class="props.embedded ? '' : 'auth-screen'">
    <div :class="props.embedded ? '' : 'auth-card'">
      <template v-if="!props.embedded">
        <p class="auth-wordmark">veyq</p>
        <h1 class="auth-title">{{ t('login.title') }}</h1>
        <p class="auth-sub">{{ host }}</p>
      </template>
      <button v-if="hasDeviceUnlock && !props.embedded" class="btn btn-primary auth-unlock" type="button" :disabled="busy" @click="unlock">
        🔓 {{ t('login.unlock') }}
      </button>
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
        <label v-if="canRemember" class="remember-row">
          <input v-model="remember" type="checkbox" />
          <span>{{ t('login.remember') }}</span>
        </label>
        <button class="btn btn-primary" type="submit" :disabled="busy">
          {{ busy ? t('login.submitting') : t('login.submit') }}
        </button>
      </form>
      <label class="kit-import">
        <span>{{ t('kit.import') }}</span>
        <input type="file" accept=".pdf,.veyqkit,application/pdf,text/plain" @change="importKit" />
        <span class="field-hint">{{ t('kit.importHint') }}</span>
      </label>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
    </div>
  </div>
</template>
