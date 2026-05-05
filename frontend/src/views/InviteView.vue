<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { acceptInvitation } from '../api/invitationClient'
import { bootstrap } from '../api/entrypointClient'
import { useAuthState } from '../shared/state/useAuthState'
import { getErrorMessage } from '../shared/utils/errors'

const route = useRoute()
const router = useRouter()
const { setAuthenticated } = useAuthState()
const password = ref('')
const error = ref('')
const loading = ref(false)
const userId = computed(() => Number(route.params.userId))
const token = computed(() => route.hash.replace(/^#/, ''))

async function submit() {
  error.value = ''
  if (!Number.isInteger(userId.value) || !token.value) {
    error.value = 'Einladungslink ist ungueltig.'
    return
  }
  loading.value = true
  try {
    await acceptInvitation(userId.value, token.value, password.value)
    const entrypoint = await bootstrap()
    if (entrypoint.user) {
      setAuthenticated(entrypoint.user, entrypoint.tenants)
      await router.push(entrypoint.tenants[0] ? `/${entrypoint.tenants[0].name}` : '/')
    }
  } catch (caught) {
    error.value = getErrorMessage(caught, 'Einladung konnte nicht angenommen werden.')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>Einladung annehmen</template>
    <template #content>
      <form class="form" @submit.prevent="submit">
        <label>
          Passwort
          <Password
            v-model="password"
            autocomplete="new-password"
            required
            minlength="8"
            toggle-mask
            fluid
          />
        </label>
        <Button type="submit" label="Passwort setzen" icon="pi pi-key" :loading="loading" />
      </form>
      <Message v-if="error" severity="error" :closable="false" class="form-message">
        {{ error }}
      </Message>
    </template>
  </Card>
</template>
