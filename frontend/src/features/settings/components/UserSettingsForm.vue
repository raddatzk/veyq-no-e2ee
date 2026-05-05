<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import ProgressSpinner from 'primevue/progressspinner'
import {
  changeMyPassword,
  getMySettings,
  updateMySettings,
  type UserSettingsResponse
} from '../api'
import { authState } from '../../../authState'

const settings = ref<UserSettingsResponse | null>(null)
const displayName = ref('')
const email = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const loading = ref(false)
const savingProfile = ref(false)
const savingPassword = ref(false)
const error = ref('')
const profileMessage = ref('')
const passwordMessage = ref('')

onMounted(loadSettings)

async function loadSettings() {
  loading.value = true
  error.value = ''
  try {
    applySettings(await getMySettings())
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
    const nextSettings = await updateMySettings({ displayName: displayName.value, email: email.value })
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

function applySettings(nextSettings: UserSettingsResponse) {
  settings.value = nextSettings
  displayName.value = nextSettings.displayName
  email.value = nextSettings.email
  if (authState.user?.userId === nextSettings.userId) {
    authState.user.displayName = nextSettings.displayName
    authState.user.email = nextSettings.email
    authState.userLabel = nextSettings.displayName || nextSettings.email
  }
}
</script>

<template>
  <section class="settings-panel">
    <h1>Meine Einstellungen</h1>
    <p>Profil und Anmeldung</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div v-if="loading" class="explorer-loading">
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
    </template>
  </section>
</template>
