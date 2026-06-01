<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { toDataURL } from 'qrcode'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import PasskeyNameDialog from '../components/auth/PasskeyNameDialog.vue'
import RegistrationPrivacyOptIns from '../components/auth/RegistrationPrivacyOptIns.vue'
import { acceptInvitation, acceptInvitationWithPasskey, invitationMetadata } from '../api/invitationClient'
import { bootstrap } from '../api/entrypointClient'
import {
  createTotpEnrollment,
  enableTotp,
  listMyPasskeys,
  updateMyPasskey,
  type PasskeySummary,
  type TotpEnrollmentResponse
} from '../features/settings/api'
import { useAuthState } from '../shared/state/useAuthState'
import { getErrorMessage } from '../shared/utils/errors'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { authState, setAuthenticated } = useAuthState()
const displayName = ref('')
const invitedEmail = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)
const savingPasskeyName = ref(false)
const namingPasskey = ref<PasskeySummary | null>(null)
const pendingRedirect = ref('/')
const authMethod = ref<'passkey' | 'password'>('passkey')
const showPrivacyOptIns = ref(false)
const showTotpSetup = ref(false)
const totpEnrollment = ref<TotpEnrollmentResponse | null>(null)
const totpQrCodeUrl = ref('')
const totpCode = ref('')
const showTotpManualSetup = ref(false)
const savingTotp = ref(false)
const needsPrivacyOptIn = ref(false)
const userId = computed(() => Number(route.params.userId))
const token = computed(() => route.hash.replace(/^#/, ''))
const requiresPassword = computed(() => !authState.isAuthenticated)
const newPasswordInputProps: Record<string, string> = {
  name: 'password',
  autocomplete: 'new-password',
  passwordrules: 'minlength: 10;'
}

onMounted(() => {
  void loadInvitationMetadata()
})

async function loadInvitationMetadata() {
  if (!requiresPassword.value) return
  if (!Number.isInteger(userId.value) || !token.value) return
  try {
    invitedEmail.value = (await invitationMetadata(userId.value, token.value)).email
  } catch {
    invitedEmail.value = ''
  }
}

async function submit() {
  error.value = ''
  if (!Number.isInteger(userId.value) || !token.value) {
    error.value = t('auth.invalidInviteLink')
    return
  }
  const inviteDisplayName = displayName.value.trim()
  if (requiresPassword.value && !inviteDisplayName) {
    error.value = t('auth.displayNameRequired')
    return
  }
  const createsAccount = requiresPassword.value
  const withPasskey = createsAccount && authMethod.value === 'passkey'
  loading.value = true
  try {
    if (!createsAccount) {
      await acceptInvitation(userId.value, token.value)
    } else if (withPasskey) {
      await acceptInvitationWithPasskey(userId.value, token.value, inviteDisplayName)
    } else {
      await acceptInvitation(userId.value, token.value, password.value, inviteDisplayName)
    }
    const entrypoint = await bootstrap()
    if (entrypoint.user) {
      setAuthenticated(entrypoint.user, entrypoint.tenants)
      pendingRedirect.value = '/'
      needsPrivacyOptIn.value = createsAccount
      if (withPasskey) {
        await showLatestPasskeyNameDialog()
      } else if (createsAccount) {
        await showInvitationTotpSetup()
      } else {
        await finishInvite()
      }
    }
  } catch (caught) {
    if (!authState.isAuthenticated && getErrorMessage(caught, '').includes(t('auth.loggedInRequiredMarker'))) {
      await router.push({ name: 'login', query: { redirect: route.fullPath } })
      return
    }
    error.value = getErrorMessage(caught, t('auth.inviteAcceptFailed'))
  } finally {
    loading.value = false
  }
}

async function showInvitationTotpSetup() {
  showTotpSetup.value = true
  showTotpManualSetup.value = false
  totpCode.value = ''
  try {
    const enrollment = await createTotpEnrollment()
    totpEnrollment.value = enrollment
    totpQrCodeUrl.value = await toDataURL(enrollment.provisioningUri, {
      errorCorrectionLevel: 'M',
      margin: 4,
      width: 240
    })
  } catch (caught) {
    error.value = getErrorMessage(caught, t('auth.inviteTotpSetupFailed'))
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
    password.value = ''
    await skipTotpSetup()
  } catch (caught) {
    error.value = getErrorMessage(caught, t('profile.totpEnableFailed'))
  } finally {
    savingTotp.value = false
  }
}

async function skipTotpSetup() {
  showTotpSetup.value = false
  totpEnrollment.value = null
  totpQrCodeUrl.value = ''
  totpCode.value = ''
  showTotpManualSetup.value = false
  password.value = ''
  await continueAfterRegistration()
}

async function showLatestPasskeyNameDialog() {
  try {
    const summary = await listMyPasskeys()
    namingPasskey.value = summary.passkeys[0] ?? null
  } catch {
    namingPasskey.value = null
  }
  if (!namingPasskey.value) await finishInvite()
}

async function saveInvitePasskeyName(displayName: string) {
  if (!namingPasskey.value) return
  savingPasskeyName.value = true
  error.value = ''
  try {
    await updateMyPasskey(namingPasskey.value.passkeyId, displayName)
    namingPasskey.value = null
    await continueAfterRegistration()
  } catch (caught) {
    error.value = getErrorMessage(caught, t('profile.passkeyRenameFailed'))
  } finally {
    savingPasskeyName.value = false
  }
}

async function skipInvitePasskeyName() {
  if (!namingPasskey.value) return
  namingPasskey.value = null
  await continueAfterRegistration()
}

async function continueAfterRegistration() {
  if (needsPrivacyOptIn.value) {
    showPrivacyOptIns.value = true
  } else {
    await finishInvite()
  }
}

async function finishPrivacyOptIns() {
  showPrivacyOptIns.value = false
  needsPrivacyOptIn.value = false
  await finishInvite()
}

async function finishInvite() {
  await router.push(pendingRedirect.value)
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('auth.inviteTitle') }}</template>
    <template #content>
      <RegistrationPrivacyOptIns v-if="showPrivacyOptIns" @complete="finishPrivacyOptIns" />
      <form v-else-if="showTotpSetup" class="veyq-form invite-totp-setup" @submit.prevent="submitTotpSetup">
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
      <form
        v-else
        class="veyq-form"
        method="post"
        :action="`/api/users/${userId}/invite/accept`"
        @submit.prevent="submit"
      >
        <div v-if="requiresPassword" class="auth-method-switch" role="tablist" :aria-label="t('auth.authMethod')">
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
        <label v-if="requiresPassword && authMethod === 'password' && invitedEmail" for="invite-email">
          {{ t('common.email') }}
          <InputText
            id="invite-email"
            :model-value="invitedEmail"
            type="email"
            name="email"
            autocomplete="username"
            readonly
            required
          />
        </label>
        <label v-if="requiresPassword">
          {{ t('auth.displayName') }}
          <InputText v-model="displayName" name="name" autocomplete="name" required />
        </label>
        <label v-if="requiresPassword && authMethod === 'password'" for="invite-new-password">
          {{ t('common.password') }}
          <Password
            v-model="password"
            input-id="invite-new-password"
            :input-props="newPasswordInputProps"
            required
            minlength="10"
            toggle-mask
            fluid
          />
        </label>
        <Button
          type="submit"
          :label="
            requiresPassword
              ? authMethod === 'passkey'
                ? t('auth.accountCreateWithPasskey')
                : t('auth.setPassword')
              : t('auth.acceptInvite')
          "
          :icon="requiresPassword && authMethod === 'passkey' ? 'pi pi-key' : 'pi pi-sign-in'"
          :loading="loading"
        />
      </form>
      <Message v-if="error" severity="error" :closable="false" class="form-message">
        {{ error }}
      </Message>
      <PasskeyNameDialog
        :visible="Boolean(namingPasskey)"
        :passkey="namingPasskey"
        :saving="savingPasskeyName"
        @update:visible="(visible) => { if (!visible) skipInvitePasskeyName() }"
        @save="saveInvitePasskeyName"
        @skip="skipInvitePasskeyName"
      />
    </template>
  </Card>
</template>
