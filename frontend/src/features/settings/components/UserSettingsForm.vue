<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import ProgressSpinner from 'primevue/progressspinner'
import {
  changeMyPassword,
  createMyPasskey,
  deleteMyPasskey,
  getMySettings,
  listMyPasskeys,
  updateMySettings,
  type PasskeySummary,
  type UserSettingsResponse
} from '../api'
import { authState } from '../../../authState'

const settings = ref<UserSettingsResponse | null>(null)
const displayName = ref('')
const permalink = ref('')
const email = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const passkeys = ref<PasskeySummary[]>([])
const passkeyName = ref(defaultPasskeyName())
const loading = ref(false)
const savingProfile = ref(false)
const savingPassword = ref(false)
const savingPasskey = ref(false)
const deletingPasskeyId = ref<number | null>(null)
const error = ref('')
const profileMessage = ref('')
const passwordMessage = ref('')
const passkeyMessage = ref('')

onMounted(loadSettings)

async function loadSettings() {
  loading.value = true
  error.value = ''
  try {
    const [nextSettings, nextPasskeys] = await Promise.all([getMySettings(), listMyPasskeys()])
    applySettings(nextSettings)
    passkeys.value = nextPasskeys.passkeys
  } catch (caught) {
    settings.value = null
    error.value = caught instanceof Error ? caught.message : 'User-Einstellungen konnten nicht geladen werden.'
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
        ? 'Profil wurde gespeichert. Bitte bestaetige die neue Email-Adresse.'
        : 'Profil wurde gespeichert.'
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : 'Profil konnte nicht gespeichert werden.'
  } finally {
    savingProfile.value = false
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
    passwordMessage.value = 'Passwort wurde geändert.'
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : 'Passwort konnte nicht geändert werden.'
  } finally {
    savingPassword.value = false
  }
}

async function addPasskey() {
  savingPasskey.value = true
  error.value = ''
  passkeyMessage.value = ''
  try {
    const passkey = await createMyPasskey(passkeyName.value || defaultPasskeyName())
    passkeys.value = [passkey, ...passkeys.value]
    if (settings.value) {
      settings.value = { ...settings.value, passkeyCount: passkeys.value.length }
    }
    if (authState.user) {
      authState.user.passkeyCount = passkeys.value.length
    }
    passkeyName.value = defaultPasskeyName()
    passkeyMessage.value = 'Passkey wurde hinzugefügt.'
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : 'Passkey konnte nicht hinzugefügt werden.'
  } finally {
    savingPasskey.value = false
  }
}

async function removePasskey(passkey: PasskeySummary) {
  deletingPasskeyId.value = passkey.passkeyId
  error.value = ''
  passkeyMessage.value = ''
  try {
    await deleteMyPasskey(passkey.passkeyId)
    passkeys.value = passkeys.value.filter((item) => item.passkeyId !== passkey.passkeyId)
    if (settings.value) {
      settings.value = { ...settings.value, passkeyCount: passkeys.value.length }
    }
    if (authState.user) {
      authState.user.passkeyCount = passkeys.value.length
    }
    passkeyMessage.value = 'Passkey wurde gelöscht.'
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : 'Passkey konnte nicht gelöscht werden.'
  } finally {
    deletingPasskeyId.value = null
  }
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
    authState.userLabel = nextSettings.displayName || nextSettings.email
  }
}

function formatTimestamp(value?: string) {
  if (!value) return 'Noch nicht verwendet'
  return new Intl.DateTimeFormat('de-DE', {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(new Date(value))
}

function defaultPasskeyName() {
  return 'Dieses Gerät'
}
</script>

<template>
  <section class="settings-panel">
    <h1>Meine Einstellungen</h1>
    <p>Profil und Anmeldung</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div v-if="loading" class="memory-loading">
      <ProgressSpinner class="small-spinner" />
      <span>User-Einstellungen werden geladen...</span>
    </div>

    <template v-else-if="settings">
      <form class="form settings-form" @submit.prevent="saveProfile">
        <label>
          <span>Anzeigename</span>
          <InputText v-model="displayName" autocomplete="name" />
        </label>
        <label>
          <span>Permalink</span>
          <InputText v-model="permalink" autocomplete="off" />
        </label>
        <label>
          <span>Email</span>
          <InputText v-model="email" type="email" autocomplete="email" />
        </label>
        <Message v-if="settings.pendingEmail" severity="info" :closable="false">
          Neue Email wartet auf Bestaetigung: {{ settings.pendingEmail }}
        </Message>
        <div class="button-row">
          <Button type="submit" label="Speichern" icon="pi pi-save" :loading="savingProfile" />
        </div>
        <Message v-if="profileMessage" severity="success" :closable="false">{{ profileMessage }}</Message>
      </form>

      <form class="form settings-form" @submit.prevent="savePassword">
        <label>
          <span>Aktuelles Passwort</span>
          <Password v-model="currentPassword" autocomplete="current-password" toggle-mask :feedback="false" />
        </label>
        <label>
          <span>Neues Passwort</span>
          <Password v-model="newPassword" autocomplete="new-password" toggle-mask />
        </label>
        <div class="button-row">
          <Button type="submit" label="Passwort ändern" icon="pi pi-key" :loading="savingPassword" />
        </div>
        <Message v-if="passwordMessage" severity="success" :closable="false">{{ passwordMessage }}</Message>
      </form>

      <section class="form settings-form">
        <div>
          <h2>Passkeys</h2>
          <p>Empfohlen</p>
        </div>
        <Message v-if="passkeys.length === 0" severity="info" :closable="false">
          Passkeys sind die empfohlene Anmeldung fuer Veyq.
        </Message>
        <div v-if="passkeys.length" class="passkey-list">
          <div v-for="passkey in passkeys" :key="passkey.passkeyId" class="passkey-row">
            <div>
              <strong>{{ passkey.displayName }}</strong>
              <span>Erstellt: {{ formatTimestamp(passkey.createdAt) }}</span>
              <span>Zuletzt verwendet: {{ formatTimestamp(passkey.lastUsedAt) }}</span>
            </div>
            <Button
              type="button"
              icon="pi pi-trash"
              severity="danger"
              text
              :loading="deletingPasskeyId === passkey.passkeyId"
              aria-label="Passkey löschen"
              @click="removePasskey(passkey)"
            />
          </div>
        </div>
        <label>
          <span>Name</span>
          <InputText v-model="passkeyName" autocomplete="off" />
        </label>
        <div class="button-row">
          <Button
            type="button"
            label="Passkey hinzufügen"
            icon="pi pi-key"
            :loading="savingPasskey"
            @click="addPasskey"
          />
        </div>
        <Message v-if="passkeyMessage" severity="success" :closable="false">{{ passkeyMessage }}</Message>
      </section>
    </template>
  </section>
</template>
