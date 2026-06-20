<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { login } from '../features/account/login/login'
import { parseKit } from '../features/account/emergencyKit/emergencyKit'
import { requestPasswordReset } from '../features/account/resetPassword/resetPassword'
import { rememberedLoginSupported, saveRememberedLogin } from '../features/account/rememberedLogin/rememberedLogin'
import { isUnlocked, session, setUnlocked } from '../app/session'
import { takeAuthPrefill } from '../app/authPrefill'
import { t } from '../i18n/i18n'
import QrScanner from '../components/auth/QrScanner.vue'

const router = useRouter()
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

function redirectTarget(): string {
  const redirect = router.currentRoute.value.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/'
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
  // The payload lives as ASCII in the QR text, the .veyqkit body, or the PDF's Keywords metadata —
  // reading the file as text surfaces it regardless of container.
  const text = await file.text()
  input.value = ''
  applyKit(text)
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
    setUnlocked(await login(session.apiBase, email.value, password.value, secretKey.value))
    if (remember.value && canRemember) {
      saveRememberedLogin({ email: email.value, password: password.value, secretKey: secretKey.value })
    }
    await router.push(redirectTarget())
  } catch {
    error.value = t('login.failed')
  } finally {
    busy.value = false
  }
}

async function sendReset() {
  error.value = ''
  const requestEmail = resetEmail.value.trim()
  if (!requestEmail) return
  sendingReset.value = true
  try {
    await requestPasswordReset(session.apiBase, requestEmail)
    resetDialogVisible.value = false
    message.value = t('reset.sent')
  } catch {
    error.value = t('reset.requestFailed')
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
  }
})
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('login.title') }}</template>
    <template #content>
      <form class="veyq-form" @submit.prevent="submit">
        <label>
          {{ t('common.email') }}
          <InputText v-model="email" type="email" autocomplete="username" required />
        </label>
        <label>
          {{ t('common.password') }}
          <Password
            v-model="password"
            input-id="login-password"
            :input-props="{ autocomplete: 'current-password' }"
            required
            :feedback="false"
            toggle-mask
            fluid
          />
        </label>
        <label>
          {{ t('common.secretKey') }}
          <Password
            v-model="secretKey"
            input-id="login-secret"
            :input-props="{ autocomplete: 'off' }"
            required
            :feedback="false"
            toggle-mask
            fluid
          />
          <small class="field-hint">{{ t('login.secretHint') }}</small>
        </label>
        <label v-if="canRemember" class="remember-row">
          <input v-model="remember" type="checkbox" />
          <span>{{ t('login.remember') }}</span>
        </label>
        <Button type="submit" :label="busy ? t('login.submitting') : t('login.submit')" icon="pi pi-sign-in" :loading="busy" />
      </form>

      <div class="kit-import">
        <span class="field-hint">{{ t('kit.importHint') }}</span>
        <div class="kit-actions">
          <label class="kit-file-trigger">
            <span class="p-button p-component p-button-secondary p-button-text">{{ t('kit.importFile') }}</span>
            <input class="kit-file-input" type="file" accept=".pdf,.veyqkit,application/pdf,text/plain" @change="importKit" />
          </label>
          <Button v-if="canScan" type="button" :label="t('kit.scan')" icon="pi pi-qrcode" severity="secondary" text @click="scanning = true" />
        </div>
      </div>

      <Button type="button" :label="t('reset.forgot')" severity="secondary" text @click="openResetDialog" />

      <Dialog v-model:visible="resetDialogVisible" :header="t('reset.title')" modal class="tenant-dialog">
        <form class="veyq-form" @submit.prevent="sendReset">
          <p class="veyq-form-copy">{{ t('reset.requestHint') }}</p>
          <label>
            {{ t('common.email') }}
            <InputText v-model="resetEmail" type="email" autocomplete="email" required autofocus />
          </label>
          <div class="veyq-dialog-action-row">
            <Button type="button" :label="t('common.cancel')" severity="secondary" text @click="resetDialogVisible = false" />
            <Button type="submit" :label="t('reset.sendLink')" icon="pi pi-envelope" :loading="sendingReset" />
          </div>
        </form>
      </Dialog>

      <Message v-if="message" severity="success" :closable="false" class="form-message">{{ message }}</Message>
      <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
    </template>
  </Card>
  <QrScanner v-if="scanning" @decoded="onScanned" @close="scanning = false" />
</template>
