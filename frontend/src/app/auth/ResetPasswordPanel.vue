<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { session, setUnlocked } from '../session'
import { t } from '../../i18n/i18n'
import { confirmPasswordReset, getResetMaterial } from '../../features/account/resetPassword/resetPassword'
import { login } from '../../features/account/login/login'
import { parseKit } from '../../features/account/emergencyKit/emergencyKit'
import { usePwnedWarning } from '../../features/account/passwordWarning'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../../shared/passwordPolicy'

const props = defineProps<{ token: string }>()
const emit = defineEmits<{ done: [] }>()

const email = ref('')
const secretKey = ref('')
const password = ref('')
const { breachCount } = usePwnedWarning(password)
const busy = ref(false)
const error = ref('')
const ready = ref(false)

onMounted(async () => {
  try {
    email.value = (await getResetMaterial(session.apiBase, props.token)).email
    ready.value = true
  } catch {
    error.value = t('reset.invalidToken')
  }
})

function importKit(e: Event) {
  const input = e.target as HTMLInputElement
  input.files?.[0]?.text().then((text) => {
    const kit = parseKit(text)
    if (kit) secretKey.value = kit.secretKey
    else error.value = t('kit.importFailed')
    input.value = ''
  })
}

/** Reset the password with the Secret Key, then sign in with the new password + Secret Key. */
async function submit() {
  if (password.value.length < MIN_PASSWORD_LENGTH) return
  error.value = ''
  busy.value = true
  try {
    await confirmPasswordReset(session.apiBase, props.token, secretKey.value, password.value)
    setUnlocked(await login(session.apiBase, email.value, password.value, secretKey.value))
    emit('done')
  } catch {
    error.value = t('reset.failed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="auth-screen">
    <div class="auth-card">
      <p class="auth-wordmark">veyq</p>
      <h1 class="auth-title">{{ t('reset.title') }}</h1>
      <p v-if="email" class="auth-sub">{{ email }}</p>
      <template v-if="ready">
        <p class="auth-sub">{{ t('reset.confirmHint') }}</p>
        <form class="veyq-form" @submit.prevent="submit">
          <label>
            {{ t('common.secretKey') }}
            <input v-model="secretKey" type="password" autocomplete="off" required autofocus />
            <span class="field-hint">{{ t('login.secretHint') }}</span>
          </label>
          <label>
            {{ t('reset.newPassword') }}
            <input v-model="password" type="password" autocomplete="new-password" required />
            <p v-if="breachCount > 0" class="auth-msg auth-msg-warn">{{ t('password.pwnedWarning', { count: breachCount }) }}</p>
            <p v-if="passwordHintKey(password)" class="field-hint">{{ t(passwordHintKey(password)!) }}</p>
          </label>
          <button class="btn btn-primary" type="submit" :disabled="busy || password.length < MIN_PASSWORD_LENGTH">{{ busy ? t('login.submitting') : t('reset.setPassword') }}</button>
        </form>
        <label class="kit-import">
          <span>{{ t('kit.import') }}</span>
          <input type="file" accept=".pdf,.veyqkit,application/pdf,text/plain" @change="importKit" />
        </label>
      </template>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
    </div>
  </div>
</template>
