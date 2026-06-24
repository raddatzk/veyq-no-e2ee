<script setup lang="ts">
// User account settings for the E2EE backend. Profile metadata is server-visible; credential changes are
// separate Master-Key wrap flows and never re-encrypt content.
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import ProgressSpinner from 'primevue/progressspinner'
import QrScanner from '../../../components/auth/QrScanner.vue'
import { requestEmailChange } from '../../../features/account/emailChange/emailChange'
import { parseKit } from '../../../features/account/emergencyKit/emergencyKit'
import {
  changePassword,
  deleteAccount,
  deleteAvatar,
  logout,
  updateAvatar,
  updateProfile
} from '../../../features/account/login/login'
import {
  listLoginSessions,
  removeLoginSession,
  renameLoginSession,
  type LoginSessionSummary
} from '../../../features/keys/loginSessions'
import { clearRememberedLogin } from '../../../features/account/rememberedLogin/rememberedLogin'
import { requireSession, session, setUnlocked } from '../../../app/session'

const router = useRouter()
const { t } = useI18n()

const displayName = ref('')
const displayNameDraft = ref('')
const username = ref('')
const usernameDraft = ref('')
const email = ref('')
const avatarUrl = ref('')
const avatarFile = ref<File | null>(null)
const avatarPreview = ref('')
const newEmail = ref('')
const emailMessage = ref('')
const sendingEmail = ref(false)
const currentPassword = ref('')
const secretKey = ref('')
const nextPassword = ref('')
const nextPasswordRepeat = ref('')
const changingPassword = ref(false)
const passwordMessage = ref('')
const deletePassword = ref('')
const deleteConfirmation = ref('')
const deletingAccount = ref(false)
const devices = ref<LoginSessionSummary[]>([])
const loading = ref(false)
const savingProfile = ref(false)
const changingDisplayName = ref(false)
const changingUsername = ref(false)
const removingRef = ref<string | null>(null)
const error = ref('')
const profileMessage = ref('')
const deviceMessage = ref('')
const thisDeviceId = ref('')
const editingRef = ref<string | null>(null)
const editingName = ref('')
const savingDeviceRef = ref<string | null>(null)
const emailDialogVisible = ref(false)
const displayNameDialogVisible = ref(false)
const usernameDialogVisible = ref(false)
const passwordDialogVisible = ref(false)
const deleteAccountDialogVisible = ref(false)
const scanningSecretKey = ref(false)
const canScanSecretKey = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
const secretKitFileInput = ref<HTMLInputElement | null>(null)
const guardedDisplayNameDialogVisible = computed({
  get: () => displayNameDialogVisible.value,
  set: (nextVisible: boolean) => {
    if (!nextVisible && changingDisplayName.value) return
    displayNameDialogVisible.value = nextVisible
  }
})
const guardedEmailDialogVisible = computed({
  get: () => emailDialogVisible.value,
  set: (nextVisible: boolean) => {
    if (!nextVisible && sendingEmail.value) return
    emailDialogVisible.value = nextVisible
  }
})
const guardedUsernameDialogVisible = computed({
  get: () => usernameDialogVisible.value,
  set: (nextVisible: boolean) => {
    if (!nextVisible && changingUsername.value) return
    usernameDialogVisible.value = nextVisible
  }
})
const guardedPasswordDialogVisible = computed({
  get: () => passwordDialogVisible.value,
  set: (nextVisible: boolean) => {
    if (!nextVisible && changingPassword.value) return
    passwordDialogVisible.value = nextVisible
  }
})
const guardedDeleteAccountDialogVisible = computed({
  get: () => deleteAccountDialogVisible.value,
  set: (nextVisible: boolean) => {
    if (!nextVisible && deletingAccount.value) return
    deleteAccountDialogVisible.value = nextVisible
  }
})

