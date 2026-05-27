<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import FileUpload, { type FileUploadSelectEvent } from 'primevue/fileupload'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import ProgressSpinner from 'primevue/progressspinner'
import PasskeyNameDialog from '../../../components/auth/PasskeyNameDialog.vue'
import PrivacySettingsPanel from './PrivacySettingsPanel.vue'
import {
  changeMyPassword,
  createMyPasskey,
  deleteMyAvatar,
  deleteMyPasskey,
  getMySettings,
  listMyPasskeys,
  updateMyAvatar,
  updateMySettings,
  updateMyPasskey,
  type PasskeySummary,
  type UserSettingsResponse
} from '../api'
import { authState } from '../../../authState'

const { locale, t } = useI18n()
const settings = ref<UserSettingsResponse | null>(null)
const displayName = ref('')
const permalink = ref('')
const email = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const passkeys = ref<PasskeySummary[]>([])
const passkeyNames = ref<Record<number, string>>({})
const namingPasskey = ref<PasskeySummary | null>(null)
const loading = ref(false)
const savingProfile = ref(false)
const savingAvatar = ref(false)
const savingPassword = ref(false)
const savingPasskey = ref(false)
const savingPasskeyName = ref(false)
const savingPasskeyId = ref<number | null>(null)
const deletingPasskeyId = ref<number | null>(null)
const error = ref('')
const profileMessage = ref('')
const passwordMessage = ref('')
const passkeyMessage = ref('')
const privacyDialogOpen = ref(false)

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
    await changeMyPassword({
      currentPassword: currentPassword.value,
      newPassword: newPassword.value
    })
    currentPassword.value = ''
    newPassword.value = ''
    passwordMessage.value = t('profile.passwordChangeSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.passwordChangeFailed')
  } finally {
    savingPassword.value = false
  }
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
  deletingPasskeyId.value = passkey.passkeyId
  error.value = ''
  passkeyMessage.value = ''
  try {
    await deleteMyPasskey(passkey.passkeyId)
    passkeys.value = passkeys.value.filter((item) => item.passkeyId !== passkey.passkeyId)
    applyPasskeyNames(passkeys.value)
    if (settings.value) {
      settings.value = { ...settings.value, passkeyCount: passkeys.value.length }
    }
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
  <section class="settings-panel">
    <h1>{{ t('profile.mySettings') }}</h1>
    <p>{{ t('profile.profileAndLogin') }}</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div v-if="loading" class="memory-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('settings.loading') }}</span>
    </div>

    <template v-else-if="settings">
      <form class="form settings-form" @submit.prevent="saveProfile">
        <div class="profile-avatar-settings">
          <img v-if="settings.avatarUrl" class="profile-avatar-image" :src="settings.avatarUrl" alt="" />
          <span v-else class="profile-avatar-fallback">{{ displayName.charAt(0).toUpperCase() || '?' }}</span>
          <div class="button-row">
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
        <div class="button-row">
          <Button type="submit" :label="t('common.save')" icon="pi pi-save" :loading="savingProfile" />
        </div>
        <Message v-if="profileMessage" severity="success" :closable="false">{{ profileMessage }}</Message>
      </form>

      <form class="form settings-form" @submit.prevent="savePassword">
        <label>
          <span>{{ t('settings.currentPassword') }}</span>
          <Password v-model="currentPassword" autocomplete="current-password" toggle-mask :feedback="false" />
        </label>
        <label>
          <span>{{ t('auth.newPassword') }}</span>
          <Password v-model="newPassword" autocomplete="new-password" toggle-mask />
        </label>
        <div class="button-row">
          <Button type="submit" :label="t('profile.passwordChange')" icon="pi pi-key" :loading="savingPassword" />
        </div>
        <Message v-if="passwordMessage" severity="success" :closable="false">{{ passwordMessage }}</Message>
      </form>

      <section class="form settings-form">
        <div>
          <h2>{{ t('privacy.externalContent') }}</h2>
          <p>{{ t('privacy.externalContentCopy') }}</p>
        </div>
        <div class="button-row">
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

      <section class="form settings-form">
        <div>
          <h2>{{ t('profile.passkeys') }}</h2>
          <p>{{ t('profile.recommended') }}</p>
        </div>
        <Message v-if="passkeys.length === 0" severity="info" :closable="false">
          {{ t('profile.passkeyHint') }}
        </Message>
        <div v-if="passkeys.length" class="passkey-list">
          <div v-for="passkey in passkeys" :key="passkey.passkeyId" class="passkey-row">
            <div>
              <label>
                <span>{{ t('auth.passkeyDisplayName') }}</span>
                <InputText v-model="passkeyNames[passkey.passkeyId]" autocomplete="off" required />
              </label>
              <span>{{ t('profile.createdAt') }}: {{ formatTimestamp(passkey.createdAt) }}</span>
              <span>{{ t('profile.lastUsedAt') }}: {{ formatTimestamp(passkey.lastUsedAt) }}</span>
            </div>
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
              :loading="deletingPasskeyId === passkey.passkeyId"
              :aria-label="t('profile.deletePasskey')"
              @click="removePasskey(passkey)"
            />
          </div>
        </div>
        <div class="button-row">
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
    </template>
  </section>
</template>
