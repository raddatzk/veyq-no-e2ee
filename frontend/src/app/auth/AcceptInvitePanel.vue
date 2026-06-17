<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { session, setUnlocked } from '../session'
import { t } from '../../i18n/i18n'
import { getInviteMetadata, type InviteMetadata } from '../../features/account/registerViaInvitation/registerViaInvitation'
import { acceptMemberInvite, registerViaMemberInvite } from '../../features/admin/inviteMembersRoles/inviteMembers'
import { unprotectTempKey } from '../../features/spaces/invite/groups'
import { login } from '../../features/account/login/login'
import { downloadKitPdf } from '../../features/account/emergencyKit/emergencyKit'
import { isProtectedInvite, type ParsedInvite } from '../../features/spaces/invite/inviteLink'
import { usePwnedWarning } from '../../features/account/passwordWarning'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../../shared/passwordPolicy'
import LoginPanel from './LoginPanel.vue'
import OpenInApp from './OpenInApp.vue'

const props = defineProps<{ invite: ParsedInvite }>()
const emit = defineEmits<{ done: [tenantId: string]; dismiss: [] }>()

// Passphrase-protected invites carry the temp key encrypted (`ek`/`ps`); the recipient supplies the
// passphrase (shared out of band) to resolve it. Pragmatic invites carry it in the clear.
const needsPassphrase = isProtectedInvite(props.invite)
const passphrase = ref('')

/** Resolve the temp private key for the accept/register calls; throws on a wrong passphrase. */
function tempKey(): string {
  if (!isProtectedInvite(props.invite)) return props.invite.tempPrivateKey
  return unprotectTempKey(props.invite, passphrase.value)
}

const meta = ref<InviteMetadata | null>(null)
const error = ref('')
const busy = ref(false)
// not-signed-in flow: choose to create a new account or sign in to an existing one
const mode = ref<'register' | 'signin'>('register')
const email = ref('')
const displayName = ref('')
const password = ref('')
const { breachCount } = usePwnedWarning(password)
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
    await acceptMemberInvite(session.apiBase, session.unlocked, props.invite.inviteId, props.invite.bootstrapSecret, tempKey())
    emit('done', meta.value.tenant.id)
  } catch {
    error.value = needsPassphrase ? t('accept.wrongPassphrase') : t('accept.failed')
  } finally {
    busy.value = false
  }
}

/** Brand-new invitee → create the account (this also joins the group), then show the Emergency Kit. */
async function register() {
  if (password.value.length < MIN_PASSWORD_LENGTH) return
  busy.value = true
  error.value = ''
  try {
    const res = await registerViaMemberInvite(session.apiBase, props.invite.inviteId, props.invite.bootstrapSecret, tempKey(), email.value, displayName.value, password.value)
    kit.value = res.emergencyKit.secretKey
  } catch {
    error.value = needsPassphrase ? t('accept.wrongPassphrase') : t('accept.registerFailed')
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

        <!-- passphrase-protected invite: the temp key is encrypted; the recipient enters the out-of-band passphrase -->
        <label v-if="needsPassphrase" class="veyq-form" style="display: block; margin-bottom: 0.4rem">
          {{ t('accept.passphrase') }}
          <input v-model="passphrase" type="password" autocomplete="off" :placeholder="t('accept.passphraseHint')" />
        </label>

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
          <button class="btn btn-ghost" type="button" style="margin-top: 1rem; width: 100%" @click="downloadKitPdf({ instance: session.apiBase, email, secretKey: kit! }, displayName)">{{ t('kit.download') }}</button>
          <button class="btn btn-primary" type="button" style="margin-top: 0.6rem; width: 100%" :disabled="busy" @click="proceed">{{ t('setup.kitProceed') }}</button>
        </template>

        <!-- not signed in: create an account, or sign in to an existing one -->
        <template v-else>
          <OpenInApp :invite="props.invite" />
          <div class="seg accept-modes">
            <button type="button" :class="{ active: mode === 'register' }" @click="mode = 'register'">{{ t('accept.createAccount') }}</button>
            <button type="button" :class="{ active: mode === 'signin' }" @click="mode = 'signin'">{{ t('accept.haveAccount') }}</button>
          </div>
          <form v-if="mode === 'register'" class="veyq-form" @submit.prevent="register">
            <label>{{ t('setup.displayName') }}<input v-model="displayName" type="text" required autofocus /></label>
            <label>{{ t('common.email') }}<input v-model="email" type="email" autocomplete="username" required /></label>
            <label>{{ t('common.password') }}<input v-model="password" type="password" autocomplete="new-password" required /></label>
            <p v-if="breachCount > 0" class="auth-msg auth-msg-warn">{{ t('password.pwnedWarning', { count: breachCount }) }}</p>
            <p v-if="passwordHintKey(password)" class="field-hint">{{ t(passwordHintKey(password)!) }}</p>
            <button class="btn btn-primary" type="submit" :disabled="busy || password.length < MIN_PASSWORD_LENGTH">{{ busy ? t('accept.joining') : t('accept.register') }}</button>
          </form>
          <LoginPanel v-else embedded />
        </template>
      </template>
      <p v-else-if="error" class="auth-msg auth-msg-error">{{ error }}</p>
      <p v-else class="auth-sub">{{ t('app.loading') }}</p>
    </div>
  </div>
</template>
