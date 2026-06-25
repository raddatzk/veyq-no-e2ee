<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { login, logout } from '../features/account/login/login'
import { unlockMasterKey } from '../features/account/unlock/unlock'
import { vaultState, clearVault } from '../app/vault'
import { takeAuthPrefill } from '../app/authPrefill'

// uc.account.login + uc.account.unlock (web). Login is challenge-response sign-in (auth only); a successful
// sign-in lands authenticated-but-locked. Unlock then opens the Master Key on-device from the password
// (still held here from the just-completed login) + the Secret Key pasted from the Emergency Kit. QR / file
// import of the Secret Key and remembered-session unlock belong to deferred use-cases.
const { t } = useI18n()
const email = ref('')
const password = ref('')
const secretKey = ref('')
const busy = ref(false)
const error = ref('')
const loggedIn = ref(false)

async function submit() {
  error.value = ''
  busy.value = true
  try {
    await login(email.value, password.value)
    loggedIn.value = true
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
    // The password is reused from the just-completed login (held in `password`, never persisted).
    await unlockMasterKey(password.value, secretKey.value)
    // Drop both inputs the moment the KEK is derived — the vault now holds the keys, in memory only.
    password.value = ''
    secretKey.value = ''
  } catch {
    // Wrong password, wrong Secret Key, or no PASSWORD wrap all surface as one generic failure (E1/E2).
    error.value = t('unlock.failed')
  } finally {
    busy.value = false
  }
}

async function signOut() {
  await logout()
  clearVault()
  loggedIn.value = false
  password.value = ''
  secretKey.value = ''
  error.value = ''
}

onMounted(() => {
  // Right after first-run setup the owner has just seen their email + Secret Key, so setup hands both over
  // (in memory, consumed once) to pre-fill the form — the email for sign-in and the Secret Key for the
  // unlock step that follows. A returning user has no prefill and pastes their Secret Key from the kit.
  const prefill = takeAuthPrefill()
  if (prefill) {
    email.value = prefill.email
    secretKey.value = prefill.secretKey
  }
})
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('login.title') }}</template>
    <template #content>
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

      <div v-else class="veyq-form">
        <Message severity="success" :closable="false">{{ t('unlock.unlockedTitle') }}</Message>
        <p class="veyq-form-copy">{{ t('unlock.unlockedNote') }}</p>
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
