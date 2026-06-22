<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { registerServerOwner } from '../features/admin/setupServer/setupServer'
import { downloadKitPdf } from '../features/account/emergencyKit/emergencyKit'
import { usePwnedWarning } from '../features/account/passwordWarning'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../shared/passwordPolicy'
import { session } from '../app/session'
import { setAuthPrefill } from '../app/authPrefill'
import { useServerState } from '../shared/state/useServerState'

const router = useRouter()
const { t } = useI18n()
const { setSetupCompleted } = useServerState()
const displayName = ref('')
const email = ref('')
const password = ref('')
const { breachCount } = usePwnedWarning(password)
const busy = ref(false)
const error = ref('')
const secretKey = ref<string | null>(null)
let kitEmail = ''

async function submit() {
  if (password.value.length < MIN_PASSWORD_LENGTH) return
  error.value = ''
  busy.value = true
  try {
    const { emergencyKit } = await registerServerOwner(session.apiBase, email.value, displayName.value, password.value)
    kitEmail = email.value
    secretKey.value = emergencyKit.secretKey
    setSetupCompleted(true)
  } catch {
    error.value = t('setup.failed')
  } finally {
    busy.value = false
  }
}

function proceed() {
  // Hand the email + Secret Key to the login form so the owner doesn't retype them right after setup.
  setAuthPrefill(kitEmail, secretKey.value!)
  void router.push({ name: 'login' })
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
            {{ t('setup.displayName') }}
            <InputText v-model="displayName" type="text" required autofocus />
          </label>
          <label>
            {{ t('common.email') }}
            <InputText v-model="email" type="email" autocomplete="username" required />
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
          <Message v-if="breachCount > 0" severity="warn" :closable="false">{{ t('password.pwnedWarning', { count: breachCount }) }}</Message>
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
        <pre class="kit-secret">{{ secretKey }}</pre>
        <Button
          type="button"
          :label="t('kit.download')"
          icon="pi pi-download"
          severity="secondary"
          @click="downloadKitPdf({ instance: session.apiBase, email: kitEmail, secretKey: secretKey! }, displayName)"
        />
        <Button type="button" :label="t('setup.kitProceed')" icon="pi pi-arrow-right" @click="proceed" />
      </template>

      <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
    </template>
  </Card>
</template>

<style scoped>
.kit-secret {
  white-space: pre-wrap;
  word-break: break-all;
  font-family: var(--font-mono, monospace);
  background: var(--p-content-border-color, rgba(127, 127, 127, 0.15));
  padding: 0.75rem;
  border-radius: 8px;
  margin: 0 0 0.75rem;
}
</style>
