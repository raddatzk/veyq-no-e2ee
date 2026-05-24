<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { login } from '../api/authClient'
import { setupAdmin, setupAdminWithPasskey } from '../api/setupClient'
import { createTenant, tenants } from '../api/tenantClient'
import { toPermalink } from '../composables/usePermalink'
import { useAuthState } from '../shared/state/useAuthState'
import { useServerState } from '../shared/state/useServerState'
import { getErrorMessage } from '../shared/utils/errors'

const router = useRouter()
const { t } = useI18n()
const { setAuthenticated, setTenants } = useAuthState()
const { setSetupCompleted } = useServerState()
const step = ref<'admin' | 'tenant'>('admin')
const tenantDisplayName = ref('Friends')
const tenantName = ref('friends')
const tenantDomain = ref(window.location.host)
const tenantPermalinkTouched = ref(false)
const password = ref('')
const displayName = ref('Administrator')
const email = ref('')
const error = ref('')
const savingAdmin = ref(false)
const savingAdminWithPasskey = ref(false)
const authMethod = ref<'passkey' | 'password'>('passkey')

watch(tenantDisplayName, (value) => {
  if (!tenantPermalinkTouched.value) {
    tenantName.value = toPermalink(value)
  }
})

function updateTenantPermalink(value: string | undefined) {
  tenantPermalinkTouched.value = true
  tenantName.value = toPermalink(value || '')
}

async function submitAdmin() {
  error.value = ''
  if (savingAdmin.value || savingAdminWithPasskey.value) return
  const withPasskey = authMethod.value === 'passkey'
  savingAdmin.value = !withPasskey
  savingAdminWithPasskey.value = withPasskey
  try {
    const user = withPasskey
      ? await setupAdminWithPasskey(email.value, displayName.value)
      : await setupAdminWithPassword()
    const authenticatedUser = {
      userId: user.userId,
      email: user.email,
      displayName: user.displayName,
      permalink: user.permalink,
      serverRole: user.serverRole,
      pendingEmail: user.pendingEmail,
      passkeyCount: user.passkeyCount
    }
    setAuthenticated(
      authenticatedUser,
      await tenants()
    )
    setSetupCompleted(true)
    step.value = 'tenant'
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('setup.adminCreateFailed'))
  } finally {
    savingAdmin.value = false
    savingAdminWithPasskey.value = false
  }
}

async function setupAdminWithPassword() {
  await setupAdmin({
    password: password.value,
    displayName: displayName.value,
    email: email.value
  })
  return login(email.value, password.value)
}

async function submitTenant() {
  error.value = ''
  try {
    const tenant = await createTenant(tenantName.value, tenantDisplayName.value, tenantDomain.value)
    setTenants(await tenants())
    await router.push(`/${tenant.name}`)
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('setup.tenantCreateFailed'))
  }
}

async function skipTenant() {
  await router.push('/')
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('setup.title') }}</template>
    <template #content>
      <form v-if="step === 'admin'" class="form" @submit.prevent="submitAdmin">
        <div class="auth-method-switch" role="tablist" :aria-label="t('setup.method')">
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
        <label>
          {{ t('common.email') }}
          <InputText v-model="email" type="email" autocomplete="email" required />
        </label>
        <label v-if="authMethod === 'password'">
          {{ t('common.password') }}
          <Password
            v-model="password"
            autocomplete="new-password"
            required
            minlength="8"
            toggle-mask
            fluid
          />
        </label>
        <label>
          {{ t('auth.displayName') }}
          <InputText v-model="displayName" required />
        </label>
        <Message v-if="authMethod === 'passkey'" severity="info" :closable="false">
          {{ t('setup.passkeyHint') }}
        </Message>
        <Button
          v-if="authMethod === 'passkey'"
          type="submit"
          :label="t('setup.adminCreateWithPasskey')"
          icon="pi pi-key"
          :loading="savingAdminWithPasskey"
        />
        <Button
          v-else
          type="submit"
          :label="t('setup.adminCreate')"
          icon="pi pi-user-plus"
          :loading="savingAdmin"
        />
      </form>

      <form v-else class="form" @submit.prevent="submitTenant">
        <label>
          {{ t('setup.tenantName') }}
          <InputText v-model="tenantDisplayName" required />
        </label>
        <label>
          {{ t('common.permalink') }}
          <InputText
            :model-value="tenantName"
            autocomplete="off"
            required
            @update:model-value="updateTenantPermalink"
          />
        </label>
        <label>
          {{ t('common.domain') }}
          <InputText v-model="tenantDomain" autocomplete="off" required />
        </label>
        <div class="button-row">
          <Button type="submit" :label="t('setup.tenantCreate')" icon="pi pi-plus" />
          <Button
            type="button"
            :label="t('setup.skip')"
            severity="secondary"
            outlined
            @click="skipTenant"
          />
        </div>
      </form>
      <Message v-if="error" severity="error" :closable="false" class="form-message">{{
        error
      }}</Message>
    </template>
  </Card>
</template>
