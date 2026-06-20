<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { confirmPasswordReset, parseResetHash } from '../features/account/resetPassword/resetPassword'
import { usePwnedWarning } from '../features/account/passwordWarning'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../shared/passwordPolicy'
import { session } from '../app/session'
import { setAuthPrefill } from '../app/authPrefill'
import { t } from '../i18n/i18n'

const route = useRoute()
const router = useRouter()
// The reset email link carries the one-time token in the URL fragment (#reset=…) or as a `token` query.
const token = parseResetHash(window.location.hash) || (typeof route.query.token === 'string' ? route.query.token : '')
const secretKey = ref('')
const password = ref('')
const { breachCount } = usePwnedWarning(password)
const busy = ref(false)
const error = ref('')

async function submit() {
  if (password.value.length < MIN_PASSWORD_LENGTH || !token) return
  error.value = ''
  busy.value = true
  try {
    const { email } = await confirmPasswordReset(session.apiBase, token, secretKey.value, password.value)
    setAuthPrefill(email, secretKey.value)
    await router.push({ name: 'login' })
  } catch {
    error.value = t('reset.failed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('reset.title') }}</template>
    <template #content>
      <Message v-if="!token" severity="error" :closable="false">{{ t('reset.invalidToken') }}</Message>
      <form v-else class="veyq-form" @submit.prevent="submit">
        <p class="veyq-form-copy">{{ t('reset.confirmHint') }}</p>
        <label>
          {{ t('common.secretKey') }}
          <Password v-model="secretKey" input-id="reset-secret" :input-props="{ autocomplete: 'off' }" required :feedback="false" toggle-mask fluid />
        </label>
        <label>
          {{ t('reset.newPassword') }}
          <Password v-model="password" input-id="reset-password" :input-props="{ autocomplete: 'new-password' }" required :feedback="false" toggle-mask fluid />
        </label>
        <Message v-if="breachCount > 0" severity="warn" :closable="false">{{ t('password.pwnedWarning', { count: breachCount }) }}</Message>
        <small v-if="passwordHintKey(password)" class="field-hint">{{ t(passwordHintKey(password)!) }}</small>
        <Button
          type="submit"
          :label="t('reset.setPassword')"
          icon="pi pi-check"
          :loading="busy"
          :disabled="password.length < MIN_PASSWORD_LENGTH"
        />
      </form>
      <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
    </template>
  </Card>
</template>
