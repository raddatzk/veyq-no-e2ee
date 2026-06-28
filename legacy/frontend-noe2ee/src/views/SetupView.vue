<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { toDataURL } from 'qrcode'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import PasskeyNameDialog from '../components/auth/PasskeyNameDialog.vue'
import RegistrationPrivacyOptIns from '../components/auth/RegistrationPrivacyOptIns.vue'
import { login } from '../api/authClient'
import { setupAdmin, setupAdminWithPasskey } from '../api/setupClient'
import { createTenant, tenants } from '../api/tenantClient'
import {
  createTotpEnrollment,
  enableTotp,
  listMyPasskeys,
  updateMyPasskey,
  type PasskeySummary,
  type TotpEnrollmentResponse
} from '../features/settings/api'
import { toPermalink } from '../composables/usePermalink'
import { useAuthState } from '../shared/state/useAuthState'
import { useServerState } from '../shared/state/useServerState'
import { getErrorMessage } from '../shared/utils/errors'

const router = useRouter()
const { t } = useI18n()
const { authState, setAuthenticated, setTenants } = useAuthState()
const { setSetupCompleted } = useServerState()
const step = ref<'admin' | 'totp' | 'privacy' | 'tenant'>('admin')
const tenantDisplayName = ref('Friends')
const tenantName = ref('friends')
const tenantDomain = ref(window.location.host)
const tenantOwnerEmail = ref('')
const password = ref('')
const displayName = ref('Administrator')
const email = ref('')
const error = ref('')
const savingAdmin = ref(false)
const savingAdminWithPasskey = ref(false)
const savingPasskeyName = ref(false)
const savingTotp = ref(false)
const savingTenant = ref(false)
const namingPasskey = ref<PasskeySummary | null>(null)
const totpEnrollment = ref<TotpEnrollmentResponse | null>(null)
const totpQrCodeUrl = ref('')
const totpCode = ref('')
const showTotpManualSetup = ref(false)
const authMethod = ref<'passkey' | 'password'>('passkey')
const newPasswordInputProps: Record<string, string> = {
  name: 'password',
  autocomplete: 'new-password',
  passwordrules: 'minlength: 10;'
}
const canSubmitTenant = computed(
  () =>
    tenantName.value.trim().length > 0 &&
    tenantDisplayName.value.trim().length > 0 &&
    tenantDomain.value.trim().length > 0 &&
    tenantOwnerEmail.value.trim().length > 0
)
const canSubmitAdmin = computed(() => {
  if (savingAdmin.value || savingAdminWithPasskey.value) return false
  if (!email.value.trim()) return false
  if (!displayName.value.trim()) return false
  if (authMethod.value === 'password' && !password.value) return false
  return true
})

watch(tenantDisplayName, (value) => {
  tenantName.value = toPermalink(value)
})

async function submitAdmin() {
  error.value = ''
  if (savingAdmin.value || savingAdminWithPasskey.value) return
  if (!email.value.trim()) {
    error.value = t('auth.emailRequired')
    return
  }
  const adminDisplayName = displayName.value.trim()
  if (!adminDisplayName) {
    error.value = t('auth.displayNameRequired')
    return
  }
  if (authMethod.value === 'password' && !password.value) {
    error.value = t('auth.passwordRequired')
    return
  }
  const withPasskey = authMethod.value === 'passkey'
  savingAdmin.value = !withPasskey
  savingAdminWithPasskey.value = withPasskey
  try {
    const user = withPasskey
      ? await setupAdminWithPasskey(email.value, adminDisplayName)
      : await setupAdminWithPassword()
    const authenticatedUser = {
      userId: user.userId,
      email: user.email,
      displayName: user.displayName,
      permalink: user.permalink,
      serverRole: user.serverRole,
      pendingEmail: user.pendingEmail,
      passkeyCount: user.passkeyCount,
      hasPassword: user.hasPassword
    }
    setAuthenticated(
      authenticatedUser,
      await tenants()
    )
    setSetupCompleted(true)
    if (withPasskey) {
      await showLatestPasskeyNameDialog()
    } else {
      await showSetupTotp()
    }
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('setup.adminCreateFailed'))
  } finally {
    savingAdmin.value = false
    savingAdminWithPasskey.value = false
  }
}

async function showSetupTotp() {
  step.value = 'totp'
  totpCode.value = ''
  showTotpManualSetup.value = false
  try {
    const enrollment = await createTotpEnrollment()
    totpEnrollment.value = enrollment
    totpQrCodeUrl.value = await toDataURL(enrollment.provisioningUri, {
      errorCorrectionLevel: 'M',
      margin: 4,
      width: 240
    })
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('auth.inviteTotpSetupFailed'))
  }
}

async function submitTotpSetup() {
  if (!totpEnrollment.value) return
  savingTotp.value = true
  error.value = ''
  try {
    const user = await enableTotp(password.value, totpEnrollment.value.secret, totpCode.value)
    if (authState.user?.userId === user.userId) {
      authState.user.totpEnabled = user.totpEnabled
    }
    skipTotpSetup()
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('profile.totpEnableFailed'))
  } finally {
    savingTotp.value = false
  }
}

function skipTotpSetup() {
  step.value = 'privacy'
  password.value = ''
  totpEnrollment.value = null
  totpQrCodeUrl.value = ''
  totpCode.value = ''
  showTotpManualSetup.value = false
}

async function showLatestPasskeyNameDialog() {
  try {
    const summary = await listMyPasskeys()
    namingPasskey.value = summary.passkeys[0] ?? null
  } catch {
    namingPasskey.value = null
  }
  if (!namingPasskey.value) step.value = 'privacy'
}

