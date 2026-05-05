<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { resetPassword } from '../api/authClient'
import { getErrorMessage } from '../shared/utils/errors'

const route = useRoute()
const router = useRouter()
const password = ref('')
const error = ref('')
const message = ref('')
const loading = ref(false)
const userId = computed(() => Number(route.params.userId))
const token = computed(() => String(route.params.token || ''))

async function submit() {
  error.value = ''
  message.value = ''
  if (!Number.isInteger(userId.value)) {
    error.value = 'Reset-Link ist ungueltig.'
    return
  }
  loading.value = true
  try {
    await resetPassword(userId.value, token.value, password.value)
    password.value = ''
    message.value = 'Passwort wurde zurueckgesetzt.'
  } catch (caught) {
    error.value = getErrorMessage(caught, 'Passwort konnte nicht zurueckgesetzt werden.')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>Passwort zuruecksetzen</template>
    <template #content>
      <form class="form" @submit.prevent="submit">
        <label>
          Neues Passwort
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
      <Message v-if="message" severity="success" :closable="false" class="form-message">
        {{ message }}
      </Message>
      <Message v-if="error" severity="error" :closable="false" class="form-message">
        {{ error }}
      </Message>
      <div v-if="message" class="button-row">
        <Button type="button" label="Zum Login" icon="pi pi-sign-in" @click="router.push('/login')" />
      </div>
    </template>
  </Card>
</template>
