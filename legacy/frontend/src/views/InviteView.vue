<script setup lang="ts">
// Accept an owner or member invite. The invite id + bootstrap secret and, for member invites, the one-time
// temp private key ride the URL FRAGMENT and never reach the server. Owner invites claim an empty tenant;
// member invites re-wrap an existing scope grant.
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { getInviteMetadata, registerViaInvitation, type InviteMetadata } from '../features/account/registerViaInvitation/registerViaInvitation'
import { acceptMemberInvite, registerViaMemberInvite } from '../features/admin/inviteMembersRoles/inviteMembers'
import { claimTenantOwnership } from '../features/admin/createTenantInviteOwner/tenant'
import { unprotectTempKey } from '../features/spaces/invite/groups'
import { isMemberInvite, isProtectedInvite, parseInviteHash, type ParsedInvite } from '../features/spaces/invite/inviteLink'
import { login, logout } from '../features/account/login/login'
import { downloadKitPdf } from '../features/account/emergencyKit/emergencyKit'
import { usePwnedWarning } from '../features/account/passwordWarning'
import { clearRememberedLogin } from '../features/account/rememberedLogin/rememberedLogin'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../shared/passwordPolicy'
import { isUnlocked, session, setUnlocked } from '../app/session'
import { reloadSpaces } from '../app/spaces'

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
const kitDownloadTriggered = ref(false)
const kitBackupConfirmed = ref(false)
const canProceed = computed(() => kitDownloadTriggered.value && kitBackupConfirmed.value)
const isOwnerInvite = computed(() => meta.value?.kind === 'OWNER' || meta.value?.role === 'Owner')
const targetsExistingAccount = computed(() => meta.value?.existingAccount === true)
const currentInviteRedirect = computed(() => `/invite${window.location.hash || ''}`)
const currentUserName = computed(() => session.unlocked?.user.displayName || session.unlocked?.user.email || '')
const signedInInviteCopy = computed(() => {
  if (!meta.value) return ''
  const key = isOwnerInvite.value ? 'accept.signedInOwner' : 'accept.signedInMember'
  return t(key, { user: currentUserName.value, tenant: meta.value.tenant.displayName })
})
const existingAccountInviteCopy = computed(() => {
  if (!meta.value) return ''
  const key = isOwnerInvite.value ? 'accept.existingOwner' : 'accept.existingMember'
  return t(key, { tenant: meta.value.tenant.displayName })
})

/** Resolve the temp private key for accept/register; throws on a wrong passphrase. */
function tempKey(): string {
  const inv = invite.value!
  if (isProtectedInvite(inv)) return unprotectTempKey(inv, passphrase.value)
  if (isMemberInvite(inv)) return inv.tempPrivateKey
  throw new Error('Invite is missing member key')
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
    if (isOwnerInvite.value) {
      await claimTenantOwnership(
        session.apiBase,
        session.unlocked!.sessionToken,
        invite.value.inviteId,
        invite.value.bootstrapSecret,
        session.unlocked!.identityPublicKey.kemPublicKey,
      )
      await reloadSpaces()
    } else {
      await acceptMemberInvite(session.apiBase, session.unlocked!, invite.value.inviteId, invite.value.bootstrapSecret, tempKey())
    }
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
    const res = isOwnerInvite.value
      ? await registerViaInvitation(
        session.apiBase,
        invite.value.inviteId,
        invite.value.bootstrapSecret,
        email.value,
        displayName.value,
        password.value,
      )
      : await registerViaMemberInvite(
        session.apiBase,
        invite.value.inviteId,
        invite.value.bootstrapSecret,
        tempKey(),
        email.value,
        displayName.value,
        password.value,
      )
    kit.value = res.emergencyKit.secretKey
    kitDownloadTriggered.value = false
    kitBackupConfirmed.value = false
  } catch {
    error.value = needsPassphrase ? t('accept.wrongPassphrase') : t('accept.registerFailed')
  } finally {
    busy.value = false
  }
}

