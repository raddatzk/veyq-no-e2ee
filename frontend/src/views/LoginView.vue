<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { login } from '../api/authClient'
import { tenants } from '../api/tenantClient'
import { useAuthState } from '../shared/state/useAuthState'
import { getErrorMessage } from '../shared/utils/errors'

const router = useRouter()
const { authState, setAuthenticated } = useAuthState()
const email = ref('')
const password = ref('')
const error = ref('')

async function submit() {
  error.value = ''
  try {
    const user = await login(email.value, password.value)
    setAuthenticated(
      {
        userId: user.userId,
        email: user.email,
        displayName: user.displayName,
        serverRole: user.serverRole
      },
      await tenants()
    )
    await router.push('/')
  } catch (unknownError) {
    error.value = getErrorMessage(unknownError, 'Login fehlgeschlagen')
  }
}

onMounted(async () => {
  if (authState.isAuthenticated) {
    await router.push('/')
  }
})
</script>

<template>
  <Card class="login-card">
    <template #title>Login</template>
    <template #content>
      <form class="form" @submit.prevent="submit">
        <label>
          E-Mail
          <InputText v-model="email" type="email" autocomplete="email" required />
        </label>
        <label>
          Passwort
          <Password
            v-model="password"
            autocomplete="current-password"
            required
            :feedback="false"
            toggle-mask
            fluid
          />
        </label>
        <Button type="submit" label="Einloggen" icon="pi pi-sign-in" />
      </form>
      <Message v-if="error" severity="error" :closable="false" class="form-message">{{
        error
      }}</Message>
    </template>
  </Card>
</template>
