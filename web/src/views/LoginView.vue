<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { login, logout } from '../features/account/login/login'
import { unlockMasterKey, unlockWithDevice } from '../features/account/unlock/unlock'
import { trustThisDevice, untrustThisDevice, isTrusted } from '../features/account/trustDevice/trustDevice'
import { enrolDeviceKey, clearDeviceKey, isRememberSupported } from '../features/account/rememberMe/deviceKeyWrap'
import { importKitFromFile } from '../features/account/emergencyKit/emergencyKit'
import KitScanner from '../features/account/emergencyKit/KitScanner.vue'
import { vaultState, masterKey } from '../app/vault'
import { takeAuthPrefill } from '../app/authPrefill'
import { takeRoute } from '../app/pendingRoute'

// uc.account.remember-this-device (web, cr-0054) — ONE combined login/unlock screen with ONE "remember me"
// toggle. The same password authenticates AND derives the DEVICE-wrap KEK, so login + unlock collapse: a
// device that already carries a convenience wrap signs in with email + password only; the Secret-Key floor
// shows only on the first sign-in per device (or when a device wrap is unusable). On a successful unlock the
// client AUTO-ENROLS this device's single convenience wrap from the in-memory Master Key (ADR 0005 §17-21):
// remember off → a password DEVICE wrap; remember on → a password-less DEVICE_KEY wrap + the persistent
// session cookie. One wrap per device (§9): enrolling one drops the other. The Secret Key is read once to
// create the wrap, then forgotten — there is no separate "trust this device" checkbox any more.
const { t } = useI18n()
const router = useRouter()
const email = ref('')
const password = ref('')
const secretKey = ref('')
const busy = ref(false)
const error = ref('')
const loggedIn = ref(false)
// The single per-device choice. Drives the login cookie (on → persistent) AND which convenience wrap is
// auto-enrolled. Only offered where IndexedDB + WebCrypto exist (a wrap can't otherwise be persisted).
const remember = ref(false)
const rememberSupported = isRememberSupported()
const fileInput = ref<HTMLInputElement | null>(null)
const showSecret = ref(false)
const scanOpen = ref(false)
const scanSupported = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia

// A Secret Key handed over by setup is parked here (a plain local, not a bound input) until login succeeds.
let pendingSecretKey = ''

/** Auto-enrol this device's single convenience wrap from the in-memory Master Key, per "remember me" (§18).
 *  One wrap per device, so the other method is dropped first. Best-effort — the unlock already succeeded, so a
 *  failed enrolment just means the Secret Key is needed again next time (no silent lockout). */
async function enrolConvenienceWrap() {
  const mk = masterKey()
  if (!mk || !rememberSupported) return
  if (remember.value) {
    await untrustThisDevice(email.value).catch(() => {}) // drop a prior password DEVICE wrap
    await enrolDeviceKey(email.value, mk).catch(() => {})
  } else {
    await clearDeviceKey().catch(() => {}) // drop a prior password-less DEVICE_KEY wrap
    await trustThisDevice(email.value, password.value, mk).catch(() => {})
  }
}

function finishUnlocked() {
  loggedIn.value = true
  password.value = ''
  secretKey.value = ''
  pendingSecretKey = ''
}

async function submit() {
  error.value = ''
  busy.value = true
  try {
    await login(email.value, password.value, remember.value)
    // Stay on the (spinner-busy) sign-in card while we try a device-wrap unlock — so a returning device jumps
    // straight to the shell and never flashes the Secret-Key floor. (An IndexedDB hiccup must not break a
    // successful login — default to the floor if the check fails.)
    const trusted = await isTrusted(email.value).catch(() => false)
    if (trusted && (await unlockWithDevice(email.value, password.value).catch(() => false))) {
      // If "remember me" is now on, upgrade this password-trusted device to the password-less wrap (the toggle
      // is the per-device choice, §18). Off → keep the existing DEVICE wrap as-is (no needless re-enrol).
      if (remember.value) await enrolConvenienceWrap()
      finishUnlocked()
      await router.push(takeRoute() ?? { name: 'home' })
      return
    }
    // First sign-in on this device, or the device wrap is unusable (E2) — show the password + Secret-Key floor.
    secretKey.value = pendingSecretKey
    pendingSecretKey = ''
    loggedIn.value = true
  } catch {
    // wrong password and unknown account both surface as the same generic failure (non-enumerable)
    error.value = t('login.failed')
  } finally {
    busy.value = false
  }
}

async function unlock() {
  error.value = ''
  busy.value = true
  try {
    // Floor: password (carried from login) + Secret Key. The first unlock per device → auto-enrol the
    // convenience wrap chosen by "remember me", while the just-entered password is still in hand.
    await unlockMasterKey(password.value, secretKey.value)
    await enrolConvenienceWrap()
    finishUnlocked()
    await router.push(takeRoute() ?? { name: 'home' })
  } catch {
    // Wrong password / Secret Key (or no wrap) — one generic failure. Keep the inputs for a retry.
    error.value = t('unlock.failed')
  } finally {
    busy.value = false
  }
}

