<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
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
import { cacheSessionVault, clearSessionVault } from '../features/account/rememberMe/sessionVault'
import { LoginVerifyRequestSessionScopeEnum } from '../api/generated/auth'
import { importKitFromFile } from '../features/account/emergencyKit/emergencyKit'
import KitScanner from '../features/account/emergencyKit/KitScanner.vue'
import { vaultState, masterKey } from '../app/vault'
import { takeAuthPrefill } from '../app/authPrefill'
import { takeRoute } from '../app/pendingRoute'

// uc.account.remember-this-device (web, cr-0054) — ONE adaptive login/unlock screen with ONE "remember me"
// toggle (ADR 0005 §17-21). The same password authenticates AND derives the DEVICE-wrap KEK, so login + unlock
// collapse into a single submit. The screen shows only the fields this device still needs: the FIRST sign-in
// per device (no DEVICE wrap yet) adds the **Secret Key** field; once a wrap exists it is email + password
// only; a remembered device opens straight in at boot (no screen). On a successful unlock the client
// AUTO-ENROLS this device's single convenience wrap from the in-memory Master Key: remember off → a password
// DEVICE wrap; remember on → a password-less DEVICE_KEY wrap + the persistent session cookie. One wrap per
// device (§9): enrolling one drops the other. The Secret Key is typed once per device, then forgotten.
const { t } = useI18n()
const router = useRouter()
const email = ref('')
const password = ref('')
const secretKey = ref('')
const busy = ref(false)
const error = ref('')
// Adaptive: the Secret-Key field shows only when THIS device has no password DEVICE wrap for the entered
// email (a trusted device unlocks with email + password and never shows it). Default false (hidden) — we
// can't know the account until an email is entered — and resolved against the local trust record on mount +
// when the email changes; an empty email (no account yet) keeps it hidden.
const needsSecretKey = ref(false)
// Login already succeeded for the current inputs → a Secret-Key retry must re-unlock, not re-login.
const sessionReady = ref(false)
// The single per-device choice. Drives the login cookie (on → persistent) AND which convenience wrap is
// auto-enrolled. Only offered where IndexedDB + WebCrypto exist (a wrap can't otherwise be persisted).
const remember = ref(false)
const rememberSupported = isRememberSupported()
const fileInput = ref<HTMLInputElement | null>(null)
const showSecret = ref(false)
const scanOpen = ref(false)
const scanSupported = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia

/** Decide whether to show the Secret-Key field for the entered email: needed unless this device already has a
 *  password DEVICE wrap for it (a remembered device auto-opens at boot, so if it reached this screen its
 *  restore failed and it correctly falls back to needing the Secret Key). Defaults to "needed" on any error. */
async function resolveDeviceState() {
  // No email yet → unknown account, don't ask for the Secret Key. Otherwise it's needed unless this device
  // already carries a password DEVICE wrap for the email (then email + password unlock it). A remembered
  // device auto-opens at boot; if it reached this screen its restore failed, so it falls back to needing it.
  needsSecretKey.value = email.value ? !(await isTrusted(email.value).catch(() => false)) : false
}

/** Auto-enrol this device's convenience tier from the in-memory Master Key, per "remember me" (§18, §22).
 *  - **on** (persistent): a password-less DEVICE_KEY wrap (IndexedDB) — opens straight in across a restart.
 *  - **off** (session-scoped): the Master Key in `sessionStorage` (this browser session) PLUS a password DEVICE
 *    wrap so the next launch after a close is email + password (never the Secret Key).
 *  One tier per device, so the other is dropped first. Best-effort — the unlock already succeeded, so a failed
 *  enrolment just means a bit more friction next time (no silent lockout). */
async function enrolConvenienceWrap() {
  const mk = masterKey()
  if (!mk) return
  if (remember.value) {
    await clearSessionVault() // drop the session-scoped vault — this device opts into persistence
    if (rememberSupported) {
      await untrustThisDevice(email.value).catch(() => {}) // drop a prior password DEVICE wrap
      await enrolDeviceKey(email.value, mk).catch(() => {})
    }
  } else {
    await cacheSessionVault(email.value, mk) // survive reloads this browser session
    if (rememberSupported) {
      await clearDeviceKey().catch(() => {}) // drop a prior password-less DEVICE_KEY wrap
      await trustThisDevice(email.value, password.value, mk).catch(() => {})
    }
  }
}

