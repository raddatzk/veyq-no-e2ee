<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { login, logout } from '../features/account/login/login'
import { takeAuthPrefill } from '../app/authPrefill'

// uc.account.login (web) — challenge-response sign-in (auth only). Ported from the legacy login view,
// scoped to email + password: the Secret Key / unlock, remembered login, QR import and password reset
// belong to deferred use-cases. A successful sign-in lands in an authenticated-but-locked state.
const { t } = useI18n()
const email = ref('')
const password = ref('')
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

async function signOut() {
  await logout()
  loggedIn.value = false
  password.value = ''
}

onMounted(() => {
  // Setup hands over the email so the owner doesn't retype it right after first-run setup.
  const prefill = takeAuthPrefill()
  if (prefill) email.value = prefill.email
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

      <div v-else class="veyq-form">
        <Message severity="success" :closable="false">{{ t('login.lockedTitle') }}</Message>
        <p class="veyq-form-copy">{{ t('login.lockedNote') }}</p>
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