const initials = computed(
  () =>
    displayName.value
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase())
      .join('') || '?'
)
const canChangePassword = computed(
  () =>
    currentPassword.value.length > 0 &&
    secretKey.value.length > 0 &&
    nextPassword.value.length >= 12 &&
    nextPassword.value === nextPasswordRepeat.value &&
    !changingPassword.value
)
const canDeleteAccount = computed(
  () =>
    deletePassword.value.length > 0 &&
    deleteConfirmation.value.trim() === email.value &&
    !deletingAccount.value
)

onMounted(load)
onUnmounted(revokeAvatarPreview)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const s = requireSession()
    thisDeviceId.value = s.rootSessionId
    displayName.value = s.user.displayName
    displayNameDraft.value = s.user.displayName
    username.value = s.user.username
    usernameDraft.value = s.user.username
    email.value = s.user.email
    avatarUrl.value = s.user.avatarUrl || ''
    newEmail.value = ''
    devices.value = await listLoginSessions(session.apiBase, s)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('settings.loadFailed')
  } finally {
    loading.value = false
  }
}

async function saveAvatar(file: File) {
  savingProfile.value = true
  error.value = ''
  profileMessage.value = ''
  try {
    const s = requireSession()
    const updated = await updateAvatar(session.apiBase, s.sessionToken, file)
    s.user = updated
    displayName.value = updated.displayName
    displayNameDraft.value = updated.displayName
    username.value = updated.username
    avatarUrl.value = updated.avatarUrl || ''
    avatarFile.value = null
    revokeAvatarPreview()
    profileMessage.value = t('profile.profileSaveSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.profileSaveFailed')
  } finally {
    savingProfile.value = false
  }
}

async function selectAvatar(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0] ?? null
  if (!file) return
  revokeAvatarPreview()
  avatarFile.value = file
  avatarPreview.value = URL.createObjectURL(file)
  profileMessage.value = ''
  await saveAvatar(file)
}

function revokeAvatarPreview() {
  if (!avatarPreview.value) return
  URL.revokeObjectURL(avatarPreview.value)
  avatarPreview.value = ''
}

async function removeAvatar() {
  savingProfile.value = true
  error.value = ''
  profileMessage.value = ''
  try {
    const s = requireSession()
    const updated = await deleteAvatar(session.apiBase, s.sessionToken)
    s.user = updated
    displayName.value = updated.displayName
    displayNameDraft.value = updated.displayName
    username.value = updated.username
    avatarFile.value = null
    revokeAvatarPreview()
    avatarUrl.value = ''
    profileMessage.value = t('profile.avatarDeleteSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.avatarDeleteFailed')
  } finally {
    savingProfile.value = false
  }
}

async function sendEmailChange() {
  const emailCandidate = newEmail.value.trim()
  if (!emailCandidate || sendingEmail.value) return
  sendingEmail.value = true
  error.value = ''
  emailMessage.value = ''
  try {
    const s = requireSession()
    await requestEmailChange(session.apiBase, s.sessionToken, emailCandidate)
    emailMessage.value = t('emailChange.sent', { email: emailCandidate })
    newEmail.value = ''
    emailDialogVisible.value = false
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('emailChange.failed')
  } finally {
    sendingEmail.value = false
  }
}

function openUsernameDialog() {
  usernameDraft.value = username.value
  usernameDialogVisible.value = true
  profileMessage.value = ''
  error.value = ''
}

function openDisplayNameDialog() {
  displayNameDraft.value = displayName.value
  displayNameDialogVisible.value = true
  profileMessage.value = ''
  error.value = ''
}

async function submitDisplayNameChange() {
  const nextDisplayName = displayNameDraft.value.trim()
  if (!nextDisplayName || changingDisplayName.value) return
  changingDisplayName.value = true
  error.value = ''
  profileMessage.value = ''
  try {
    const s = requireSession()
    const updated = await updateProfile(
      session.apiBase,
      s.sessionToken,
      nextDisplayName,
      username.value
    )
    s.user = updated
    displayName.value = updated.displayName
    displayNameDraft.value = updated.displayName
    username.value = updated.username
    displayNameDialogVisible.value = false
    profileMessage.value = t('profile.profileSaveSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.profileSaveFailed')
  } finally {
    changingDisplayName.value = false
  }
}

