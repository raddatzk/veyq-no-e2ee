<script setup lang="ts">
// Accept a member invite. The invite id + bootstrap secret + one-time temp private key ride the URL FRAGMENT
// (/#invite=…&s=…&k=… or ek/ps for a passphrase-protected link) and never reach the server. A signed-in user
// joins (re-wraps the scope grant to their identity); a brand-new invitee registers (account + grant) and gets
// an Emergency Kit to download.
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { getInviteMetadata, type InviteMetadata } from '../features/account/registerViaInvitation/registerViaInvitation'
import { acceptMemberInvite, registerViaMemberInvite } from '../features/admin/inviteMembersRoles/inviteMembers'
import { unprotectTempKey } from '../features/spaces/invite/groups'
import { isProtectedInvite, parseInviteHash, type ParsedInvite } from '../features/spaces/invite/inviteLink'
import { login } from '../features/account/login/login'
import { downloadKitPdf } from '../features/account/emergencyKit/emergencyKit'
import { usePwnedWarning } from '../features/account/passwordWarning'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../shared/passwordPolicy'
import { isUnlocked, session, setUnlocked } from '../app/session'

const router = useRouter()
const { t } = useI18n()
const invite = ref<ParsedInvite | null>(parseInviteHash(window.location.hash))
const needsPassphrase = invite.value ? isProtectedInvite(invite.value) : false
const passphrase = ref('')

const meta = ref<InviteMetadata | null>(null)
const error = ref('')
const busy = ref(false)
const email = ref('')
const displayName = ref('')
const password = ref('')
const { breachCount } = usePwnedWarning(password)
const kit = ref<string | null>(null)

/** Resolve the temp private key for accept/register; throws on a wrong passphrase. */
function tempKey(): string {
  const inv = invite.value!
  return isProtectedInvite(inv) ? unprotectTempKey(inv, passphrase.value) : inv.tempPrivateKey
}

onMounted(async () => {
  if (!invite.value) {
    error.value = t('accept.failed')
    return
  }
  try {
    meta.value = await getInviteMetadata(session.apiBase, invite.value.inviteId)
  } catch {
    error.value = t('accept.failed')
  }
})

const roleLabel = (role?: string) => (role === 'Owner' ? t('invite.owner') : t('invite.member'))

async function join() {
  if (!isUnlocked() || !invite.value) return
  busy.value = true
  error.value = ''
  try {
    await acceptMemberInvite(session.apiBase, session.unlocked!, invite.value.inviteId, invite.value.bootstrapSecret, tempKey())
    await router.push('/')
  } catch {
    error.value = needsPassphrase ? t('accept.wrongPassphrase') : t('accept.failed')
  } finally {
    busy.value = false
  }
}

async function register() {
  if (password.value.length < MIN_PASSWORD_LENGTH || !invite.value) return
  busy.value = true
  error.value = ''
  try {
    const res = await registerViaMemberInvite(
      session.apiBase,
      invite.value.inviteId,
      invite.value.bootstrapSecret,
      tempKey(),
      email.value,
      displayName.value,
      password.value,
    )
    kit.value = res.emergencyKit.secretKey
  } catch {
    error.value = needsPassphrase ? t('accept.wrongPassphrase') : t('accept.registerFailed')
  } finally {
    busy.value = false
  }
}

async function proceed() {
  if (!kit.value) return
  busy.value = true
  try {
    setUnlocked(await login(session.apiBase, email.value, password.value, kit.value))
    await router.push('/')
  } catch {
    error.value = t('accept.failed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('accept.title') }}</template>
    <template #content>
      <template v-if="meta">
        <p class="veyq-form-copy">{{ t('accept.joinAs', { name: meta.tenant.displayName, role: roleLabel(meta.role) }) }}</p>

        <label v-if="needsPassphrase">
          {{ t('accept.passphrase') }}
          <Password v-model="passphrase" :input-props="{ autocomplete: 'off' }" :placeholder="t('accept.passphraseHint')" :feedback="false" toggle-mask fluid />
        </label>

        <!-- already signed in → join -->
        <template v-if="isUnlocked()">
          <Button type="button" :label="busy ? t('accept.joining') : t('accept.join')" icon="pi pi-sign-in" :loading="busy" @click="join" />
        </template>

        <!-- just registered → show kit, then proceed -->
        <template v-else-if="kit">
          <h3 class="auth-title" style="font-size: 1.1rem">{{ t('setup.kitTitle') }}</h3>
          <p class="veyq-form-copy">{{ t('setup.kitSub') }}</p>
          <pre class="kit-secret">{{ kit }}</pre>
          <Button type="button" :label="t('kit.download')" icon="pi pi-download" severity="secondary" @click="downloadKitPdf({ instance: session.apiBase, email, secretKey: kit! }, displayName)" />
          <Button type="button" :label="t('setup.kitProceed')" icon="pi pi-arrow-right" :loading="busy" @click="proceed" />
        </template>

        <!-- not signed in → register -->
        <template v-else>
          <form class="veyq-form" @submit.prevent="register">
            <label>{{ t('setup.displayName') }}<InputText v-model="displayName" type="text" required autofocus /></label>
            <label>{{ t('common.email') }}<InputText v-model="email" type="email" autocomplete="username" required /></label>
            <label>{{ t('common.password') }}<Password v-model="password" :input-props="{ autocomplete: 'new-password' }" required :feedback="false" toggle-mask fluid /></label>
            <Message v-if="breachCount > 0" severity="warn" :closable="false">{{ t('password.pwnedWarning', { count: breachCount }) }}</Message>
            <small v-if="passwordHintKey(password)" class="field-hint">{{ t(passwordHintKey(password)!) }}</small>
            <Button type="submit" :label="busy ? t('accept.joining') : t('accept.register')" icon="pi pi-user-plus" :loading="busy" :disabled="password.length < MIN_PASSWORD_LENGTH" />
          </form>
          <Button type="button" :label="t('accept.haveAccount')" severity="secondary" text @click="router.push('/login')" />
        </template>
      </template>

      <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
      <p v-else-if="!meta" class="veyq-form-copy">{{ t('common.loading') }}</p>
    </template>
  </Card>
</template>

<style scoped>
.kit-secret { white-space: pre-wrap; word-break: break-all; font-family: var(--font-mono, monospace); background: var(--p-content-border-color, rgba(127,127,127,0.15)); padding: 0.75rem; border-radius: 8px; margin: 0 0 0.75rem; }
</style>
