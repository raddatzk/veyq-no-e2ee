<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { login, logout } from '../features/account/login/login'
import { unlockMasterKey, unlockWithDevice } from '../features/account/unlock/unlock'
import { trustThisDevice, untrustThisDevice, isTrusted, isTrustSupported } from '../features/account/trustDevice/trustDevice'
import { vaultState, masterKey } from '../app/vault'
import { takeAuthPrefill } from '../app/authPrefill'

// uc.account.login + unlock + trust-device (web). Login gates a session; unlock opens the Master Key. A
// trusted device (a DEVICE wrap, ADR 0005) unlocks with only the password (carried from login); the floor
// path adds the Secret Key. "Trust this device" enrols at unlock time, reusing the just-entered password.
const { t } = useI18n()
const email = ref('')
const password = ref('')
const secretKey = ref('')
const busy = ref(false)
const error = ref('')
const loggedIn = ref(false)
const deviceTrusted = ref(false)
const unlockMode = ref<'device' | 'password'>('password')
const trustChecked = ref(false)
const trustSupported = isTrustSupported()

// A Secret Key handed over by setup is parked here (a plain local, not a bound input) until login succeeds.
let pendingSecretKey = ''

async function submit() {
  error.value = ''
  busy.value = true
  try {
    await login(email.value, password.value)
    loggedIn.value = true
    deviceTrusted.value = await isTrusted(email.value)
    unlockMode.value = deviceTrusted.value ? 'device' : 'password'
    secretKey.value = pendingSecretKey
    pendingSecretKey = ''
  } catch {
    // wrong password and unknown account both surface as the same generic failure (non-enumerable)
    error.value = t('login.failed')
  } finally {
    busy.value = false
  }
}

async function unlock() {
  error.value = ''
  busy.value = true
  try {
    if (unlockMode.value === 'device') {
      // Trusted device: unlock with only the carried password.
      const ok = await unlockWithDevice(email.value, password.value)
      if (ok) {
        password.value = ''
      } else {
        // the local key or the server wrap is gone — fall back to the Secret-Key floor
        deviceTrusted.value = false
        unlockMode.value = 'password'
        error.value = t('unlock.deviceUnavailable')
      }
      return
    }

    // Floor: password (carried from login) + Secret Key.
    await unlockMasterKey(password.value, secretKey.value)
    if (trustChecked.value && trustSupported) {
      // Enrol now, while the just-entered (known-correct) password is still in hand and the vault is open.
      try {
        await trustThisDevice(email.value, password.value, masterKey()!)
        deviceTrusted.value = true
      } catch {
        /* enrolment is best-effort — the unlock itself succeeded */
      }
    }
    password.value = ''
    secretKey.value = ''
  } catch {
    // Wrong password / Secret Key (or no wrap) — one generic failure. Keep the inputs for a retry.
    error.value = t('unlock.failed')
  } finally {
    busy.value = false
  }
}

function useSecretKeyInstead() {
  unlockMode.value = 'password'
  error.value = ''
}

async function untrust() {
  busy.value = true
  try {
    await untrustThisDevice(email.value)
    deviceTrusted.value = false
  } finally {
    busy.value = false
  }
}

async function signOut() {
  await logout() // revokes the session, drops tokens, and re-locks the vault (centralised in logout)
  loggedIn.value = false
  password.value = ''
  secretKey.value = ''
  pendingSecretKey = ''
  trustChecked.value = false
  unlockMode.value = 'password'
  error.value = ''
}

onMounted(() => {
  // Setup hands over email + Secret Key once. The email pre-fills sign-in; the Secret Key is parked off the
  // reactive ref and surfaces only once login succeeds. A returning user has no prefill.
  const prefill = takeAuthPrefill()
  if (prefill) {
    email.value = prefill.email
    pendingSecretKey = prefill.secretKey
  }
})
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('login.title') }}</template>
    <template #content>
      <!-- Sign in -->
      <form v-if="!loggedIn" class="veyq-form" @submit.prevent="submit">
        <label>
          {{ t('common.email') }}
          <InputText v-model="email" type="email" name="email" autocomplete="username" required />
        </label>
        <label>
          {{ t('common.password') }}
          <Password
            v-model="password"
            input-id="login-password"
            :input-props="{ name: 'password', autocomplete: 'current-password' }"
            required
            :feedback="false"
            toggle-mask
            fluid
          />
        </label>
        <Button
          type="submit"
          :label="busy ? t('login.submitting') : t('login.submit')"
          icon="pi pi-sign-in"
          :loading="busy"
        />
        <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
      </form>

      <!-- Unlock: trusted device (password only) -->
      <form v-else-if="!vaultState.unlocked && unlockMode === 'device'" class="veyq-form" @submit.prevent="unlock">
        <Message severity="info" :closable="false">{{ t('unlock.title') }}</Message>
        <p class="veyq-form-copy">{{ t('unlock.deviceNote') }}</p>
        <Button
          type="submit"
          :label="busy ? t('unlock.submitting') : t('unlock.submit')"
          icon="pi pi-unlock"
          :loading="busy"
        />
        <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
        <Button
          type="button"
          :label="t('unlock.anotherWay')"
          icon="pi pi-key"
          severity="secondary"
          text
          @click="useSecretKeyInstead"
        />
        <Button
          type="button"
          :label="t('login.signOut')"
          icon="pi pi-sign-out"
          severity="secondary"
          text
          @click="signOut"
        />
      </form>

      <!-- Unlock: Secret-Key floor -->
      <form v-else-if="!vaultState.unlocked" class="veyq-form" @submit.prevent="unlock">
        <Message severity="info" :closable="false">{{ t('unlock.title') }}</Message>
        <p class="veyq-form-copy">{{ t('unlock.note') }}</p>
        <label>
          {{ t('unlock.secretKey') }}
          <Password
            v-model="secretKey"
            input-id="unlock-secret-key"
            :input-props="{ name: 'secret-key', autocomplete: 'off', spellcheck: false }"
            required
            :feedback="false"
            toggle-mask
            fluid
          />
        </label>
        <label v-if="trustSupported && !deviceTrusted" class="checkbox-row">
          <input v-model="trustChecked" type="checkbox" name="trust-device" />
          <span>
            {{ t('unlock.trustDevice') }}
            <small class="field-hint">{{ t('unlock.trustDeviceHint') }}</small>
          </span>
        </label>
        <Button
          type="submit"
          :label="busy ? t('unlock.submitting') : t('unlock.submit')"
          icon="pi pi-unlock"
          :loading="busy"
        />
        <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
        <Button
          type="button"
          :label="t('login.signOut')"
          icon="pi pi-sign-out"
          severity="secondary"
          text
          @click="signOut"
        />
      </form>

      <!-- Unlocked -->
      <div v-else class="veyq-form">
        <Message severity="success" :closable="false">{{ t('unlock.unlockedTitle') }}</Message>
        <p class="veyq-form-copy">{{ t('unlock.unlockedNote') }}</p>
        <Button
          v-if="deviceTrusted"
          type="button"
          :label="t('unlock.untrust')"
          icon="pi pi-shield"
          severity="secondary"
          text
          :loading="busy"
          @click="untrust"
        />
        <Button
          type="button"
          :label="t('login.signOut')"
          icon="pi pi-sign-out"
          severity="secondary"
          @click="signOut"
        />
      </div>
    </template>
  </Card>
</template>