async function proceed() {
  if (!kit.value || !canProceed.value) return
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

async function downloadKit() {
  if (!kit.value) return
  error.value = ''
  try {
    await downloadKitPdf(
      { instance: session.apiBase, email: email.value, secretKey: kit.value },
      displayName.value
    )
    kitDownloadTriggered.value = true
  } catch {
    error.value = t('kit.downloadFailed')
  }
}

function signInForInvite() {
  void router.push({ name: 'login', query: { redirect: currentInviteRedirect.value } })
}

async function signOutForInvite() {
  busy.value = true
  error.value = ''
  try {
    if (session.unlocked) await logout(session.apiBase, session.unlocked.sessionToken)
  } catch {
    /* best-effort: local session is cleared so another account can sign in */
  } finally {
    clearRememberedLogin()
    setUnlocked(null)
    busy.value = false
    await router.push({ name: 'login', query: { redirect: currentInviteRedirect.value } })
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('accept.title') }}</template>
    <template #content>
      <template v-if="meta">
        <p v-if="!targetsExistingAccount" class="veyq-form-copy">{{ t('accept.joinAs', { name: meta.tenant.displayName, role: roleLabel(meta.role) }) }}</p>

        <label v-if="needsPassphrase">
          {{ t('accept.passphrase') }}
          <Password v-model="passphrase" :input-props="{ autocomplete: 'off' }" :placeholder="t('accept.passphraseHint')" :feedback="false" toggle-mask fluid />
        </label>

        <!-- already signed in → join -->
        <template v-if="isUnlocked()">
          <p class="veyq-form-copy">{{ signedInInviteCopy }}</p>
          <div class="invite-action-row">
            <Button type="button" :label="busy ? t('accept.joining') : t('accept.join')" icon="pi pi-check" :loading="busy" @click="join" />
            <Button type="button" :label="t('accept.useOtherAccount')" icon="pi pi-sign-out" severity="secondary" text :disabled="busy" @click="signOutForInvite" />
          </div>
        </template>

        <!-- just registered → show kit, then proceed -->
        <template v-else-if="kit">
          <h3 class="auth-title" style="font-size: 1.1rem">{{ t('setup.kitTitle') }}</h3>
          <p class="veyq-form-copy">{{ t('setup.kitSub') }}</p>
          <Button
            type="button"
            :label="t('kit.download')"
            icon="pi pi-download"
            severity="secondary"
            @click="downloadKit"
          />
          <p v-if="kitDownloadTriggered" class="kit-status">
            <span class="pi pi-check" aria-hidden="true" />
            {{ t('kit.downloaded') }}
          </p>
          <label class="checkbox-row kit-confirm">
            <input v-model="kitBackupConfirmed" type="checkbox" :disabled="!kitDownloadTriggered" />
            <span>{{ t('kit.backupDownloadConfirm') }}</span>
          </label>
          <Button
            type="button"
            :label="t('setup.kitProceed')"
            icon="pi pi-arrow-right"
            :loading="busy"
            :disabled="!canProceed"
            @click="proceed"
          />
        </template>

        <!-- existing target account on this origin → sign in, then return to accept -->
        <template v-else-if="targetsExistingAccount">
          <p class="veyq-form-copy">{{ existingAccountInviteCopy }}</p>
          <div class="invite-action-row">
            <Button type="button" :label="t('accept.signIn')" icon="pi pi-sign-in" @click="signInForInvite" />
            <Button type="button" :label="t('accept.useOtherAccount')" icon="pi pi-sign-out" severity="secondary" text @click="signOutForInvite" />
          </div>
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
          <Button type="button" :label="t('accept.haveAccount')" severity="secondary" text @click="signInForInvite" />
        </template>
      </template>

      <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
      <p v-else-if="!meta" class="veyq-form-copy">{{ t('common.loading') }}</p>
    </template>
  </Card>
</template>

<style scoped>
.kit-status {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  margin: 0.75rem 0 0;
  color: var(--app-success, var(--p-green-400, #22c55e));
}
.kit-confirm {
  margin: 0.75rem 0;
}
.invite-action-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: center;
}
</style>
