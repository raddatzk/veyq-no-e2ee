<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import {
  completePasskeyLink,
  createPasskeyLink,
  forgotPasskey,
  forgotPassword,
  login,
  loginWithPasskey,
  passkeyLoginDomains
} from '../api/authClient'
import type { PasskeyLoginDomain, UserSummary } from '../api/generated'
import { tenants } from '../api/tenantClient'
import { useAuthState } from '../shared/state/useAuthState'
import { getErrorMessage } from '../shared/utils/errors'

const router = useRouter()
const { t } = useI18n()
const { authState, setAuthenticated } = useAuthState()
const email = ref('')
const password = ref('')
const passkeyResetEmail = ref('')
const error = ref('')
const message = ref('')
const sendingReset = ref(false)
const passkeyLoading = ref(false)
const linkLoading = ref(false)
const completingLink = ref(false)
const linkingDomain = ref('')
const linkCode = ref('')
const authDomains = ref<PasskeyLoginDomain[]>([])
const authMethod = ref<'passkey' | 'password'>('passkey')

async function submit() {
  error.value = ''
  message.value = ''
  try {
    const user = await login(email.value, password.value)
    await finishAuthenticated(user)
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
    await finishAuthenticated(user)
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

async function requestPasskeyReset() {
  error.value = ''
  message.value = ''
  if (!passkeyResetEmail.value.trim()) {
    error.value = t('auth.passwordRequiredEmail')
    return
  }
  sendingReset.value = true
  try {
    await forgotPasskey(passkeyResetEmail.value)
    message.value = t('auth.passkeyResetRequested')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('auth.passkeyResetRequestFailed'))
  } finally {
    sendingReset.value = false
  }
}

async function startPasskeyLink(domain: PasskeyLoginDomain) {
  error.value = ''
  message.value = ''
  linkingDomain.value = domain.domain
  try {
    const targetOrigin = window.location.origin
    const redirect = resolveRedirect()
    const url = new URL('/login', domain.origin)
    url.searchParams.set('passkeyLinkTarget', targetOrigin)
    url.searchParams.set('redirect', redirect)
    window.location.assign(url.toString())
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('auth.passkeyLinkStartFailed'))
  } finally {
    linkingDomain.value = ''
  }
}

async function continuePasskeyLink() {
  const targetOrigin = passkeyLinkTarget()
  if (!targetOrigin) return
  linkLoading.value = true
  error.value = ''
  message.value = ''
  try {
    const link = await createPasskeyLink(targetOrigin, resolveRedirect())
    const url = new URL('/login', link.targetOrigin)
    url.searchParams.set('redirect', link.redirectPath)
    url.hash = new URLSearchParams({ passkeyLinkCode: link.code }).toString()
    window.location.assign(url.toString())
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('auth.passkeyLinkCreateFailed'))
  } finally {
    linkLoading.value = false
  }
}

async function finishPasskeyLink() {
  const code = linkCode.value
  if (!code) return
  completingLink.value = true
  error.value = ''
  message.value = ''
  try {
    const user = await completePasskeyLink(code)
    await finishAuthenticated(user)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('auth.passkeyLinkCompleteFailed'))
  } finally {
    completingLink.value = false
  }
}

async function finishAuthenticated(user: UserSummary) {
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
  if (passkeyLinkTarget()) {
    await continuePasskeyLink()
    return
  }
  await router.push(resolveRedirect())
}

function resolveRedirect(): string {
  const redirect = router.currentRoute.value.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/'
}

function passkeyLinkTarget(): string {
  const target = router.currentRoute.value.query.passkeyLinkTarget
  return typeof target === 'string' ? target : ''
}

function passkeyLinkCode(): string {
  const hashCode = new URLSearchParams(router.currentRoute.value.hash.replace(/^#/, '')).get('passkeyLinkCode')
  if (hashCode) return hashCode
  const queryCode = router.currentRoute.value.query.passkeyLinkCode
  return typeof queryCode === 'string' ? queryCode : ''
}

async function clearPasskeyLinkCodeFromUrl() {
  const query = { ...router.currentRoute.value.query }
  delete query.passkeyLinkCode
  await router.replace({
    path: router.currentRoute.value.path,
    query,
    hash: ''
  })
}

function currentDomain(): string {
  return window.location.host
}

onMounted(async () => {
  linkCode.value = passkeyLinkCode()
  if (linkCode.value) {
    await clearPasskeyLinkCodeFromUrl()
  }
  try {
    authDomains.value = await passkeyLoginDomains()
  } catch {
    authDomains.value = []
  }
  if (linkCode.value) return
  if (authState.isAuthenticated) {
    if (passkeyLinkTarget()) {
      await continuePasskeyLink()
    } else {
      await router.push(resolveRedirect())
    }
  }
})
</script>

<template>
  <Card class="login-card" :class="{ 'login-card-wide': linkCode }">
    <template #title>{{ t('auth.login') }}</template>
    <template #content>
      <div v-if="linkCode" class="veyq-form">
        <Message severity="info" :closable="false">
          {{ t('auth.passkeyLinkPrompt', { domain: currentDomain() }) }}
        </Message>
        <Message severity="secondary" :closable="false">
          {{ t('auth.passkeyLinkExplanation') }}
        </Message>
        <Button
          type="button"
          class="passkey-link-submit"
          :label="t('auth.passkeyLinkCreate', { domain: currentDomain() })"
          icon="pi pi-key"
          :loading="completingLink"
          @click="finishPasskeyLink"
        />
      </div>
      <form v-else class="veyq-form" @submit.prevent="authMethod === 'passkey' ? submitPasskey() : submit()">
        <Message v-if="passkeyLinkTarget()" severity="info" :closable="false">
          {{ t('auth.passkeyLinkAuthPrompt') }}
        </Message>
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
          :loading="passkeyLoading || linkLoading"
        />
        <label v-if="authMethod === 'passkey'">
          {{ t('common.email') }}
          <InputText v-model="passkeyResetEmail" type="email" autocomplete="email" />
        </label>
        <Button
          v-if="authMethod === 'passkey'"
          type="button"
          :label="t('auth.forgotPasskey')"
          icon="pi pi-envelope"
          severity="secondary"
          text
          :loading="sendingReset"
          @click="requestPasskeyReset"
        />
        <template v-if="authMethod === 'passkey' && authDomains.length">
          <Button
            v-for="domain in authDomains"
            :key="domain.domain"
            type="button"
            :label="t('auth.loginWithDomain', { domain: domain.domain })"
            icon="pi pi-external-link"
            severity="secondary"
            outlined
            :loading="linkingDomain === domain.domain"
            @click="startPasskeyLink(domain)"
          />
        </template>
        <Button v-if="authMethod === 'password'" type="submit" :label="t('auth.login')" icon="pi pi-sign-in" />
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
