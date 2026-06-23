<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Checkbox from 'primevue/checkbox'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { login } from '../features/account/login/login'
import { registerLoginDevice } from '../features/keys/deviceUnlock'
import { parseKit } from '../features/account/emergencyKit/emergencyKit'
import { requestPasswordReset } from '../features/account/resetPassword/resetPassword'
import {
  clearRememberedLogin,
  readRememberedLogin,
  rememberedLoginSupported,
  saveRememberedLogin
} from '../features/account/rememberedLogin/rememberedLogin'
import { isUnlocked, session, setUnlocked } from '../app/session'
import { takeAuthPrefill } from '../app/authPrefill'
import QrScanner from '../components/auth/QrScanner.vue'

const router = useRouter()
const { t } = useI18n()
const email = ref('')
const password = ref('')
const secretKey = ref('')
const remember = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
const canRemember = rememberedLoginSupported()
const scanning = ref(false)
const canScan = !!navigator.mediaDevices?.getUserMedia
const resetDialogVisible = ref(false)
const resetEmail = ref('')
const sendingReset = ref(false)
const kitFileInput = ref<HTMLInputElement | null>(null)

function redirectTarget(): string {
  const redirect = router.currentRoute.value.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : '/'
}

function applyKit(text: string): boolean {
  const kit = parseKit(text)
  if (!kit) {
    error.value = t('kit.importFailed')
    return false
  }
  email.value = kit.email
  secretKey.value = kit.secretKey
  return true
}

async function importKit(event: Event) {
  error.value = ''
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  // The payload lives as ASCII in the QR text or the PDF's Keywords metadata —
  // reading the file as text surfaces it regardless of container.
  const text = await file.text()
  input.value = ''
  applyKit(text)
}

function openKitFilePicker() {
  kitFileInput.value?.click()
}

function onScanned(text: string) {
  scanning.value = false
  applyKit(text)
}

async function submit() {
  error.value = ''
  message.value = ''
  busy.value = true
  try {
    const unlocked = await login(session.apiBase, email.value, password.value, secretKey.value)
    setUnlocked(unlocked)
    await registerLoginDevice(session.apiBase, unlocked)
    if (remember.value && canRemember)
      saveRememberedLogin({ email: email.value, secretKey: secretKey.value }, unlocked)
    else clearRememberedLogin()
    await router.push(redirectTarget())
  } catch {
    error.value = t('auth.loginFailed')
  } finally {
    busy.value = false
  }
}

async function sendReset() {
  error.value = ''
  const requestEmail = resetEmail.value.trim()
  if (!requestEmail) {
    error.value = t('auth.passwordRequiredEmail')
    return
  }
  sendingReset.value = true
  try {
    await requestPasswordReset(session.apiBase, requestEmail)
    resetDialogVisible.value = false
    message.value = t('auth.passwordResetRequested')
  } catch {
    error.value = t('auth.passwordResetRequestFailed')
  } finally {
    sendingReset.value = false
  }
}

function openResetDialog() {
  resetEmail.value = email.value
  resetDialogVisible.value = true
  error.value = ''
  message.value = ''
}

onMounted(() => {
  if (isUnlocked()) {
    void router.push(redirectTarget())
    return
  }
  const prefill = takeAuthPrefill()
  if (prefill) {
    email.value = prefill.email
    secretKey.value = prefill.secretKey
    return
  }
  const remembered = readRememberedLogin()
  if (remembered) {
    email.value = remembered.email
    secretKey.value = remembered.secretKey
    remember.value = true
  }
})
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('auth.login') }}</template>
    <template #content>
      <form class="veyq-form" @submit.prevent="submit">
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
        <label>
          {{ t('common.secretKey') }}
          <div class="secret-key-control">
            <Password
              v-model="secretKey"
              class="secret-key-input"
              input-id="login-secret"
              :input-props="{ autocomplete: 'off' }"
              required
              :feedback="false"
              toggle-mask
              fluid
            />
            <div class="secret-key-actions">
              <Button
                type="button"
                icon="pi pi-upload"
                severity="secondary"
                text
                rounded
                :aria-label="t('kit.importFile')"
                @click="openKitFilePicker"
              />
              <Button
                v-if="canScan"
                type="button"
                icon="pi pi-qrcode"
                severity="secondary"
                text
                rounded
                :aria-label="t('kit.importScan')"
                @click="scanning = true"
              />
            </div>
            <input
              ref="kitFileInput"
              class="kit-file-input"
              type="file"
              accept=".pdf,application/pdf"
              @change="importKit"
            />
          </div>
          <small class="field-hint">{{ t('auth.secretHint') }}</small>
        </label>
        <div v-if="canRemember" class="remember-row">
          <Checkbox v-model="remember" input-id="remember-login" binary />
          <label class="remember-label" for="remember-login">{{ t('auth.remember') }}</label>
        </div>
        <Button
          type="submit"
          :label="busy ? t('auth.submitting') : t('auth.login')"
          icon="pi pi-sign-in"
          :loading="busy"
        />
      </form>

      <Button
        type="button"
        :label="t('auth.forgotPassword')"
        icon="pi pi-envelope"
        severity="secondary"
        text
        @click="openResetDialog"
      />

      <Dialog
        v-model:visible="resetDialogVisible"
        :header="t('auth.passwordResetTitle')"
        modal
        class="tenant-dialog"
      >
        <form class="veyq-form" @submit.prevent="sendReset">
          <p class="veyq-form-copy">{{ t('auth.passwordResetDialogPrompt') }}</p>
          <label>
            {{ t('common.email') }}
            <InputText v-model="resetEmail" type="email" autocomplete="email" required autofocus />
          </label>
          <div class="veyq-dialog-action-row">
            <Button
              type="button"
              :label="t('common.cancel')"
              severity="secondary"
              text
              @click="resetDialogVisible = false"
            />
            <Button
              type="submit"
              :label="t('auth.resetRequestSubmit')"
              icon="pi pi-envelope"
              :loading="sendingReset"
            />
          </div>
        </form>
      </Dialog>

      <Message v-if="message" severity="success" :closable="false" class="form-message">{{
        message
      }}</Message>
      <Message v-if="error" severity="error" :closable="false" class="form-message">{{
        error
      }}</Message>
    </template>
  </Card>
  <QrScanner v-if="scanning" @decoded="onScanned" @close="scanning = false" />
</template>

<style scoped>
.secret-key-control {
  display: flex;
  align-items: stretch;
  gap: 0.5rem;
}

.secret-key-input {
  flex: 1;
  min-width: 0;
}

.secret-key-control :deep(.p-password-input) {
  width: 100%;
}

.secret-key-actions {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  flex: 0 0 auto;
}

.kit-file-input {
  display: none;
}

.remember-row {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  min-width: 0;
  margin: 0.1rem 0 0.25rem;
}

.remember-row :deep(.p-checkbox) {
  flex: 0 0 auto;
}

.remember-row :deep(input) {
  width: auto;
  min-width: 0;
}

.remember-label {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  margin: 0;
  color: var(--app-text);
  font-weight: 600;
  line-height: 1.25;
  white-space: nowrap;
  cursor: pointer;
}
</style>
