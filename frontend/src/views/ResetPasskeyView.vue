<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import { resetPasskey } from '../api/authClient'
import { tenants } from '../api/tenantClient'
import { useAuthState } from '../shared/state/useAuthState'
import { getErrorMessage } from '../shared/utils/errors'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { setAuthenticated } = useAuthState()
const error = ref('')
const loading = ref(false)
const userId = computed(() => Number(route.params.userId))
const token = computed(() => route.hash.replace(/^#/, ''))

async function submit() {
  error.value = ''
  if (!Number.isInteger(userId.value) || !token.value) {
    error.value = t('auth.invalidResetLink')
    return
  }
  loading.value = true
  try {
    const user = await resetPasskey(userId.value, token.value)
    setAuthenticated(
      {
        userId: user.userId,
        email: user.email,
        displayName: user.displayName,
        permalink: user.permalink,
        serverRole: user.serverRole,
        pendingEmail: user.pendingEmail,
        passkeyCount: user.passkeyCount,
        hasPassword: user.hasPassword
      },
      await tenants()
    )
    await router.push('/')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('auth.passkeyResetFailed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('auth.passkeyResetTitle') }}</template>
    <template #content>
      <div class="veyq-form">
        <Message severity="info" :closable="false">
          {{ t('auth.passkeyResetPrompt') }}
        </Message>
        <Button type="button" :label="t('auth.passkeyResetCreate')" icon="pi pi-key" :loading="loading" @click="submit" />
      </div>
      <Message v-if="error" severity="error" :closable="false" class="form-message">
        {{ error }}
      </Message>
    </template>
  </Card>
</template>