function applySecretKit(text: string): boolean {
  const kit = parseKit(text)
  if (!kit) {
    error.value = t('kit.importFailed')
    return false
  }
  secretKey.value = kit.secretKey
  error.value = ''
  return true
}

async function importSecretKit(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const text = await file.text()
  input.value = ''
  applySecretKit(text)
}

function openSecretKitFilePicker() {
  secretKitFileInput.value?.click()
}

function onSecretKitScanned(text: string) {
  scanningSecretKey.value = false
  applySecretKit(text)
}

async function submitUsernameChange() {
  const nextUsername = usernameDraft.value.trim()
  if (!nextUsername || changingUsername.value) return
  changingUsername.value = true
  error.value = ''
  profileMessage.value = ''
  try {
    const s = requireSession()
    const updated = await updateProfile(
      session.apiBase,
      s.sessionToken,
      displayName.value,
      nextUsername
    )
    s.user = updated
    username.value = updated.username
    usernameDraft.value = updated.username
    usernameDialogVisible.value = false
    profileMessage.value = t('profile.profileSaveSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.profileSaveFailed')
  } finally {
    changingUsername.value = false
  }
}

async function submitPasswordChange() {
  if (!canChangePassword.value) return
  changingPassword.value = true
  error.value = ''
  passwordMessage.value = ''
  try {
    const s = requireSession()
    await changePassword(
      session.apiBase,
      s,
      currentPassword.value,
      secretKey.value,
      nextPassword.value
    )
    passwordMessage.value = t('profile.passwordChangeSuccess')
    clearRememberedLogin()
    setUnlocked(null)
    void router.push({ name: 'login' })
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.passwordChangeFailed')
  } finally {
    changingPassword.value = false
  }
}

async function submitAccountDelete() {
  if (!canDeleteAccount.value) return
  deletingAccount.value = true
  error.value = ''
  try {
    const s = requireSession()
    await deleteAccount(session.apiBase, s.sessionToken, deletePassword.value)
    clearRememberedLogin()
    setUnlocked(null)
    void router.push({ name: 'login' })
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.accountDeleteFailed')
  } finally {
    deletingAccount.value = false
  }
}

function isCurrentDevice(device: LoginSessionSummary): boolean {
  return device.rootSessionId === thisDeviceId.value
}

function startRename(device: LoginSessionSummary) {
  if (!device.rootSessionId) return
  editingRef.value = device.rootSessionId
  editingName.value = device.name || ''
  deviceMessage.value = ''
}

function cancelRename() {
  editingRef.value = null
  editingName.value = ''
}

async function saveDeviceName(device: LoginSessionSummary) {
  if (!device.rootSessionId) return
  const name = editingName.value.trim()
  if (!name) return
  savingDeviceRef.value = device.rootSessionId
  error.value = ''
  deviceMessage.value = ''
  try {
    const s = requireSession()
    await renameLoginSession(session.apiBase, s, device.rootSessionId, name)
    device.name = name
    cancelRename()
    deviceMessage.value = t('device.renamed')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('device.renameFailed')
  } finally {
    savingDeviceRef.value = null
  }
}

async function removeDevice(device: LoginSessionSummary) {
  if (!device.rootSessionId) return
  removingRef.value = device.rootSessionId
  error.value = ''
  deviceMessage.value = ''
  const removingCurrentDevice = isCurrentDevice(device)
  try {
    const s = requireSession()
    await removeLoginSession(session.apiBase, s.sessionToken, device.rootSessionId)
    devices.value = devices.value.filter((d) => d.rootSessionId !== device.rootSessionId)
    if (removingCurrentDevice) {
      clearRememberedLogin()
      setUnlocked(null)
      void router.push({ name: 'login' })
      return
    }
    deviceMessage.value = t('device.removed')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('device.removeFailed')
  } finally {
    removingRef.value = null
  }
}

