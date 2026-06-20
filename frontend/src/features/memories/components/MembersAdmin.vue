<script setup lang="ts">
// Tenant member administration (Owner only): issue a temp-wrap member invite (the link the InviteView
// accepts — closing the invite loop), list members, change roles, remove, and rotate the scope key so a
// removed member loses access to future content. All key material is wrapped client-side; server stays blind.
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import Message from 'primevue/message'
import {
  changeMemberRole,
  issueMemberInvite,
  listTenantMembers,
  removeMember,
  rotateScope,
  type MemberRole,
  type TenantMember,
} from '../../../features/admin/inviteMembersRoles/inviteMembers'
import { buildInviteLink } from '../../../features/spaces/invite/inviteLink'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'

const props = defineProps<{ tenantId: string }>()

const members = ref<TenantMember[]>([])
const error = ref('')
const busy = ref(false)
const inviteRole = ref<MemberRole>('Member')
const inviteLink = ref('')
const copied = ref(false)
const rotated = ref(false)

async function load() {
  error.value = ''
  try {
    members.value = await listTenantMembers(session.apiBase, session.unlocked!.sessionToken, props.tenantId)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function invite() {
  busy.value = true
  error.value = ''
  inviteLink.value = ''
  copied.value = false
  try {
    const parts = await issueMemberInvite(session.apiBase, session.unlocked!, props.tenantId, inviteRole.value)
    inviteLink.value = buildInviteLink(window.location.origin, parts)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function copyLink() {
  try {
    await navigator.clipboard.writeText(inviteLink.value)
    copied.value = true
  } catch {
    /* clipboard unavailable */
  }
}

async function setRole(member: TenantMember, role: MemberRole) {
  busy.value = true
  try {
    await changeMemberRole(session.apiBase, session.unlocked!.sessionToken, props.tenantId, member.userId, role)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function remove(member: TenantMember) {
  if (!window.confirm(t('members.remove') + '?')) return
  busy.value = true
  try {
    await removeMember(session.apiBase, session.unlocked!.sessionToken, props.tenantId, member.userId)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function rotate() {
  busy.value = true
  rotated.value = false
  error.value = ''
  try {
    await rotateScope(session.apiBase, session.unlocked!, props.tenantId)
    rotated.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

watch(() => props.tenantId, load, { immediate: true })
</script>

<template>
  <div class="members-admin">
    <p v-if="error" class="members-error">{{ error }}</p>

    <div class="invite-row">
      <div class="seg">
        <button type="button" :class="{ active: inviteRole === 'Member' }" @click="inviteRole = 'Member'">{{ t('invite.member') }}</button>
        <button type="button" :class="{ active: inviteRole === 'Owner' }" @click="inviteRole = 'Owner'">{{ t('invite.owner') }}</button>
      </div>
      <Button type="button" :label="t('invite.generate')" icon="pi pi-link" :loading="busy" @click="invite" />
    </div>
    <div v-if="inviteLink" class="invite-created">
      <small class="field-hint">{{ t('invite.link') }}</small>
      <div class="invite-link-row">
        <code class="invite-link">{{ inviteLink }}</code>
        <Button type="button" :label="copied ? t('invite.copied') : t('invite.copy')" :icon="copied ? 'pi pi-check' : 'pi pi-copy'" severity="secondary" outlined @click="copyLink" />
      </div>
    </div>

    <h4 class="members-section">{{ t('members.title') }}</h4>
    <ul class="member-list">
      <li v-for="m in members" :key="m.userId" class="member-row">
        <span class="member-meta">
          <strong>{{ m.displayName }}</strong>
          <small>{{ m.role }}<template v-if="m.status !== 'ACTIVE'"> · {{ m.status }}</template></small>
        </span>
        <div class="member-actions">
          <Button v-if="m.role !== 'Owner'" type="button" :label="t('invite.owner')" severity="secondary" text size="small" :disabled="busy" @click="setRole(m, 'Owner')" />
          <Button v-else type="button" :label="t('invite.member')" severity="secondary" text size="small" :disabled="busy" @click="setRole(m, 'Member')" />
          <Button type="button" :label="t('members.remove')" severity="danger" text size="small" :disabled="busy" @click="remove(m)" />
        </div>
      </li>
    </ul>

    <p class="members-hint">{{ t('members.rotateHint') }}</p>
    <Message v-if="rotated" severity="success" :closable="false">{{ t('members.rotated') }}</Message>
    <Button type="button" :label="t('members.rotate')" icon="pi pi-refresh" severity="secondary" outlined :loading="busy" @click="rotate" />
  </div>
</template>

<style scoped>
.members-admin { margin: 0.5rem 0; }
.members-error { color: var(--p-red-400, #f87171); }
.invite-row { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.75rem; }
.seg { display: inline-flex; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.3)); border-radius: 8px; overflow: hidden; }
.seg button { background: none; border: none; padding: 0.3rem 0.7rem; cursor: pointer; color: inherit; }
.seg button.active { background: var(--p-primary-color, #6366f1); color: var(--p-primary-contrast-color, #fff); }
.invite-created { margin-bottom: 1rem; }
.invite-link-row { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
.invite-link { flex: 1; min-width: 12rem; word-break: break-all; background: var(--p-content-border-color, rgba(127,127,127,0.12)); padding: 0.5rem; border-radius: 6px; font-size: 0.8rem; }
.members-section { margin: 1rem 0 0.5rem; }
.member-list { list-style: none; padding: 0; margin: 0 0 1rem; display: flex; flex-direction: column; gap: 0.4rem; }
.member-row { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
.member-meta { display: flex; flex-direction: column; }
.member-meta small { opacity: 0.6; }
.members-hint { opacity: 0.7; font-size: 0.9rem; margin: 0.5rem 0; }
</style>
