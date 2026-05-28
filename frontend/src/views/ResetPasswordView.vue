<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { resetPassword } from '../api/authClient'
import { getErrorMessage } from '../shared/utils/errors'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const password = ref('')
const error = ref('')
const message = ref('')
const loading = ref(false)
const userId = computed(() => Number(route.params.userId))
const token = computed(() => route.hash.replace(/^#/, ''))

async function submit() {
  error.value = ''
  message.value = ''
  if (!Number.isInteger(userId.value) || !token.value) {
    error.value = t('auth.invalidResetLink')
    return
  }
  loading.value = true
  try {
    await resetPassword(userId.value, token.value, password.value)
    password.value = ''
    message.value = t('auth.passwordResetSuccess')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('auth.passwordResetFailed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('auth.passwordResetTitle') }}</template>
    <template #content>
      <form class="veyq-form" @submit.prevent="submit">
        <label>
          {{ t('auth.newPassword') }}
          <Password
            v-model="password"
            autocomplete="new-password"
            required
            minlength="8"
            toggle-mask
            fluid
          />
        </label>
        <Button type="submit" :label="t('auth.setPassword')" icon="pi pi-key" :loading="loading" />
      </form>
      <Message v-if="message" severity="success" :closable="false" class="form-message">
        {{ message }}
      </Message>
      <Message v-if="error" severity="error" :closable="false" class="form-message">
        {{ error }}
      </Message>
      <div v-if="message" class="veyq-button-row">
        <Button type="button" :label="t('auth.toLogin')" icon="pi pi-sign-in" @click="router.push('/login')" />
      </div>
    </template>
  </Card>
</template>
