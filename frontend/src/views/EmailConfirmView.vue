<script setup lang="ts">
// Email-change confirmation landing. The link in the email (#confirm-email=<token>) is token-only, so this
// works whether or not the visitor is logged in. On success, if a session is unlocked in THIS tab, refresh
// its cached user so the new email shows immediately.
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { confirmEmailChange, parseConfirmEmailHash } from '../features/account/emailChange/emailChange'
import { session, isUnlocked } from '../app/session'
import { t } from '../i18n/i18n'

const router = useRouter()
const token = parseConfirmEmailHash(window.location.hash)
const loading = ref(true)
const error = ref('')
const confirmedEmail = ref('')

onMounted(confirm)

async function confirm() {
  if (!token) {
    error.value = t('emailChange.confirmInvalid')
    loading.value = false
    return
  }
  try {
    const user = await confirmEmailChange(session.apiBase, token)
    confirmedEmail.value = user.email
    if (session.unlocked) session.unlocked.user = user // refresh the cached user in this tab
  } catch {
    error.value = t('emailChange.confirmFailed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('emailChange.confirmTitle') }}</template>
    <template #content>
      <div v-if="loading" class="veyq-loading">
        <ProgressSpinner class="small-spinner" />
        <span>{{ t('emailChange.confirmLoading') }}</span>
      </div>
      <Message v-else-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
      <Message v-else severity="success" :closable="false" class="form-message">
        {{ t('emailChange.confirmSuccess', { email: confirmedEmail }) }}
      </Message>
      <div class="veyq-button-row">
        <Button
          v-if="isUnlocked()"
          type="button"
          :label="t('emailChange.confirmContinue')"
          icon="pi pi-arrow-right"
          :disabled="loading"
          @click="router.push('/')"
        />
        <Button
          v-else
          type="button"
          :label="t('auth.login')"
          icon="pi pi-sign-in"
          :disabled="loading"
          @click="router.push({ name: 'login' })"
        />
      </div>
    </template>
  </Card>
</template>