async function signOut() {
  try {
    await logout(session.apiBase, requireSession().sessionToken)
  } catch {
    /* best-effort: drop local session regardless */
  }
  clearRememberedLogin()
  setUnlocked(null)
  void router.push({ name: 'login' })
}

function formatTimestamp(value?: string): string {
  if (!value) return ''
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? value : new Date(parsed).toLocaleString()
}
</script>

<template>
  <section class="veyq-surface settings-panel user-settings-panel">
    <h1>{{ t('profile.mySettings') }}</h1>

    <div v-if="loading" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('settings.loading') }}</span>
    </div>

    <template v-else>
      <!-- Profile -->
      <section class="settings-section">
        <div class="settings-section-header">
          <h2>{{ t('profile.profile') }}</h2>
          <p>{{ t('profile.profileSectionSubtitle') }}</p>
        </div>
        <div class="veyq-form">
          <div class="profile-avatar-row">
            <div class="profile-avatar-preview" aria-hidden="true">
              <img v-if="avatarPreview || avatarUrl" :src="avatarPreview || avatarUrl" alt="" />
              <span v-else>{{ initials }}</span>
            </div>
            <div class="profile-avatar-actions">
              <label class="avatar-file-button">
                <i class="pi pi-plus" aria-hidden="true"></i>
                <span>{{ t('profile.avatarChoose') }}</span>
                <input
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  @change="selectAvatar"
                />
              </label>
              <span v-if="avatarFile" class="avatar-file-name">{{ avatarFile.name }}</span>
              <Button
                v-if="avatarUrl || avatarPreview"
                type="button"
                icon="pi pi-trash"
                severity="secondary"
                text
                :aria-label="t('profile.avatarRemove')"
                @click="removeAvatar"
              />
            </div>
          </div>
          <div class="account-row account-row-open">
            <div class="account-row-text">
              <h3>{{ t('auth.displayName') }}</h3>
              <span>{{ displayName }}</span>
            </div>
            <Button
              type="button"
              :label="t('profile.displayNameChange')"
              icon="pi pi-pencil"
              severity="secondary"
              @click="openDisplayNameDialog"
            />
          </div>
          <div class="account-row account-row-open">
            <div class="account-row-text">
              <h3>{{ t('profile.username') }}</h3>
              <span>~{{ username }}</span>
            </div>
            <Button
              type="button"
              :label="t('profile.usernameChange')"
              icon="pi pi-link"
              severity="secondary"
              @click="openUsernameDialog"
            />
          </div>
          <Message v-if="profileMessage" severity="success" :closable="false">{{
            profileMessage
          }}</Message>
        </div>
      </section>

      <!-- Login -->
      <section class="settings-section account-section">
        <div class="settings-section-header">
          <h2>{{ t('profile.loginSection') }}</h2>
          <p>{{ t('profile.loginSectionSubtitle') }}</p>
        </div>
        <div class="account-list">
          <div class="account-row account-row-open">
            <div class="account-row-text">
              <h3>{{ t('common.email') }}</h3>
              <span>{{ email }}</span>
            </div>
            <Button
              type="button"
              :label="t('emailChange.request')"
              icon="pi pi-envelope"
              severity="secondary"
              @click="emailDialogVisible = true"
            />
          </div>
          <Message v-if="emailMessage" severity="success" :closable="false">{{
            emailMessage
          }}</Message>

          <div class="account-row account-row-open">
            <div class="account-row-text">
              <h3>{{ t('profile.passwordLogin') }}</h3>
              <span>{{ t('profile.passwordLoginCopy') }}</span>
            </div>
            <Button
              type="button"
              :label="t('profile.passwordChange')"
              icon="pi pi-key"
              severity="secondary"
              @click="passwordDialogVisible = true"
            />
          </div>
          <Message v-if="passwordMessage" severity="success" :closable="false">{{
            passwordMessage
          }}</Message>
        </div>
      </section>

      <!-- Devices -->
      <section class="settings-section passkey-section">
        <div class="settings-section-header">
          <h2>{{ t('device.title') }}</h2>
          <p>{{ t('device.subtitle') }}</p>
        </div>

        <Message v-if="devices.length === 0" severity="info" :closable="false">{{
          t('device.empty')
        }}</Message>

        <div v-else class="passkey-list">
          <div v-for="device in devices" :key="device.rootSessionId" class="passkey-row">
            <div class="device-details">
              <form
                v-if="editingRef === device.rootSessionId"
                class="device-name-form"
                @submit.prevent="saveDeviceName(device)"
              >
                <InputText v-model="editingName" type="text" autofocus />
                <Button
                  type="submit"
                  icon="pi pi-check"
                  :aria-label="t('common.save')"
                  :loading="savingDeviceRef === device.rootSessionId"
                  :disabled="!editingName.trim()"
                />
                <Button
                  type="button"
                  icon="pi pi-times"
                  severity="secondary"
                  text
                  :aria-label="t('common.cancel')"
                  @click="cancelRename"
                />
              </form>
              <template v-else>
                <h3>{{ device.name || t('device.unnamed') }}</h3>
                <span v-if="isCurrentDevice(device)" class="device-current">{{
                  t('device.thisDevice')
                }}</span>
                <span v-if="device.lastUsedAt"
                  >{{ t('device.lastLogin') }}: {{ formatTimestamp(device.lastUsedAt) }}</span
                >
              </template>
            </div>
            <div class="passkey-row-actions">
              <Button
                type="button"
                icon="pi pi-pencil"
                severity="secondary"
                text
                :disabled="editingRef === device.rootSessionId"
                :aria-label="t('device.edit')"
                @click="startRename(device)"
              />
              <Button
                type="button"
                icon="pi pi-trash"
                severity="danger"
                text
                :loading="removingRef === device.rootSessionId"
                :aria-label="t('device.remove')"
                @click="removeDevice(device)"
              />
            </div>
          </div>
        </div>

        <Message v-if="deviceMessage" severity="success" :closable="false" class="device-message">{{
          deviceMessage
        }}</Message>
      </section>

      <!-- Account deletion -->
      <section class="settings-section account-section account-danger-section">
        <div class="settings-section-header">
          <h2>{{ t('profile.accountDelete') }}</h2>
          <p>{{ t('profile.accountDeleteCopy') }}</p>
        </div>
        <div class="settings-action-row">
          <Button
            type="button"
            :label="t('profile.accountDelete')"
            icon="pi pi-trash"
            severity="danger"
            text
            @click="deleteAccountDialogVisible = true"
          />
        </div>
      </section>

      <!-- Sign out -->
      <section class="settings-section signout-section">
        <div class="settings-section-header">
          <h2>{{ t('auth.logout') }}</h2>
          <p>{{ t('profile.signOutCopy') }}</p>
        </div>
        <Button
          type="button"
          :label="t('auth.logout')"
          icon="pi pi-sign-out"
          severity="secondary"
          @click="signOut"
        />
      </section>

      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

      <Dialog
        v-model:visible="guardedDisplayNameDialogVisible"
        :header="t('profile.displayNameChange')"
        modal
        class="tenant-dialog account-dialog"
        :closable="!changingDisplayName"
        :close-on-escape="!changingDisplayName"
      >
        <form class="veyq-form account-dialog-form" @submit.prevent="submitDisplayNameChange">
          <label>
            {{ t('auth.displayName') }}
            <InputText
              v-model="displayNameDraft"
              type="text"
              autocomplete="name"
              required
              :disabled="changingDisplayName"
            />
          </label>
          <div class="veyq-dialog-action-row">
            <Button
              type="button"
              :label="t('common.cancel')"
              icon="pi pi-times"
              severity="secondary"
              outlined
              :disabled="changingDisplayName"
              @click="guardedDisplayNameDialogVisible = false"
            />
            <Button
              type="submit"
              :label="t('common.save')"
              icon="pi pi-save"
              :loading="changingDisplayName"
              :disabled="!displayNameDraft.trim() || changingDisplayName"
            />
          </div>
        </form>
      </Dialog>

      <Dialog
        v-model:visible="guardedEmailDialogVisible"
        :header="t('emailChange.request')"
        modal
        class="tenant-dialog account-dialog"
        :closable="!sendingEmail"
        :close-on-escape="!sendingEmail"
      >
        <form class="veyq-form account-dialog-form" @submit.prevent="sendEmailChange">
          <label>
            {{ t('emailChange.newEmail') }}
            <InputText
              v-model="newEmail"
              type="email"
              autocomplete="email"
              required
              :disabled="sendingEmail"
            />
          </label>
          <div class="veyq-dialog-action-row">
            <Button
              type="button"
              :label="t('common.cancel')"
              icon="pi pi-times"
              severity="secondary"
              outlined
              :disabled="sendingEmail"
              @click="guardedEmailDialogVisible = false"
            />
            <Button
              type="submit"
              :label="t('emailChange.request')"
              icon="pi pi-envelope"
              :loading="sendingEmail"
              :disabled="!newEmail.trim() || sendingEmail"
            />
          </div>
        </form>
      </Dialog>

      <Dialog
        v-model:visible="guardedUsernameDialogVisible"
        :header="t('profile.usernameChange')"
        modal
        class="tenant-dialog account-dialog"
        :closable="!changingUsername"
        :close-on-escape="!changingUsername"
      >
        <form class="veyq-form account-dialog-form" @submit.prevent="submitUsernameChange">
          <p class="veyq-form-copy account-dialog-copy">{{ t('profile.usernameWarning') }}</p>
          <label>
            {{ t('profile.usernameNew') }}
            <InputText
              v-model="usernameDraft"
              type="text"
              autocomplete="username"
              required
              :disabled="changingUsername"
            />
          </label>
          <div class="veyq-dialog-action-row">
            <Button
              type="button"
              :label="t('common.cancel')"
              icon="pi pi-times"
              severity="secondary"
              outlined
              :disabled="changingUsername"
              @click="guardedUsernameDialogVisible = false"
            />
            <Button
              type="submit"
              :label="t('common.save')"
              icon="pi pi-save"
              :loading="changingUsername"
              :disabled="!usernameDraft.trim() || changingUsername"
            />
          </div>
        </form>
      </Dialog>

      <Dialog
        v-model:visible="guardedPasswordDialogVisible"
        :header="t('profile.passwordChange')"
        modal
        class="tenant-dialog account-dialog"
        :closable="!changingPassword"
        :close-on-escape="!changingPassword"
      >
        <form class="veyq-form account-dialog-form" @submit.prevent="submitPasswordChange">
          <div class="password-change-section">
            <label class="current-password-field">
              {{ t('settings.currentPassword') }}
              <InputText
                v-model="currentPassword"
                type="password"
                autocomplete="current-password"
                required
                :disabled="changingPassword"
              />
            </label>
            <div class="secret-key-panel">
              <label class="secret-key-field">
                {{ t('common.secretKey') }}
                <Password
                  v-model="secretKey"
                  input-id="change-password-secret"
                  :input-props="{ autocomplete: 'off' }"
                  required
                  :feedback="false"
                  toggle-mask
                  fluid
                  :disabled="changingPassword"
                />
              </label>
              <small class="field-hint">{{ t('auth.secretHint') }}</small>
              <div class="secret-key-control">
                <div class="secret-key-actions">
                  <Button
                    type="button"
                    :label="t('profile.secretKeyFileImport')"
                    icon="pi pi-upload"
                    severity="secondary"
                    outlined
                    :aria-label="t('kit.importFile')"
                    :disabled="changingPassword"
                    @click="openSecretKitFilePicker"
                  />
                  <Button
                    v-if="canScanSecretKey"
                    type="button"
                    :label="t('profile.secretKeyQrImport')"
                    icon="pi pi-qrcode"
                    severity="secondary"
                    outlined
                    :aria-label="t('kit.importScan')"
                    :disabled="changingPassword"
                    @click="scanningSecretKey = true"
                  />
                </div>
                <input
                  ref="secretKitFileInput"
                  class="kit-file-input"
                  type="file"
                  accept=".pdf,application/pdf"
                  @change="importSecretKit"
                />
              </div>
            </div>
          </div>
          <div class="password-change-divider" aria-hidden="true"></div>
          <div class="password-change-section">
            <label>
              {{ t('auth.newPassword') }}
              <InputText
                v-model="nextPassword"
                type="password"
                autocomplete="new-password"
                required
                :disabled="changingPassword"
              />
            </label>
            <label>
              {{ t('profile.passwordRepeat') }}
              <InputText
                v-model="nextPasswordRepeat"
                type="password"
                autocomplete="new-password"
                required
                :disabled="changingPassword"
              />
            </label>
          </div>
          <div class="veyq-dialog-action-row">
            <Button
              type="button"
              :label="t('common.cancel')"
              icon="pi pi-times"
              severity="secondary"
              outlined
              :disabled="changingPassword"
              @click="guardedPasswordDialogVisible = false"
            />
            <Button
              type="submit"
              :label="t('profile.passwordChange')"
              icon="pi pi-key"
              :loading="changingPassword"
              :disabled="!canChangePassword"
            />
          </div>
        </form>
      </Dialog>

      <Dialog
        v-model:visible="guardedDeleteAccountDialogVisible"
        :header="t('profile.accountDelete')"
        modal
        class="tenant-dialog account-dialog"
        :closable="!deletingAccount"
        :close-on-escape="!deletingAccount"
      >
        <form
          class="veyq-form account-dialog-form danger-account-form"
          @submit.prevent="submitAccountDelete"
        >
          <p>{{ t('profile.accountDeleteConfirm') }}</p>
          <label>
            {{ t('common.password') }}
            <InputText
              v-model="deletePassword"
              type="password"
              autocomplete="current-password"
              required
              :disabled="deletingAccount"
            />
          </label>
          <label>
            {{ t('profile.accountDeleteEmailConfirm', { email }) }}
            <InputText
              v-model="deleteConfirmation"
              type="email"
              required
              :disabled="deletingAccount"
            />
          </label>
          <div class="veyq-dialog-action-row">
            <Button
              type="button"
              :label="t('common.cancel')"
              icon="pi pi-times"
              severity="secondary"
              outlined
              :disabled="deletingAccount"
              @click="guardedDeleteAccountDialogVisible = false"
            />
            <Button
              type="submit"
              :label="t('profile.accountDelete')"
              icon="pi pi-trash"
              severity="danger"
              :loading="deletingAccount"
              :disabled="!canDeleteAccount"
            />
          </div>
        </form>
      </Dialog>
      <QrScanner
        v-if="scanningSecretKey"
        @decoded="onSecretKitScanned"
        @close="scanningSecretKey = false"
      />
    </template>
  </section>
