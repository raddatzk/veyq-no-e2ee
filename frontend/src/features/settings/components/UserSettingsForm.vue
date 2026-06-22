<script setup lang="ts">
// User account settings for the E2EE backend. Replaces the legacy passkey/TOTP/avatar form: the operator-
// blind server has none of those. What remains is what the new backend supports — the display name (the one
// low-sensitivity, server-visible profile field) and the list of DEVICES (unlock wraps) the account can be
// opened with, which the user can name at login and remove here (this is the passkey-list replacement).
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { logout, updateProfile } from '../../../features/account/login/login'
import { listMyKeyWraps, deviceId, type KeyWrapSummary } from '../../../features/keys/deviceUnlock'
import { removeMyMasterKeyWrap } from '../../../features/keys/rewrapScopeGrant'
import { clearRememberedLogin } from '../../../features/account/rememberedLogin/rememberedLogin'
import { requireSession, session, setUnlocked } from '../../../app/session'

const router = useRouter()
const { t } = useI18n()

const displayName = ref('')
const email = ref('')
const devices = ref<KeyWrapSummary[]>([])
const loading = ref(false)
const savingProfile = ref(false)
const removingRef = ref<string | null>(null)
const error = ref('')
const profileMessage = ref('')
const deviceMessage = ref('')
const thisDeviceId = deviceId()

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const s = requireSession()
    displayName.value = s.user.displayName
    email.value = s.user.email
    devices.value = (await listMyKeyWraps(session.apiBase, s.sessionToken)).filter((w) => w.unlockMethod === 'DEVICE')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('settings.loadFailed')
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  savingProfile.value = true
  error.value = ''
  profileMessage.value = ''
  try {
    const s = requireSession()
    const updated = await updateProfile(session.apiBase, s.sessionToken, displayName.value)
    s.user.displayName = updated.displayName
    profileMessage.value = t('profile.profileSaveSuccess')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('profile.profileSaveFailed')
  } finally {
    savingProfile.value = false
  }
}

function isCurrentDevice(device: KeyWrapSummary): boolean {
  return device.credentialRef === thisDeviceId
}

async function removeDevice(device: KeyWrapSummary) {
  if (!device.credentialRef) return
  removingRef.value = device.credentialRef
  error.value = ''
  deviceMessage.value = ''
  try {
    const s = requireSession()
    await removeMyMasterKeyWrap(session.apiBase, s.sessionToken, 'DEVICE', device.credentialRef)
    devices.value = devices.value.filter((d) => d.credentialRef !== device.credentialRef)
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
  <section class="veyq-surface settings-panel">
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
        </div>
        <form class="veyq-form" @submit.prevent="saveProfile">
          <label>
            {{ t('auth.displayName') }}
            <InputText v-model="displayName" type="text" required />
          </label>
          <label>
            {{ t('common.email') }}
            <InputText :model-value="email" type="email" disabled />
          </label>
          <Button type="submit" :label="t('common.save')" icon="pi pi-save" :loading="savingProfile" />
          <Message v-if="profileMessage" severity="success" :closable="false">{{ profileMessage }}</Message>
        </form>
      </section>

      <!-- Devices (replaces the old passkey list) -->
      <section class="settings-section passkey-section">
        <div class="settings-section-header">
          <h2>{{ t('device.title') }}</h2>
          <p>{{ t('device.subtitle') }}</p>
        </div>

        <Message v-if="devices.length === 0" severity="info" :closable="false">{{ t('device.empty') }}</Message>

        <div v-else class="passkey-list">
          <div v-for="device in devices" :key="device.credentialRef" class="passkey-row">
            <div>
              <strong>{{ device.name || t('device.unnamed') }}</strong>
              <span v-if="isCurrentDevice(device)" class="device-current">{{ t('device.thisDevice') }}</span>
              <span v-if="device.createdAt">{{ t('device.created') }}: {{ formatTimestamp(device.createdAt) }}</span>
            </div>
            <div class="passkey-row-actions">
              <Button
                type="button"
                icon="pi pi-trash"
                severity="danger"
                text
                :loading="removingRef === device.credentialRef"
                :aria-label="t('device.remove')"
                @click="removeDevice(device)"
              />
            </div>
          </div>
        </div>

        <Message v-if="deviceMessage" severity="success" :closable="false">{{ deviceMessage }}</Message>
      </section>

      <!-- Sign out -->
      <section class="settings-section">
        <Button type="button" :label="t('auth.logout')" icon="pi pi-sign-out" severity="secondary" @click="signOut" />
      </section>

      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    </template>
  </section>
</template>

<style scoped>
.settings-section {
  margin-top: 1.5rem;
}
.device-current {
  font-weight: 600;
  color: var(--p-primary-color, #5b4df5);
}
</style>
