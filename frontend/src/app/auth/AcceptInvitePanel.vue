<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { getInviteMetadata, type InviteMetadata } from '../../features/account/registerViaInvitation/registerViaInvitation'
import { acceptMemberInvite } from '../../features/admin/inviteMembersRoles/inviteMembers'
import type { InviteParts } from '../../features/spaces/invite/inviteLink'
import LoginPanel from './LoginPanel.vue'

const props = defineProps<{ invite: InviteParts }>()
const emit = defineEmits<{ done: [tenantId: string]; dismiss: [] }>()

const meta = ref<InviteMetadata | null>(null)
const error = ref('')
const busy = ref(false)

onMounted(async () => {
  try {
    meta.value = await getInviteMetadata(session.apiBase, props.invite.inviteId)
  } catch {
    error.value = t('accept.failed')
  }
})

const roleLabel = (role?: string) => (role === 'Owner' ? t('invite.owner') : t('invite.member'))

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
</script>

<template>
  <div class="auth-screen">
    <div class="auth-card">
      <p class="auth-wordmark">veyq</p>
      <h1 class="auth-title">{{ t('accept.title') }}</h1>
      <template v-if="meta">
        <p class="auth-sub">{{ t('accept.joinAs', { name: meta.tenant.displayName, role: roleLabel(meta.role) }) }}</p>
        <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>

        <template v-if="session.unlocked">
          <form class="veyq-form" @submit.prevent="join">
            <button class="btn btn-primary" type="submit" :disabled="busy">{{ busy ? t('accept.joining') : t('accept.join') }}</button>
            <button class="btn btn-ghost" type="button" @click="emit('dismiss')">{{ t('accept.dismiss') }}</button>
          </form>
        </template>
        <template v-else>
          <p class="auth-sub">{{ t('accept.signInToJoin') }}</p>
          <LoginPanel embedded />
        </template>
      </template>
      <p v-else-if="error" class="auth-msg auth-msg-error">{{ error }}</p>
      <p v-else class="auth-sub">{{ t('app.loading') }}</p>
    </div>
  </div>
</template>
