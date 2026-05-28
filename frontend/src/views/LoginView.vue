<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { forgotPassword, login, loginWithPasskey } from '../api/authClient'
import { tenants } from '../api/tenantClient'
import { useAuthState } from '../shared/state/useAuthState'
import { getErrorMessage } from '../shared/utils/errors'

const router = useRouter()
const { t } = useI18n()
const { authState, setAuthenticated } = useAuthState()
const email = ref('')
const password = ref('')
const error = ref('')
const message = ref('')
const sendingReset = ref(false)
const passkeyLoading = ref(false)
const authMethod = ref<'passkey' | 'password'>('passkey')

async function submit() {
  error.value = ''
  message.value = ''
  try {
    const user = await login(email.value, password.value)
    setAuthenticated(
      {
        userId: user.userId,
        email: user.email,
        displayName: user.displayName,
        permalink: user.permalink,
        serverRole: user.serverRole,
        pendingEmail: user.pendingEmail,
        passkeyCount: user.passkeyCount,
        hasPassword: user.hasPassword
      },
      await tenants()
    )
    await router.push(resolveRedirect())
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('auth.loginFailed'))
  }
}

async function submitPasskey() {
  error.value = ''
  message.value = ''
  passkeyLoading.value = true
  try {
    const user = await loginWithPasskey()
    setAuthenticated(
      {
        userId: user.userId,
        email: user.email,
        displayName: user.displayName,
        permalink: user.permalink,
        serverRole: user.serverRole,
        pendingEmail: user.pendingEmail,
        passkeyCount: user.passkeyCount,
        hasPassword: user.hasPassword
      },
      await tenants()
    )
    await router.push(resolveRedirect())
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('auth.passkeyLoginFailed'))
  } finally {
    passkeyLoading.value = false
  }
}

async function requestPasswordReset() {
  error.value = ''
  message.value = ''
  if (!email.value.trim()) {
    error.value = t('auth.passwordRequiredEmail')
    return
  }
  sendingReset.value = true
  try {
    await forgotPassword(email.value)
    message.value = t('auth.passwordResetRequested')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('auth.passwordResetRequestFailed'))
  } finally {
    sendingReset.value = false
  }
}

function resolveRedirect(): string {
  const redirect = router.currentRoute.value.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/'
}

onMounted(async () => {
  if (authState.isAuthenticated) {
    await router.push(resolveRedirect())
  }
})
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('auth.login') }}</template>
    <template #content>
      <form class="veyq-form" @submit.prevent="authMethod === 'passkey' ? submitPasskey() : submit()">
        <div class="auth-method-switch" role="tablist" :aria-label="t('auth.authMethod')">
          <button
            type="button"
            :class="{ active: authMethod === 'passkey' }"
            role="tab"
            :aria-selected="authMethod === 'passkey'"
            @click="authMethod = 'passkey'"
          >
            {{ t('auth.passkey') }}
          </button>
          <button
            type="button"
            :class="{ active: authMethod === 'password' }"
            role="tab"
            :aria-selected="authMethod === 'password'"
            @click="authMethod = 'password'"
          >
            {{ t('common.password') }}
          </button>
        </div>
        <label v-if="authMethod === 'password'">
          {{ t('common.email') }}
          <InputText v-model="email" type="email" autocomplete="email" required />
        </label>
        <label v-if="authMethod === 'password'">
          {{ t('common.password') }}
          <Password
            v-model="password"
            autocomplete="current-password"
            required
            :feedback="false"
            toggle-mask
            fluid
          />
        </label>
        <Button
          v-if="authMethod === 'passkey'"
          type="submit"
          :label="t('auth.loginWithPasskey')"
          icon="pi pi-key"
          :loading="passkeyLoading"
        />
        <Button v-else type="submit" :label="t('auth.login')" icon="pi pi-sign-in" />
        <Button
          v-if="authMethod === 'password'"
          type="button"
          :label="t('auth.forgotPassword')"
          icon="pi pi-envelope"
          severity="secondary"
          text
          :loading="sendingReset"
          @click="requestPasswordReset"
        />
      </form>
      <Message v-if="message" severity="success" :closable="false" class="form-message">{{
        message
      }}</Message>
      <Message v-if="error" severity="error" :closable="false" class="form-message">{{
        error
      }}</Message>
    </template>
  </Card>
</template>
