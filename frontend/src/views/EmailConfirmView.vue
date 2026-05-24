<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { tenants } from '../api/tenantClient'
import { confirmMyEmailChange } from '../features/settings/api'
import { useAuthState } from '../shared/state/useAuthState'
import { getErrorMessage } from '../shared/utils/errors'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { authState, setAuthenticated } = useAuthState()

const loading = ref(true)
const error = ref('')
const confirmedEmail = ref('')
const token = computed(() => route.hash.replace(/^#/, ''))

onMounted(confirmEmail)

async function confirmEmail() {
  loading.value = true
  error.value = ''
  if (!token.value) {
    error.value = t('settings.emailConfirmInvalid')
    loading.value = false
    return
  }
  try {
    const user = await confirmMyEmailChange(token.value)
    setAuthenticated(user, await tenants())
    confirmedEmail.value = user.email
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, t('settings.emailConfirmFailed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('settings.emailConfirmTitle') }}</template>
    <template #content>
      <div v-if="loading" class="memory-loading">
        <ProgressSpinner class="small-spinner" />
        <span>{{ t('settings.emailConfirmLoading') }}</span>
      </div>
      <Message v-else-if="error" severity="error" :closable="false" class="form-message">
        {{ error }}
      </Message>
      <Message v-else severity="success" :closable="false" class="form-message">
        {{ t('settings.emailConfirmSuccess', { email: confirmedEmail }) }}
      </Message>
      <div class="button-row">
        <Button
          type="button"
          :label="t('settings.emailConfirmButton')"
          icon="pi pi-user"
          :disabled="loading || !authState.isAuthenticated"
          @click="router.push('/~me')"
        />
      </div>
    </template>
  </Card>
</template>
