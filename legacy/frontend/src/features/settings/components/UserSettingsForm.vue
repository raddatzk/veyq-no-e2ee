<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { toDataURL } from 'qrcode'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import FileUpload, { type FileUploadSelectEvent } from 'primevue/fileupload'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import ProgressSpinner from 'primevue/progressspinner'
import PasskeyNameDialog from '../../../components/auth/PasskeyNameDialog.vue'
import ConfirmationDialog from '../../../shared/components/ConfirmationDialog.vue'
import PrivacySettingsPanel from './PrivacySettingsPanel.vue'
import {
  changeMyPassword,
  createTotpEnrollment,
  createMyPasskey,
  deleteMyAvatar,
  deleteMyAccountWithPasskey,
  deleteMyAccountWithPassword,
  deleteMyPasskeyWithPasskey,
  deleteMyPassword,
  disableTotp,
  enableTotp,
  getMySettings,
  listMyPasskeys,
  updateMyAvatar,
  updateMySettings,
  updateMyPasskey,
  type PasskeySummary,
  type TotpEnrollmentResponse,
  type UserSettingsResponse
} from '../api'
import { authState } from '../../../authState'
import { useAuthState } from '../../../shared/state/useAuthState'
import { clearAccessSession } from '../../../shared/api/accessSession'

const router = useRouter()
const { locale, t } = useI18n()
const { setAnonymous } = useAuthState()
const settings = ref<UserSettingsResponse | null>(null)
const displayName = ref('')
const permalink = ref('')
const email = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const currentPasswordInputProps: Record<string, string> = {
  name: 'password',
  autocomplete: 'current-password'
}
const newPasswordInputProps: Record<string, string> = {
  name: 'password',
  autocomplete: 'new-password',
  passwordrules: 'minlength: 10;'
}
const totpEnrollment = ref<TotpEnrollmentResponse | null>(null)
const totpQrCodeUrl = ref('')
const totpPassword = ref('')
const totpCode = ref('')
const showTotpManualSetup = ref(false)
const showTotpDisableForm = ref(false)
const passkeys = ref<PasskeySummary[]>([])
const passkeyNames = ref<Record<number, string>>({})
const namingPasskey = ref<PasskeySummary | null>(null)
const loading = ref(false)
const savingProfile = ref(false)
const savingAvatar = ref(false)
const savingPassword = ref(false)
const deletingPassword = ref(false)
const creatingTotpEnrollment = ref(false)
const savingTotp = ref(false)
const savingPasskey = ref(false)
const savingPasskeyName = ref(false)
const savingPasskeyId = ref<number | null>(null)
const deletingPasskeyId = ref<number | null>(null)
const error = ref('')
const profileMessage = ref('')
const passwordMessage = ref('')
const totpMessage = ref('')
const passkeyMessage = ref('')
const deletePasswordDialogOpen = ref(false)
const deletePasswordConfirmation = ref('')
const privacyDialogOpen = ref(false)
const deleteAccountDialogOpen = ref(false)
const deletingAccount = ref(false)
const deleteAccountMethod = ref<'password' | 'passkey'>('password')
const deleteAccountPassword = ref('')
const deleteAccountConfirmation = ref('')
const canSubmitDeleteAccount = computed(() =>
  settings.value !== null &&
  deleteAccountConfirmation.value.trim() === settings.value.email &&
  (deleteAccountMethod.value === 'passkey' || Boolean(deleteAccountPassword.value))
)
const canDeleteCurrentPassword = computed(() => Boolean(settings.value?.hasPassword) && passkeys.value.length > 0)

onMounted(loadSettings)

