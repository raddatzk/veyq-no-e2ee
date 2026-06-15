<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { session, setUnlocked } from '../session'
import { t } from '../../i18n/i18n'
import { getInviteMetadata, type InviteMetadata } from '../../features/account/registerViaInvitation/registerViaInvitation'
import { acceptMemberInvite, registerViaMemberInvite } from '../../features/admin/inviteMembersRoles/inviteMembers'
import { login } from '../../features/account/login/login'
import type { InviteParts } from '../../features/spaces/invite/inviteLink'
import LoginPanel from './LoginPanel.vue'

const props = defineProps<{ invite: InviteParts }>()
const emit = defineEmits<{ done: [tenantId: string]; dismiss: [] }>()

const meta = ref<InviteMetadata | null>(null)
const error = ref('')
const busy = ref(false)
// not-signed-in flow: choose to create a new account or sign in to an existing one
const mode = ref<'register' | 'signin'>('register')
const email = ref('')
const displayName = ref('')
const password = ref('')
const kit = ref<string | null>(null) // the Emergency Kit secret to show after registering

onMounted(async () => {
  try {
    meta.value = await getInviteMetadata(session.apiBase, props.invite.inviteId)
  } catch {
    error.value = t('accept.failed')
  }
})

const roleLabel = (role?: string) => (role === 'Owner' ? t('invite.owner') : t('invite.member'))

/** Existing account that's already signed in → add the grant + membership. */
async function join() {
  if (!session.unlocked || !meta.value) return
  busy.value = true
  error.value = ''
  try {
    await acceptMemberInvite(session.apiBase, session.unlocked, props.invite.inviteId, props.invite.bootstrapSecret, props.invite.tempPrivateKey)
    emit('done', meta.value.tenant.id)
  } catch {
    error.value = t('accept.failed')
  } finally {
    busy.value = false
  }
}

/** Brand-new invitee → create the account (this also joins the group), then show the Emergency Kit. */
async function register() {
  busy.value = true
  error.value = ''
  try {
    const res = await registerViaMemberInvite(session.apiBase, props.invite.inviteId, props.invite.bootstrapSecret, props.invite.tempPrivateKey, email.value, displayName.value, password.value)
    kit.value = res.emergencyKit.secretKey
  } catch {
    error.value = t('accept.registerFailed')
  } finally {
    busy.value = false
  }
}

/** After saving the Emergency Kit, sign in with it to unlock and land in the group. */
async function proceed() {
  if (!kit.value || !meta.value) return
  busy.value = true
  try {
    setUnlocked(await login(session.apiBase, email.value, password.value, kit.value))
    emit('done', meta.value.tenant.id)
  } catch {
    error.value = t('accept.failed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="auth-screen">
    <div class="auth-card">
      <p class="auth-wordmark">veyq</p>
      <h1 class="auth-title">{{ t('accept.title') }}</h1>
      <template v-if="meta">
        <p class="auth-sub">{{ t('accept.joinAs', { name: meta.tenant.displayName, role: roleLabel(meta.role) }) }}</p>
        <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>

        <!-- already signed in -->
        <template v-if="session.unlocked">
          <form class="veyq-form" @submit.prevent="join">
            <button class="btn btn-primary" type="submit" :disabled="busy">{{ busy ? t('accept.joining') : t('accept.join') }}</button>
            <button class="btn btn-ghost" type="button" @click="emit('dismiss')">{{ t('accept.dismiss') }}</button>
          </form>
        </template>

        <!-- just registered → show the Emergency Kit, then proceed -->
        <template v-else-if="kit">
          <h2 class="auth-title" style="font-size: 1.1rem">{{ t('setup.kitTitle') }}</h2>
          <p class="auth-sub">{{ t('setup.kitSub') }}</p>
          <div class="kit-secret">{{ kit }}</div>
          <button class="btn btn-primary" type="button" style="margin-top: 1rem; width: 100%" :disabled="busy" @click="proceed">{{ t('setup.kitProceed') }}</button>
        </template>

        <!-- not signed in: create an account, or sign in to an existing one -->
        <template v-else>
          <div class="seg accept-modes">
            <button type="button" :class="{ active: mode === 'register' }" @click="mode = 'register'">{{ t('accept.createAccount') }}</button>
            <button type="button" :class="{ active: mode === 'signin' }" @click="mode = 'signin'">{{ t('accept.haveAccount') }}</button>
          </div>
          <form v-if="mode === 'register'" class="veyq-form" @submit.prevent="register">
            <label>{{ t('setup.displayName') }}<input v-model="displayName" type="text" required autofocus /></label>
            <label>{{ t('common.email') }}<input v-model="email" type="email" autocomplete="username" required /></label>
            <label>{{ t('common.password') }}<input v-model="password" type="password" autocomplete="new-password" required /></label>
            <button class="btn btn-primary" type="submit" :disabled="busy">{{ busy ? t('accept.joining') : t('accept.register') }}</button>
          </form>
          <LoginPanel v-else embedded />
        </template>
      </template>
      <p v-else-if="error" class="auth-msg auth-msg-error">{{ error }}</p>
      <p v-else class="auth-sub">{{ t('app.loading') }}</p>
    </div>
  </div>
</template>