async function finishAndRoute() {
  password.value = ''
  secretKey.value = ''
  await router.push(takeRoute() ?? { name: 'home' })
}

async function submit() {
  error.value = ''
  busy.value = true
  try {
    // The password both authenticates and derives the unlock KEK, so a first submit logs in; a Secret-Key
    // retry (session already up) skips straight to unlock.
    if (!sessionReady.value) {
      // Web is cookie-based: the toggle picks the refresh-cookie lifetime — persistent ("remember me") vs a
      // session cookie (survives reloads, gone on browser close, §22).
      const scope = remember.value
        ? LoginVerifyRequestSessionScopeEnum.Persistent
        : LoginVerifyRequestSessionScopeEnum.Session
      await login(email.value, password.value, scope)
      sessionReady.value = true
    }
    if (!needsSecretKey.value) {
      // Returning trusted device — unlock with only the password (the DEVICE wrap).
      if (await unlockWithDevice(email.value, password.value).catch(() => false)) {
        // "remember me" on now → upgrade this device to the password-less wrap (the toggle is the per-device
        // choice, §18). Off → keep the DEVICE wrap, but refresh the session vault so reloads stay prompt-free.
        if (remember.value) await enrolConvenienceWrap()
        else await cacheSessionVault(email.value, masterKey()!)
        await finishAndRoute()
        return
      }
      // The device's wrap can't unlock (a stale local trust record whose server wrap is gone — e.g. it was
      // reset, ADR 0005 E2). This is NOT a failure: reveal the Secret-Key field (no error) and let the user
      // finish with it; the next submit re-unlocks + re-enrols, replacing the stale record. The local key is
      // intentionally kept (no self-wipe on a single server response — operator-blind anti-withholding).
      needsSecretKey.value = true
      return
    }
    // First sign-in / degraded device — unlock with the on-screen Secret Key, then auto-enrol the wrap.
    await unlockMasterKey(password.value, secretKey.value)
    await enrolConvenienceWrap()
    await finishAndRoute()
  } catch {
    // Before login: wrong password / unknown account → one generic failure. After: wrong Secret Key (keep the
    // session + inputs for a retry). Both non-enumerable.
    error.value = sessionReady.value ? t('unlock.failed') : t('login.failed')
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
  sessionReady.value = false
  password.value = ''
  secretKey.value = ''
  error.value = ''
  await resolveDeviceState()
}

// A different email is a different account. Two deliberately-split mechanisms: the watcher drops sessionReady on
// every keystroke (cheap — a re-login is required the moment the email changes, even before blur), while the
// expensive IndexedDB trust lookup that decides whether to show the Secret-Key field runs only on blur
// (@change) so the field doesn't flicker while typing.
function onEmailChange() {
  void resolveDeviceState()
}

watch(email, () => {
  sessionReady.value = false
})

onMounted(async () => {
  // Setup hands over email + Secret Key once, for the first sign-in. Both pre-fill the (adaptive) screen — the
  // device is fresh, so the Secret-Key field is shown. A returning user has no prefill.
  const prefill = takeAuthPrefill()
  if (prefill) {
    email.value = prefill.email
    secretKey.value = prefill.secretKey
  }
  await resolveDeviceState()
})
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('login.title') }}</template>
    <template #content>
      <!-- One adaptive sign-in: email + password always; the Secret-Key field only on the first sign-in per
           device (or when a device wrap is unusable). A single submit logs in AND unlocks. -->
      <form v-if="!vaultState.unlocked" class="veyq-form" @submit.prevent="submit">
        <label>
          {{ t('common.email') }}
          <InputText
            v-model="email"
            type="email"
            name="email"
            autocomplete="username"
            required
            @change="onEmailChange"
          />
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
        <label v-if="needsSecretKey">
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
          <small class="field-hint">{{ t('unlock.secretKeyHint') }}</small>
          <input
            ref="fileInput"
            type="file"
            accept="application/pdf,image/*"
            class="visually-hidden"
            @change="onUpload"
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
        <Button
          v-if="sessionReady"
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