function triggerUpload() {
  error.value = ''
  fileInput.value?.click()
}

async function onUpload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // allow re-picking the same file
  if (!file) return
  const kit = await importKitFromFile(file)
  // generic failure — never reveal why a kit couldn't be read
  if (kit) secretKey.value = kit.secretKey
  else error.value = t('unlock.kitUnreadable')
}

function onScanned(kit: { secretKey: string }) {
  secretKey.value = kit.secretKey
  scanOpen.value = false
  error.value = ''
}

async function signOut() {
  await logout() // revokes the session, drops tokens, and re-locks the vault (centralised in logout)
  loggedIn.value = false
  password.value = ''
  secretKey.value = ''
  pendingSecretKey = ''
  error.value = ''
}

onMounted(() => {
  // Setup hands over email + Secret Key once. The email pre-fills sign-in; the Secret Key is parked off the
  // reactive ref and surfaces only once login succeeds. A returning user has no prefill.
  const prefill = takeAuthPrefill()
  if (prefill) {
    email.value = prefill.email
    pendingSecretKey = prefill.secretKey
  }
})
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('login.title') }}</template>
    <template #content>
      <!-- Sign in -->
      <form v-if="!loggedIn" class="veyq-form" @submit.prevent="submit">
        <label>
          {{ t('common.email') }}
          <InputText v-model="email" type="email" name="email" autocomplete="username" required />
        </label>
        <label>
          {{ t('common.password') }}
          <Password
            v-model="password"
            input-id="login-password"
            :input-props="{ name: 'password', autocomplete: 'current-password' }"
            required
            :feedback="false"
            toggle-mask
            fluid
          />
        </label>
        <div v-if="rememberSupported" class="checkbox-row">
          <Checkbox v-model="remember" input-id="remember-me" binary />
          <label for="remember-me">
            {{ t('login.rememberMe') }}
            <small class="field-hint">{{ t('login.rememberMeHint') }}</small>
          </label>
        </div>
        <Button
          type="submit"
          :label="busy ? t('login.submitting') : t('login.submit')"
          icon="pi pi-sign-in"
          :loading="busy"
        />
        <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
      </form>

      <!-- Unlock: Secret-Key floor (a trusted device auto-unlocks at login and never shows this) -->
      <form v-else-if="!vaultState.unlocked" class="veyq-form" @submit.prevent="unlock">
        <Message severity="info" :closable="false">{{ t('unlock.title') }}</Message>
        <p class="veyq-form-copy">{{ t('unlock.note') }}</p>
        <label>
          {{ t('unlock.secretKey') }}
          <div class="secret-field" :style="{ '--secret-icons': scanSupported ? 3 : 2 }">
            <InputText
              v-model="secretKey"
              id="unlock-secret-key"
              :type="showSecret ? 'text' : 'password'"
              name="secret-key"
              autocomplete="off"
              :spellcheck="false"
              required
              fluid
            />
            <span class="secret-icons">
              <button
                type="button"
                class="secret-icon"
                :aria-label="t('unlock.upload')"
                :title="t('unlock.upload')"
                @click="triggerUpload"
              >
                <i class="pi pi-upload" aria-hidden="true" />
              </button>
              <button
                v-if="scanSupported"
                type="button"
                class="secret-icon"
                :aria-label="t('unlock.scan')"
                :title="t('unlock.scan')"
                @click="scanOpen = true"
              >
                <i class="pi pi-qrcode" aria-hidden="true" />
              </button>
              <button
                type="button"
                class="secret-icon"
                :aria-label="t(showSecret ? 'common.hide' : 'common.show')"
                :title="t(showSecret ? 'common.hide' : 'common.show')"
                @click="showSecret = !showSecret"
              >
                <i :class="showSecret ? 'pi pi-eye-slash' : 'pi pi-eye'" aria-hidden="true" />
              </button>
            </span>
          </div>
          <input
            ref="fileInput"
            type="file"
            accept="application/pdf,image/*"
            class="visually-hidden"
            @change="onUpload"
          />
        </label>
        <Button
          type="submit"
          :label="busy ? t('unlock.submitting') : t('unlock.submit')"
          icon="pi pi-unlock"
          :loading="busy"
        />
        <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
        <Button
          type="button"
          :label="t('login.signOut')"
          icon="pi pi-sign-out"
          severity="secondary"
          text
          @click="signOut"
        />
      </form>

      <!-- Unlocked → redirecting into the shell. No confirmation card; the navigation is the feedback. -->
      <div v-else class="login-redirecting" aria-hidden="true">
        <span class="pi pi-spin pi-spinner" />
      </div>
    </template>
  </Card>
  <KitScanner v-if="scanOpen" @found="onScanned" @close="scanOpen = false" />
</template>

<style scoped>
/* The unlocked state only flashes for the moment before the redirect — a neutral spinner, no card. */
.login-redirecting {
  display: grid;
  place-items: center;
  padding: 1.5rem 0;
}
.login-redirecting .pi-spinner {
  font-size: 1.5rem;
  color: var(--app-muted);
}
</style>