async function saveSetupPasskeyName(displayName: string) {
  if (!namingPasskey.value) return
  savingPasskeyName.value = true
  error.value = ''
  try {
    await updateMyPasskey(namingPasskey.value.passkeyId, displayName)
    namingPasskey.value = null
    step.value = 'privacy'
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('profile.passkeyRenameFailed'))
  } finally {
    savingPasskeyName.value = false
  }
}

function skipSetupPasskeyName() {
  if (!namingPasskey.value) return
  namingPasskey.value = null
  step.value = 'privacy'
}

async function setupAdminWithPassword() {
  await setupAdmin({
    password: password.value,
    displayName: displayName.value.trim(),
    email: email.value
  })
  return login(email.value, password.value)
}

async function submitTenant() {
  error.value = ''
  if (savingTenant.value || !canSubmitTenant.value) return
  savingTenant.value = true
  try {
    await createTenant(
      tenantName.value,
      tenantDisplayName.value,
      tenantDomain.value,
      tenantOwnerEmail.value
    )
    await refreshTenantsAfterCreation()
    await router.push('/')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('setup.tenantCreateFailed'))
  } finally {
    savingTenant.value = false
  }
}

async function refreshTenantsAfterCreation() {
  try {
    setTenants(await tenants())
  } catch {
    // Tenant creation already succeeded; the bootstrap call refreshes tenant state after navigation.
  }
}

function finishPrivacyOptIns() {
  step.value = 'tenant'
}

async function skipTenant() {
  if (savingTenant.value) return
  await router.push('/')
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('setup.title') }}</template>
    <template #content>
      <form v-if="step === 'admin'" class="veyq-form" method="post" action="/api/setup/admin" @submit.prevent="submitAdmin">
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
        <label for="setup-admin-email">
          {{ t('common.email') }}
          <InputText
            id="setup-admin-email"
            v-model="email"
            type="email"
            name="email"
            autocomplete="username"
            required
          />
        </label>
        <label v-if="authMethod === 'password'" for="setup-admin-password">
          {{ t('common.password') }}
          <Password
            v-model="password"
            input-id="setup-admin-password"
            :input-props="newPasswordInputProps"
            required
            minlength="10"
            toggle-mask
            fluid
          />
        </label>
        <label>
          {{ t('auth.displayName') }}
          <InputText v-model="displayName" name="name" autocomplete="name" required />
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
          :disabled="!canSubmitAdmin"
        />
        <Button
          v-else
          type="submit"
          :label="t('setup.adminCreate')"
          icon="pi pi-user-plus"
          :loading="savingAdmin"
          :disabled="!canSubmitAdmin"
        />
      </form>
      <PasskeyNameDialog
        :visible="Boolean(namingPasskey)"
        :passkey="namingPasskey"
        :saving="savingPasskeyName"
        @update:visible="(visible) => { if (!visible) skipSetupPasskeyName() }"
        @save="saveSetupPasskeyName"
        @skip="skipSetupPasskeyName"
      />

      <RegistrationPrivacyOptIns v-if="step === 'privacy'" @complete="finishPrivacyOptIns" />

      <form v-if="step === 'totp'" class="veyq-form invite-totp-setup" @submit.prevent="submitTotpSetup">
        <div>
          <h2>{{ t('auth.inviteTotpTitle') }}</h2>
          <p>{{ t('auth.inviteTotpCopy') }}</p>
        </div>
        <template v-if="totpEnrollment">
          <div class="totp-qr-panel">
            <div class="totp-qr-code">
              <img :src="totpQrCodeUrl" :alt="t('profile.totpQrAlt')" />
            </div>
            <p>{{ t('profile.totpQrCopy') }}</p>
          </div>
          <div v-if="!showTotpManualSetup" class="veyq-button-row">
            <Button
              type="button"
              :label="t('profile.totpShowManual')"
              icon="pi pi-list"
              severity="secondary"
              text
              @click="showTotpManualSetup = true"
            />
          </div>
          <label v-else>
            {{ t('profile.totpSecret') }}
            <InputText :model-value="totpEnrollment.secret" readonly autocomplete="off" />
          </label>
          <label>
            {{ t('auth.totpCode') }}
            <InputText v-model="totpCode" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required />
          </label>
        </template>
        <div class="veyq-button-row">
          <Button
            type="button"
            :label="t('auth.inviteTotpSkip')"
            icon="pi pi-arrow-right"
            severity="secondary"
            outlined
            @click="skipTotpSetup"
          />
          <Button
            type="submit"
            :label="t('profile.totpEnable')"
            icon="pi pi-shield"
            :disabled="!totpEnrollment || !totpCode"
            :loading="savingTotp"
          />
        </div>
      </form>

      <form v-if="step === 'tenant'" class="veyq-form" @submit.prevent="submitTenant">
        <label>
          {{ t('setup.tenantName') }}
          <InputText v-model="tenantDisplayName" required />
        </label>
        <label>
          {{ t('common.domain') }}
          <InputText v-model="tenantDomain" autocomplete="off" required />
        </label>
        <label>
          {{ t('setup.tenantOwnerEmail') }}
          <InputText v-model="tenantOwnerEmail" type="email" autocomplete="email" required />
        </label>
        <div class="veyq-button-row">
          <Button
            type="button"
            :label="t('setup.skip')"
            severity="secondary"
            outlined
            :disabled="savingTenant"
            @click="skipTenant"
          />
          <Button
            type="submit"
            :label="t('setup.tenantCreate')"
            icon="pi pi-plus"
            :disabled="!canSubmitTenant || savingTenant"
            :loading="savingTenant"
          />
        </div>
      </form>
      <Message v-if="error" severity="error" :closable="false" class="form-message">{{
        error
      }}</Message>
    </template>
  </Card>
</template>