</template>

<style scoped>
.user-settings-panel {
  gap: 1.1rem;
}
.settings-section {
  margin-top: 0.35rem;
  padding-top: 1rem;
  border-top: 1px solid var(--app-line);
}
.settings-section-header {
  display: grid;
  gap: 0.25rem;
  justify-content: stretch;
  align-items: start;
  margin-bottom: 0.65rem;
}
.settings-section-header h2 {
  margin: 0;
  font-size: var(--app-font-size-section-title);
  line-height: 1.2;
}
.settings-section-header p {
  margin: 0;
  color: var(--app-muted);
  font-size: var(--app-font-size-subtitle);
  line-height: 1.45;
}
.user-settings-panel :deep(.veyq-form) {
  gap: 0.5rem;
}
.user-settings-panel :deep(.p-button) {
  font-size: var(--app-font-size-control);
}
.settings-action-row {
  display: flex;
  justify-content: flex-start;
}
.settings-action-row :deep(.p-button) {
  width: auto;
  min-width: 0;
}
.password-change-section {
  display: grid;
  gap: 0.85rem;
}
.password-change-divider {
  height: 1px;
  background: var(--app-line);
}
.secret-key-panel {
  display: grid;
  gap: 0.55rem;
  padding: 0.85rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface-muted);
}
.secret-key-field {
  gap: 0.45rem;
}
.field-hint {
  display: block;
  margin: 0;
  color: var(--app-muted);
  font-size: var(--app-font-size-subtitle);
  line-height: 1.35;
}
.secret-key-control {
  display: flex;
  min-width: 0;
}
.secret-key-field :deep(.p-password),
.secret-key-field :deep(.p-password-input) {
  width: 100%;
}
.secret-key-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.kit-file-input {
  display: none;
}
.profile-avatar-row {
  display: flex;
  align-items: center;
  gap: 1rem;
  flex-wrap: wrap;
}
.profile-avatar-preview {
  display: grid;
  place-items: center;
  width: 4.5rem;
  height: 4.5rem;
  border-radius: 50%;
  overflow: hidden;
  background: var(--app-info-soft);
  color: var(--app-info-text);
  font-weight: 800;
}
.profile-avatar-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.profile-avatar-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}
.avatar-file-button {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.65rem 0.9rem;
  border-radius: 6px;
  background: var(--p-primary-color);
  color: var(--p-primary-contrast-color);
  cursor: pointer;
  font-size: var(--app-font-size-control);
  font-weight: 500;
}
.avatar-file-button input {
  position: absolute;
  inline-size: 1px;
  block-size: 1px;
  opacity: 0;
  pointer-events: none;
}
.avatar-file-name {
  color: var(--app-muted);
  font-size: var(--app-font-size-subtitle);
}
.account-section {
  display: grid;
  gap: 0.5rem;
}
.account-list {
  display: grid;
  gap: 0.25rem;
}
.account-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 1rem;
  align-items: center;
  padding: 0.4rem 0;
  border-bottom: 1px solid var(--app-line);
}
.account-row-open {
  border-bottom: 0;
}
.account-row:first-child {
  padding-top: 0;
}
.account-row-text {
  display: grid;
  min-width: 0;
  gap: 0.15rem;
}
.account-row-text h3 {
  margin: 0;
  color: var(--app-text);
  font-size: var(--app-font-size-row-title);
  line-height: 1.25;
}
.account-row-text span {
  overflow-wrap: anywhere;
  color: var(--app-muted);
  font-size: var(--app-font-size-subtitle);
}
.account-danger-section {
  padding-top: 0.85rem;
}
.account-danger-section .settings-section-header {
  margin-bottom: 0.55rem;
}
.account-danger-section .settings-action-row {
  margin-top: 0;
}
:deep(.account-dialog.p-dialog) {
  width: min(34rem, calc(100vw - 2rem));
  max-width: calc(100vw - 2rem);
}
.account-dialog-form {
  width: 100%;
  min-width: 0;
}
.danger-account-form {
  min-width: 0;
}
.account-dialog-form :deep(.p-inputtext) {
  width: 100%;
}
.account-dialog-copy {
  max-width: 100%;
  line-height: 1.45;
}
.danger-account-form p {
  max-width: 100%;
  line-height: 1.45;
}
.signout-section {
  display: grid;
  gap: 0.85rem;
  justify-items: start;
}
.device-current {
  font-weight: 600;
  color: var(--p-primary-color, #5b4df5);
}
.device-details {
  min-width: 0;
}
.device-details h3 {
  margin: 0;
  font-size: var(--app-font-size-row-title);
  line-height: 1.25;
}
.device-name-form {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.device-message {
  margin-top: 0.75rem;
}
@media (max-width: 720px) {
  .account-row {
    grid-template-columns: 1fr;
  }
  .account-row {
    gap: 0.65rem;
    align-items: start;
  }
  .account-row :deep(.p-button),
  .settings-action-row :deep(.p-button) {
    width: 100%;
  }
  .secret-key-actions :deep(.p-button) {
    flex: 1 1 10rem;
  }
}
</style>
