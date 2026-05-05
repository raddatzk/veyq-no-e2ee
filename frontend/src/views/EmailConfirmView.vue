<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
    error.value = 'Bestaetigungslink ist ungueltig.'
    loading.value = false
    return
  }
  try {
    const user = await confirmMyEmailChange(token.value)
    setAuthenticated(user, await tenants())
    confirmedEmail.value = user.email
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Email konnte nicht bestaetigt werden.')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>Email bestaetigen</template>
    <template #content>
      <div v-if="loading" class="explorer-loading">
        <ProgressSpinner class="small-spinner" />
        <span>Email wird bestaetigt...</span>
      </div>
      <Message v-else-if="error" severity="error" :closable="false" class="form-message">
        {{ error }}
      </Message>
      <Message v-else severity="success" :closable="false" class="form-message">
        Email wurde bestaetigt: {{ confirmedEmail }}
      </Message>
      <div class="button-row">
        <Button
          type="button"
          label="Zu den Einstellungen"
          icon="pi pi-user"
          :disabled="loading || !authState.isAuthenticated"
          @click="router.push('/~me')"
        />
      </div>
    </template>
  </Card>
</template>