async function loadSettings() {
  loading.value = true
  error.value = ''
  try {
    const [nextSettings, nextPasskeys] = await Promise.all([getMySettings(), listMyPasskeys()])
    applySettings(nextSettings)
    passkeys.value = nextPasskeys.passkeys
    applyPasskeyNames(nextPasskeys.passkeys)
  } catch (caught) {
    settings.value = null
    error.value = caught instanceof Error ? caught.message : t('profile.profileLoadFailed')
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  savingProfile.value = true
  error.value = ''
  profileMessage.value = ''
  try {
    const nextSettings = await updateMySettings({
      displayName: displayName.value,
      permalink: permalink.value,
      email: email.value
    })
    applySettings(nextSettings)
    profileMessage.value =
      nextSettings.pendingEmail && nextSettings.pendingEmail !== nextSettings.email
        ? t('profile.profileSavePendingEmail')
        : t('profile.profileSaveSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.profileSaveFailed')
  } finally {
    savingProfile.value = false
  }
}

async function uploadAvatar(event: FileUploadSelectEvent) {
  const file = event.files?.[0]
  if (!file) return
  savingAvatar.value = true
  error.value = ''
  profileMessage.value = ''
  try {
    const nextSettings = await updateMyAvatar(file)
    applySettings(nextSettings)
    profileMessage.value = t('profile.avatarSaveSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.avatarSaveFailed')
  } finally {
    savingAvatar.value = false
  }
}

async function removeAvatar() {
  savingAvatar.value = true
  error.value = ''
  profileMessage.value = ''
  try {
    const nextSettings = await deleteMyAvatar()
    applySettings(nextSettings)
    profileMessage.value = t('profile.avatarDeleteSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.avatarDeleteFailed')
  } finally {
    savingAvatar.value = false
  }
}

async function savePassword() {
  savingPassword.value = true
  error.value = ''
  passwordMessage.value = ''
  try {
    await changeMyPassword(
      settings.value?.hasPassword
        ? {
            currentPassword: currentPassword.value,
            newPassword: newPassword.value
          }
        : {
            newPassword: newPassword.value
          }
    )
    const nextSettings = await getMySettings()
    applySettings(nextSettings)
    currentPassword.value = ''
    newPassword.value = ''
    passwordMessage.value = t('profile.passwordChangeSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.passwordChangeFailed')
  } finally {
    savingPassword.value = false
  }
}

function openDeletePasswordDialog() {
  deletePasswordConfirmation.value = ''
  passwordMessage.value = ''
  error.value = ''
  deletePasswordDialogOpen.value = true
}

async function submitDeletePassword() {
  if (deletingPassword.value) return
  deletingPassword.value = true
  error.value = ''
  passwordMessage.value = ''
  try {
    const nextSettings = await deleteMyPassword(deletePasswordConfirmation.value)
    applySettings(nextSettings)
    resetTotpForm()
    currentPassword.value = ''
    newPassword.value = ''
    deletePasswordConfirmation.value = ''
    deletePasswordDialogOpen.value = false
    passwordMessage.value = t('profile.passwordDeleteSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.passwordDeleteFailed')
  } finally {
    deletingPassword.value = false
  }
}

async function startTotpEnrollment() {
  creatingTotpEnrollment.value = true
  error.value = ''
  totpMessage.value = ''
  try {
    const enrollment = await createTotpEnrollment()
    totpEnrollment.value = enrollment
    totpQrCodeUrl.value = await toDataURL(enrollment.provisioningUri, {
      errorCorrectionLevel: 'M',
      margin: 4,
      width: 240
    })
    totpPassword.value = ''
    totpCode.value = ''
    showTotpManualSetup.value = false
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.totpEnrollmentFailed')
  } finally {
    creatingTotpEnrollment.value = false
  }
}

async function submitEnableTotp() {
  const enrollment = totpEnrollment.value
  if (!enrollment) return
  savingTotp.value = true
  error.value = ''
  totpMessage.value = ''
  try {
    const nextSettings = await enableTotp(totpPassword.value, enrollment.secret, totpCode.value)
    applySettings(nextSettings)
    resetTotpForm()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.totpEnableFailed')
  } finally {
    savingTotp.value = false
  }
}

async function submitDisableTotp() {
  savingTotp.value = true
  error.value = ''
  totpMessage.value = ''
  try {
    const nextSettings = await disableTotp(totpPassword.value, totpCode.value)
    applySettings(nextSettings)
    resetTotpForm()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.totpDisableFailed')
  } finally {
    savingTotp.value = false
  }
}

function resetTotpForm() {
  totpEnrollment.value = null
  totpQrCodeUrl.value = ''
  totpPassword.value = ''
  totpCode.value = ''
  showTotpManualSetup.value = false
  showTotpDisableForm.value = false
}

async function addPasskey() {
  savingPasskey.value = true
  error.value = ''
  passkeyMessage.value = ''
  try {
    const passkey = await createMyPasskey()
    passkeys.value = [passkey, ...passkeys.value]
    applyPasskeyNames(passkeys.value)
    if (settings.value) {
      settings.value = { ...settings.value, passkeyCount: passkeys.value.length }
    }
    if (authState.user) {
      authState.user.passkeyCount = passkeys.value.length
    }
    namingPasskey.value = passkey
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.passkeyAddFailed')
  } finally {
    savingPasskey.value = false
  }
}

async function saveCreatedPasskeyName(displayName: string) {
  if (!namingPasskey.value) return
  savingPasskeyName.value = true
  error.value = ''
  passkeyMessage.value = ''
  try {
    const renamed = await updateMyPasskey(namingPasskey.value.passkeyId, displayName)
    passkeys.value = passkeys.value.map((item) => item.passkeyId === renamed.passkeyId ? renamed : item)
    passkeyNames.value = { ...passkeyNames.value, [renamed.passkeyId]: renamed.displayName }
    namingPasskey.value = null
    passkeyMessage.value = t('profile.passkeyNameSaveSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.passkeyRenameFailed')
  } finally {
    savingPasskeyName.value = false
  }
}

function keepCreatedPasskeyName() {
  if (!namingPasskey.value) return
  namingPasskey.value = null
  passkeyMessage.value = t('profile.passkeyAddSuccess')
}

async function renamePasskey(passkey: PasskeySummary) {
  const nextPasskeyName = passkeyNames.value[passkey.passkeyId]?.trim() ?? ''
  if (!nextPasskeyName) {
    error.value = t('auth.passkeyDisplayNameRequired')
    return
  }
  savingPasskeyId.value = passkey.passkeyId
  error.value = ''
  passkeyMessage.value = ''
  try {
    const renamed = await updateMyPasskey(passkey.passkeyId, nextPasskeyName)
    passkeys.value = passkeys.value.map((item) => item.passkeyId === renamed.passkeyId ? renamed : item)
    passkeyNames.value = { ...passkeyNames.value, [renamed.passkeyId]: renamed.displayName }
    passkeyMessage.value = t('profile.passkeyRenameSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.passkeyRenameFailed')
  } finally {
    savingPasskeyId.value = null
  }
}

async function removePasskey(passkey: PasskeySummary) {
  if (!canDeletePasskey(passkey)) return
  deletingPasskeyId.value = passkey.passkeyId
  error.value = ''
  passkeyMessage.value = ''
  try {
    const nextSettings = await deleteMyPasskeyWithPasskey(passkey.passkeyId)
    passkeys.value = passkeys.value.filter((item) => item.passkeyId !== passkey.passkeyId)
    applyPasskeyNames(passkeys.value)
    applySettings({ ...nextSettings, passkeyCount: passkeys.value.length })
    if (authState.user) {
      authState.user.passkeyCount = passkeys.value.length
    }
    passkeyMessage.value = t('profile.passkeyDeleteSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.passkeyDeleteFailed')
  } finally {
    deletingPasskeyId.value = null
  }
}

function canDeletePasskey(passkey: PasskeySummary) {
  return settings.value?.hasPassword === true || passkeys.value.some((item) => item.passkeyId !== passkey.passkeyId)
}

function openDeleteAccountDialog() {
  deleteAccountPassword.value = ''
  deleteAccountConfirmation.value = ''
  deleteAccountMethod.value = settings.value?.hasPassword ? 'password' : 'passkey'
  error.value = ''
  deleteAccountDialogOpen.value = true
}

async function submitDeleteAccount() {
  if (deletingAccount.value) return
  deletingAccount.value = true
  error.value = ''
  try {
    if (deleteAccountMethod.value === 'passkey') {
      await deleteMyAccountWithPasskey()
    } else {
      await deleteMyAccountWithPassword(deleteAccountPassword.value)
    }
    clearAccessSession()
    setAnonymous()
    deleteAccountDialogOpen.value = false
    await router.push('/login')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.accountDeleteFailed')
  } finally {
    deletingAccount.value = false
  }
}

function applyPasskeyNames(nextPasskeys: PasskeySummary[]) {
  passkeyNames.value = Object.fromEntries(
    nextPasskeys.map((passkey) => [passkey.passkeyId, passkey.displayName])
  )
}

function applySettings(nextSettings: UserSettingsResponse) {
  settings.value = nextSettings
  displayName.value = nextSettings.displayName
  permalink.value = nextSettings.permalink
  email.value = nextSettings.email
  if (authState.user?.userId === nextSettings.userId) {
    authState.user.displayName = nextSettings.displayName
    authState.user.permalink = nextSettings.permalink
    authState.user.email = nextSettings.email
    authState.user.avatarUrl = nextSettings.avatarUrl
    authState.user.hasPassword = nextSettings.hasPassword
    authState.user.totpEnabled = nextSettings.totpEnabled
    authState.user.passkeyCount = nextSettings.passkeyCount
    authState.userLabel = nextSettings.displayName || nextSettings.email
  }
}

function formatTimestamp(value?: string) {
  if (!value) return t('profile.neverUsed')
  return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'de-DE', {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(new Date(value))
}

</script>

<template>
  <section class="veyq-surface settings-panel">
    <h1>{{ t('profile.mySettings') }}</h1>
    <p>{{ t('profile.profileAndLogin') }}</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div v-if="loading" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('settings.loading') }}</span>
    </div>

    <template v-else-if="settings">
      <form class="veyq-form settings-form" @submit.prevent="saveProfile">
        <div class="profile-avatar-settings">
          <img v-if="settings.avatarUrl" class="profile-avatar-image" :src="settings.avatarUrl" alt="" />
          <span v-else class="profile-avatar-fallback">{{ displayName.charAt(0).toUpperCase() || '?' }}</span>
          <div class="veyq-button-row">
            <FileUpload
              mode="basic"
              accept="image/png,image/jpeg"
              :choose-label="t('profile.avatarChoose')"
              custom-upload
              :disabled="savingAvatar"
              @select="uploadAvatar"
            />
            <Button
              type="button"
              icon="pi pi-trash"
              severity="secondary"
              text
              :disabled="!settings.avatarUrl"
              :loading="savingAvatar"
              :aria-label="t('profile.avatarRemove')"
              @click="removeAvatar"
            />
          </div>
        </div>
        <label>
          <span>{{ t('auth.displayName') }}</span>
          <InputText v-model="displayName" autocomplete="name" />
        </label>
        <label>
          <span>{{ t('common.permalink') }}</span>
          <InputText v-model="permalink" autocomplete="off" />
        </label>
        <label>
          <span>{{ t('common.email') }}</span>
          <InputText v-model="email" type="email" autocomplete="email" />
        </label>
        <Message v-if="settings.pendingEmail" severity="info" :closable="false">
          {{ t('profile.emailPending', { email: settings.pendingEmail }) }}
        </Message>
        <div class="veyq-button-row">
          <Button type="submit" :label="t('common.save')" icon="pi pi-save" :loading="savingProfile" />
        </div>
        <Message v-if="profileMessage" severity="success" :closable="false">{{ profileMessage }}</Message>
      </form>

      <section class="login-method-grid" :aria-label="t('profile.loginMethods')">
        <section class="veyq-form settings-form login-method-panel">
          <div>
            <h2>{{ t('profile.passwordLogin') }}</h2>
            <p>{{ t('profile.passwordLoginCopy') }}</p>
          </div>
          <form class="veyq-form nested-settings-form" @submit.prevent="savePassword">
            <label v-if="settings.hasPassword">
              <span>{{ t('settings.currentPassword') }}</span>
              <Password
                v-model="currentPassword"
                :input-props="currentPasswordInputProps"
                toggle-mask
                :feedback="false"
              />
            </label>
            <label>
              <span>{{ t('auth.newPassword') }}</span>
              <Password
                v-model="newPassword"
                :input-props="newPasswordInputProps"
                minlength="10"
                toggle-mask
              />
            </label>
            <div class="veyq-button-row">
              <Button
                type="submit"
                :label="settings.hasPassword ? t('profile.passwordChange') : t('profile.passwordSet')"
                icon="pi pi-key"
                :loading="savingPassword"
              />
              <Button
                v-if="canDeleteCurrentPassword"
                type="button"
                :label="t('profile.passwordDelete')"
                icon="pi pi-trash"
                severity="danger"
                outlined
                :loading="deletingPassword"
                @click="openDeletePasswordDialog"
              />
            </div>
            <Message v-if="passwordMessage" severity="success" :closable="false">{{ passwordMessage }}</Message>
          </form>

          <section v-if="settings.hasPassword" class="login-method-subsection">
            <div>
              <h3>{{ t('profile.totpTitle') }}</h3>
              <p>{{ t('profile.totpCopy') }}</p>
            </div>
            <template v-if="settings.totpEnabled">
              <Message severity="success" :closable="false">
                {{ t('profile.totpEnabled') }}
              </Message>
              <div v-if="!showTotpDisableForm" class="veyq-button-row">
                <Button
                  type="button"
                  :label="t('profile.totpDisable')"
                  icon="pi pi-lock-open"
                  severity="danger"
                  outlined
                  @click="showTotpDisableForm = true"
                />
              </div>
              <form v-else class="veyq-form nested-settings-form" @submit.prevent="submitDisableTotp">
                <label>
                  <span>{{ t('settings.currentPassword') }}</span>
                  <Password
                    v-model="totpPassword"
                    :input-props="currentPasswordInputProps"
                    toggle-mask
                    :feedback="false"
                    required
                  />
                </label>
                <label>
                  <span>{{ t('auth.totpCode') }}</span>
                  <InputText v-model="totpCode" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required />
                </label>
                <div class="veyq-button-row">
                  <Button
                    type="button"
                    :label="t('common.cancel')"
                    icon="pi pi-times"
                    severity="secondary"
                    outlined
                    @click="resetTotpForm"
                  />
                  <Button
                    type="submit"
                    :label="t('profile.totpDisable')"
                    icon="pi pi-lock-open"
                    severity="danger"
                    outlined
                    :loading="savingTotp"
                  />
                </div>
              </form>
            </template>
            <template v-else>
              <Message severity="secondary" :closable="false">
                {{ t('profile.totpNotConfigured') }}
              </Message>
              <div v-if="!totpEnrollment" class="veyq-button-row">
                <Button
                  type="button"
                  :label="t('profile.totpStart')"
                  icon="pi pi-shield"
                  :loading="creatingTotpEnrollment"
                  @click="startTotpEnrollment"
                />
              </div>
              <form v-else class="veyq-form nested-settings-form" @submit.prevent="submitEnableTotp">
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
                <template v-else>
                  <label>
                    <span>{{ t('profile.totpSecret') }}</span>
                    <InputText :model-value="totpEnrollment.secret" readonly autocomplete="off" />
                  </label>
                </template>
                <label>
                  <span>{{ t('settings.currentPassword') }}</span>
                  <Password
                    v-model="totpPassword"
                    :input-props="currentPasswordInputProps"
                    toggle-mask
                    :feedback="false"
                    required
                  />
                </label>
                <label>
                  <span>{{ t('auth.totpCode') }}</span>
                  <InputText v-model="totpCode" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required />
                </label>
                <div class="veyq-button-row">
                  <Button
                    type="button"
                    :label="t('common.cancel')"
                    icon="pi pi-times"
                    severity="secondary"
                    outlined
                    @click="resetTotpForm"
                  />
                  <Button
                    type="submit"
                    :label="t('profile.totpEnable')"
                    icon="pi pi-lock"
                    :loading="savingTotp"
                  />
                </div>
              </form>
            </template>
            <Message v-if="totpMessage" severity="success" :closable="false">{{ totpMessage }}</Message>
          </section>
        </section>

        <section class="veyq-form settings-form login-method-panel">
          <div>
            <h2>{{ t('profile.passkeys') }}</h2>
            <p>{{ t('profile.recommended') }}</p>
          </div>
          <Message v-if="passkeys.length === 0" severity="info" :closable="false">
            {{ t('profile.passkeyHint') }}
          </Message>
          <Message
            v-else-if="!settings.hasPassword && passkeys.length <= 1"
            severity="info"
            :closable="false"
          >
            {{ t('profile.passkeyDeleteUnavailable') }}
          </Message>
          <div v-if="passkeys.length" class="passkey-list">
            <div v-for="passkey in passkeys" :key="passkey.passkeyId" class="passkey-row">
              <div>
                <label>
                  <span>{{ t('auth.passkeyDisplayName') }}</span>
                  <InputText v-model="passkeyNames[passkey.passkeyId]" autocomplete="off" required />
                </label>
                <span v-if="passkey.domain">{{ t('profile.domain') }}: {{ passkey.domain }}</span>
                <span>{{ t('profile.createdAt') }}: {{ formatTimestamp(passkey.createdAt) }}</span>
                <span>{{ t('profile.lastUsedAt') }}: {{ formatTimestamp(passkey.lastUsedAt) }}</span>
              </div>
              <div class="passkey-row-actions">
                <Button
                  type="button"
                  icon="pi pi-save"
                  text
                  :loading="savingPasskeyId === passkey.passkeyId"
                  :aria-label="t('profile.renamePasskey')"
                  @click="renamePasskey(passkey)"
                />
                <Button
                  type="button"
                  icon="pi pi-trash"
                  severity="danger"
                  text
                  :disabled="!canDeletePasskey(passkey)"
                  :loading="deletingPasskeyId === passkey.passkeyId"
                  :aria-label="t('profile.deletePasskey')"
                  @click="removePasskey(passkey)"
                />
              </div>
            </div>
          </div>
          <div class="veyq-button-row">
            <Button
              type="button"
              :label="t('profile.addPasskey')"
              icon="pi pi-key"
              :loading="savingPasskey"
              @click="addPasskey"
            />
          </div>
          <Message v-if="passkeyMessage" severity="success" :closable="false">{{ passkeyMessage }}</Message>
        </section>
      </section>

      <section class="veyq-form settings-form">
        <div>
          <h2>{{ t('privacy.externalContent') }}</h2>
          <p>{{ t('privacy.externalContentCopy') }}</p>
        </div>
        <div class="veyq-button-row">
          <Button
            type="button"
            :label="t('privacy.manageExternalContent')"
            icon="pi pi-shield"
            severity="secondary"
            outlined
            @click="privacyDialogOpen = true"
          />
        </div>
      </section>

      <PasskeyNameDialog
        :visible="Boolean(namingPasskey)"
        :passkey="namingPasskey"
        :saving="savingPasskeyName"
        @update:visible="(visible) => { if (!visible) keepCreatedPasskeyName() }"
        @save="saveCreatedPasskeyName"
        @skip="keepCreatedPasskeyName"
      />
      <Dialog
        v-model:visible="privacyDialogOpen"
        modal
        :header="t('privacy.dialogTitle')"
        class="privacy-settings-dialog"
      >
        <PrivacySettingsPanel />
      </Dialog>
      <ConfirmationDialog
        v-model:visible="deletePasswordDialogOpen"
        :title="t('profile.passwordDelete')"
        :confirm-label="t('profile.passwordDeleteConfirm')"
        confirm-icon="pi pi-trash"
        confirm-severity="danger"
        :confirm-disabled="!deletePasswordConfirmation"
        :loading="deletingPassword"
        @confirm="submitDeletePassword"
      >
        <template #default="{ disabled }">
          <p>{{ t('profile.passwordDeleteCopy') }}</p>
          <label>
            <span>{{ t('settings.currentPassword') }}</span>
            <Password
              v-model="deletePasswordConfirmation"
              :input-props="currentPasswordInputProps"
              toggle-mask
              :feedback="false"
              required
              :disabled="disabled"
            />
          </label>
        </template>
      </ConfirmationDialog>

      <section class="veyq-form settings-form settings-danger-zone">
        <div>
          <h2>{{ t('profile.accountDelete') }}</h2>
          <p>{{ t('profile.accountDeleteCopy') }}</p>
        </div>
        <div class="veyq-button-row">
          <Button
            type="button"
            :label="t('profile.accountDelete')"
            icon="pi pi-trash"
            severity="danger"
            outlined
            @click="openDeleteAccountDialog"
          />
        </div>
      </section>

      <ConfirmationDialog
        v-model:visible="deleteAccountDialogOpen"
        :title="t('profile.accountDelete')"
        :confirm-label="t('profile.accountDelete')"
        confirm-icon="pi pi-trash"
        confirm-severity="danger"
        :confirm-disabled="!canSubmitDeleteAccount"
        :loading="deletingAccount"
        @confirm="submitDeleteAccount"
      >
        <template #default="{ disabled }">
          <p>{{ t('profile.accountDeleteConfirm') }}</p>
          <label>
            <span>{{ t('profile.accountDeleteEmailConfirm', { email: settings.email }) }}</span>
            <InputText v-model="deleteAccountConfirmation" autocomplete="off" :disabled="disabled" />
          </label>
          <fieldset v-if="settings.hasPassword && passkeys.length > 0" class="terms-template-fieldset">
            <legend>{{ t('profile.accountDeleteConfirmationMethod') }}</legend>
            <label class="checkbox-row">
              <input v-model="deleteAccountMethod" type="radio" value="password" :disabled="disabled" />
              <span>{{ t('settings.currentPassword') }}</span>
            </label>
            <label class="checkbox-row">
              <input v-model="deleteAccountMethod" type="radio" value="passkey" :disabled="disabled" />
              <span>{{ t('profile.passkeys') }}</span>
            </label>
          </fieldset>
          <label v-if="deleteAccountMethod === 'password'">
            <span>{{ t('settings.currentPassword') }}</span>
            <Password
              v-model="deleteAccountPassword"
              :input-props="currentPasswordInputProps"
              toggle-mask
              :feedback="false"
              required
              :disabled="disabled"
            />
          </label>
          <Message v-else severity="warn" :closable="false">
            {{ t('profile.accountDeletePasskeyHint') }}
          </Message>
        </template>
      </ConfirmationDialog>
    </template>
  </section>
</template>
