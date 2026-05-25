<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { acceptInvitation, acceptInvitationWithPasskey } from '../api/invitationClient'
import { bootstrap } from '../api/entrypointClient'
import { useAuthState } from '../shared/state/useAuthState'
import { getErrorMessage } from '../shared/utils/errors'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { authState, setAuthenticated } = useAuthState()
const displayName = ref('')
const passkeyDisplayName = ref(t('profile.defaultPasskeyName'))
const password = ref('')
const error = ref('')
const loading = ref(false)
const authMethod = ref<'passkey' | 'password'>('passkey')
const userId = computed(() => Number(route.params.userId))
const token = computed(() => route.hash.replace(/^#/, ''))
const requiresPassword = computed(() => !authState.isAuthenticated)

async function submit() {
  error.value = ''
  if (!Number.isInteger(userId.value) || !token.value) {
    error.value = t('auth.invalidInviteLink')
    return
  }
  const inviteDisplayName = displayName.value.trim()
  if (requiresPassword.value && !inviteDisplayName) {
    error.value = t('auth.displayNameRequired')
    return
  }
  const invitePasskeyDisplayName = passkeyDisplayName.value.trim()
  if (requiresPassword.value && authMethod.value === 'passkey' && !invitePasskeyDisplayName) {
    error.value = t('auth.passkeyDisplayNameRequired')
    return
  }
  loading.value = true
  try {
    if (!requiresPassword.value) {
      await acceptInvitation(userId.value, token.value)
    } else if (authMethod.value === 'passkey') {
      await acceptInvitationWithPasskey(userId.value, token.value, inviteDisplayName, invitePasskeyDisplayName)
    } else {
      await acceptInvitation(userId.value, token.value, password.value, inviteDisplayName)
    }
    const entrypoint = await bootstrap()
    if (entrypoint.user) {
      setAuthenticated(entrypoint.user, entrypoint.tenants)
      await router.push(entrypoint.tenants[0] ? `/${entrypoint.tenants[0].name}` : '/')
    }
  } catch (caught) {
    if (!authState.isAuthenticated && getErrorMessage(caught, '').includes(t('auth.loggedInRequiredMarker'))) {
      await router.push({ name: 'login', query: { redirect: route.fullPath } })
      return
    }
    error.value = getErrorMessage(caught, t('auth.inviteAcceptFailed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('auth.inviteTitle') }}</template>
    <template #content>
      <form class="form" @submit.prevent="submit">
        <div v-if="requiresPassword" class="auth-method-switch" role="tablist" :aria-label="t('auth.authMethod')">
          <button
            type="button"
            :class="{ active: authMethod === 'passkey' }"
            role="tab"
            :aria-selected="authMethod === 'passkey'"
            @click="authMethod = 'passkey'"
          >
            {{ t('auth.passkey') }}
          </button>
          <button
            type="button"
            :class="{ active: authMethod === 'password' }"
            role="tab"
            :aria-selected="authMethod === 'password'"
            @click="authMethod = 'password'"
          >
            {{ t('common.password') }}
          </button>
        </div>
        <label v-if="requiresPassword">
          {{ t('auth.displayName') }}
          <InputText v-model="displayName" autocomplete="name" required />
        </label>
        <label v-if="requiresPassword && authMethod === 'passkey'">
          {{ t('auth.passkeyDisplayName') }}
          <InputText v-model="passkeyDisplayName" autocomplete="off" required />
        </label>
        <label v-if="requiresPassword && authMethod === 'password'">
          {{ t('common.password') }}
          <Password
            v-model="password"
            autocomplete="new-password"
            required
            minlength="8"
            toggle-mask
            fluid
          />
        </label>
        <Button
          type="submit"
          :label="
            requiresPassword
              ? authMethod === 'passkey'
                ? t('auth.accountCreateWithPasskey')
                : t('auth.setPassword')
              : t('auth.acceptInvite')
          "
          :icon="requiresPassword && authMethod === 'passkey' ? 'pi pi-key' : 'pi pi-sign-in'"
          :loading="loading"
        />
      </form>
      <Message v-if="error" severity="error" :closable="false" class="form-message">
        {{ error }}
      </Message>
    </template>
  </Card>
</template>
