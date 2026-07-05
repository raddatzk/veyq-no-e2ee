<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { registerServerOwner } from '../features/admin/setupServer/setupServer'
import { forgetAllTrust } from '../features/account/trustDevice/trustDevice'
import { clearDeviceKey } from '../features/account/rememberMe/deviceKeyWrap'
import { downloadKitPdf } from '../features/account/emergencyKit/emergencyKit'
import { usePwnedWarning } from '../features/account/passwordWarning'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../shared/passwordPolicy'
import { setAuthPrefill } from '../app/authPrefill'
import { useServerState } from '../shared/state/useServerState'

const router = useRouter()
const { t } = useI18n()
const { setSetupCompleted } = useServerState()
const email = ref('')
const password = ref('')
const { breachCount } = usePwnedWarning(password)
const busy = ref(false)
const error = ref('')
const secretKey = ref<string | null>(null)
const kitDownloadTriggered = ref(false)
const kitBackupConfirmed = ref(false)
const canProceed = computed(() => kitDownloadTriggered.value && kitBackupConfirmed.value)
let kitEmail = ''

async function submit() {
  if (password.value.length < MIN_PASSWORD_LENGTH) return
  error.value = ''
  busy.value = true
  try {
    const { emergencyKit } = await registerServerOwner(email.value, password.value)
    kitEmail = email.value
    secretKey.value = emergencyKit.secretKey
    kitDownloadTriggered.value = false
    kitBackupConfirmed.value = false
    setSetupCompleted(true)
  } catch {
    error.value = t('setup.failed')
  } finally {
    busy.value = false
  }
}

async function proceed() {
  if (!canProceed.value) return
  // Fresh instance after setup: the owner now holds their Emergency Kit (just downloaded + confirmed), so the
  // Secret Key lives there. Drop any device convenience wraps this browser kept from a previous account /
  // instance (local only — the server is fresh) so the owner's first sign-in is pristine: the Secret-Key field
  // shows upfront and there is no stale-record fallback. Best-effort; a clear failure must not block proceeding.
  await Promise.allSettled([forgetAllTrust(), clearDeviceKey(false)])
  // Hand the email + Secret Key to the login form so the owner doesn't retype them right after setup.
  setAuthPrefill(kitEmail, secretKey.value!)
  void router.push({ name: 'login' })
}

async function downloadKit() {
  if (!secretKey.value) return
  error.value = ''
  try {
    await downloadKitPdf({ email: kitEmail, secretKey: secretKey.value })
    kitDownloadTriggered.value = true
  } catch {
    error.value = t('kit.downloadFailed')
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ secretKey ? t('setup.kitTitle') : t('setup.title') }}</template>
    <template #content>
      <template v-if="!secretKey">
        <p class="veyq-form-copy">{{ t('setup.sub') }}</p>
        <form class="veyq-form" @submit.prevent="submit">
          <label>
            {{ t('common.email') }}
            <InputText v-model="email" type="email" autocomplete="username" required autofocus />
          </label>
          <label>
            {{ t('common.password') }}
            <Password
              v-model="password"
              input-id="setup-password"
              :input-props="{ autocomplete: 'new-password' }"
              required
              :feedback="false"
              toggle-mask
              fluid
            />
          </label>
          <Message v-if="breachCount > 0" severity="warn" :closable="false">{{
            t('password.pwnedWarning', { count: breachCount })
          }}</Message>
          <small v-if="passwordHintKey(password)" class="field-hint">{{ t(passwordHintKey(password)!) }}</small>
          <Button
            type="submit"
            :label="busy ? t('setup.submitting') : t('setup.submit')"
            icon="pi pi-server"
            :loading="busy"
            :disabled="password.length < MIN_PASSWORD_LENGTH"
          />
        </form>
      </template>

      <template v-else>
        <p class="veyq-form-copy">{{ t('setup.kitSub') }}</p>
        <Button
          type="button"
          :label="t('kit.download')"
          icon="pi pi-download"
          severity="secondary"
          @click="downloadKit"
        />
        <p v-if="kitDownloadTriggered" class="kit-status">
          <span class="pi pi-check" aria-hidden="true" />
          {{ t('kit.downloaded') }}
        </p>
        <label class="checkbox-row kit-confirm">
          <input v-model="kitBackupConfirmed" type="checkbox" :disabled="!kitDownloadTriggered" />
          <span>{{ t('kit.backupDownloadConfirm') }}</span>
        </label>
        <Button
          type="button"
          :label="t('setup.kitProceed')"
          icon="pi pi-arrow-right"
          :disabled="!canProceed"
          @click="proceed"
        />
      </template>

      <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
    </template>
  </Card>
</template>

<style scoped>
.kit-status {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  margin: 0.75rem 0 0;
  color: var(--app-success, #22c55e);
}

.kit-confirm {
  margin: 0.75rem 0;
}
</style>
