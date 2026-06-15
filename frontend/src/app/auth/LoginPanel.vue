<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../../features/account/login/login'
import { t } from '../../i18n/i18n'
import { session, setUnlocked } from '../session'
import { deviceUnlockEnabled, deviceUnlockSupported, enableDeviceUnlock, unlockWithDevice } from '../../features/account/deviceUnlock/deviceUnlock'
import { parseKit } from '../../features/account/emergencyKit/emergencyKit'
import { requestPasswordReset } from '../../features/account/resetPassword/resetPassword'
import QrScanner from './QrScanner.vue'

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
const scanning = ref(false)
const canScan = !!navigator.mediaDevices?.getUserMedia
const forgot = ref(false)
const resetSent = ref(false)

async function sendReset() {
  error.value = ''
  busy.value = true
  try {
    await requestPasswordReset(session.apiBase, email.value)
    resetSent.value = true
  } catch {
    error.value = t('reset.requestFailed')
  } finally {
    busy.value = false
  }
}

function applyKit(text: string) {
  const kit = parseKit(text)
  if (!kit) { error.value = t('kit.importFailed'); return false }
  email.value = kit.email
  secretKey.value = kit.secretKey
  return true
}

function onScanned(text: string) {
  scanning.value = false
  applyKit(text)
}

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
  const text = await file.text()
  input.value = ''
  applyKit(text)
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
      <!-- Forgot-password: request a reset email (Secret Key + email are the two recovery factors). -->
      <template v-if="forgot">
        <h2 v-if="!props.embedded" class="auth-title" style="font-size: 1.1rem">{{ t('reset.title') }}</h2>
        <p class="auth-sub">{{ t('reset.requestHint') }}</p>
        <template v-if="resetSent">
          <div class="auth-msg">{{ t('reset.sent') }}</div>
          <button class="btn btn-ghost" type="button" style="width: 100%; margin-top: 0.6rem" @click="forgot = false; resetSent = false">{{ t('reset.backToLogin') }}</button>
        </template>
        <form v-else class="veyq-form" @submit.prevent="sendReset">
          <label>{{ t('common.email') }}<input v-model="email" type="email" autocomplete="username" required autofocus /></label>
          <button class="btn btn-primary" type="submit" :disabled="busy">{{ busy ? t('login.submitting') : t('reset.sendLink') }}</button>
          <button class="btn btn-ghost" type="button" @click="forgot = false">{{ t('reset.backToLogin') }}</button>
        </form>
        <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
      </template>
      <template v-else>
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
      <button class="btn btn-ghost auth-forgot" type="button" @click="forgot = true; error = ''">{{ t('reset.forgot') }}</button>
      <label class="kit-import">
        <span>{{ t('kit.import') }}</span>
        <input type="file" accept=".pdf,.veyqkit,application/pdf,text/plain" @change="importKit" />
        <span class="field-hint">{{ t('kit.importHint') }}</span>
      </label>
      <button v-if="canScan" class="btn btn-ghost" type="button" style="width: 100%; margin-top: 0.6rem" @click="scanning = true">
        📷 {{ t('kit.scan') }}
      </button>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
      </template>
    </div>
    <QrScanner v-if="scanning" @decoded="onScanned" @close="scanning = false" />
  </div>
</template>
