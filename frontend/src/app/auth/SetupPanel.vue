<script setup lang="ts">
import { ref } from 'vue'
import { registerServerOwner } from '../../features/admin/setupServer/setupServer'
import { t } from '../../i18n/i18n'
import { session } from '../session'
import { downloadKitPdf } from '../../features/account/emergencyKit/emergencyKit'
import { usePwnedWarning } from '../../features/account/passwordWarning'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../../shared/passwordPolicy'

const emit = defineEmits<{ done: [email: string, secretKey: string] }>()
const displayName = ref('')
const email = ref('')
const password = ref('')
const { breachCount } = usePwnedWarning(password)
const busy = ref(false)
const error = ref('')
const kit = ref<string | null>(null)
let kitEmail = ''

async function submit() {
  if (password.value.length < MIN_PASSWORD_LENGTH) return
  error.value = ''
  busy.value = true
  try {
    const { emergencyKit } = await registerServerOwner(session.apiBase, email.value, displayName.value, password.value)
    kitEmail = email.value
    kit.value = emergencyKit.secretKey
  } catch {
    error.value = t('setup.failed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="auth-screen">
    <div class="auth-card">
      <p class="auth-wordmark">veyq</p>

      <template v-if="!kit">
        <h1 class="auth-title">{{ t('setup.title') }}</h1>
        <p class="auth-sub">{{ t('setup.sub') }}</p>
        <form class="veyq-form" @submit.prevent="submit">
          <label>
            {{ t('setup.displayName') }}
            <input v-model="displayName" type="text" required autofocus />
          </label>
          <label>
            {{ t('common.email') }}
            <input v-model="email" type="email" autocomplete="username" required />
          </label>
          <label>
            {{ t('common.password') }}
            <input v-model="password" type="password" autocomplete="new-password" required />
          </label>
          <p v-if="breachCount > 0" class="auth-msg auth-msg-warn">{{ t('password.pwnedWarning', { count: breachCount }) }}</p>
          <p v-if="passwordHintKey(password)" class="field-hint">{{ t(passwordHintKey(password)!) }}</p>
          <button class="btn btn-primary" type="submit" :disabled="busy || password.length < MIN_PASSWORD_LENGTH">
            {{ busy ? t('setup.submitting') : t('setup.submit') }}
          </button>
        </form>
      </template>

      <template v-else>
        <h1 class="auth-title">{{ t('setup.kitTitle') }}</h1>
        <p class="auth-sub">{{ t('setup.kitSub') }}</p>
        <div class="kit-secret">{{ kit }}</div>
        <button class="btn btn-ghost" type="button" style="margin-top: 1rem; width: 100%" @click="downloadKitPdf({ instance: session.apiBase, email: kitEmail, secretKey: kit! }, displayName)">
          {{ t('kit.download') }}
        </button>
        <button class="btn btn-primary" type="button" style="margin-top: 0.6rem; width: 100%" @click="emit('done', kitEmail, kit!)">
          {{ t('setup.kitProceed') }}
        </button>
      </template>

      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
    </div>
  </div>
</template>
