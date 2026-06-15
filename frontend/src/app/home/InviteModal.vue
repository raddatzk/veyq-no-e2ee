<script setup lang="ts">
import { ref } from 'vue'
import { session } from '../session'
import { i18n, t } from '../../i18n/i18n'
import { inviteToGroup } from '../../features/spaces/invite/groups'
import { getTenantTerms, setTenantTerms } from '../../features/memory/share/share'
import { TERMS_CLAUSES } from '../../features/memory/share/termsTemplate'
import { changeMemberRole, listTenantMembers, removeMember, rotateScope, type MemberRole, type TenantMember } from '../../features/admin/inviteMembersRoles/inviteMembers'
import { deleteTenant, cancelTenantDeletion } from '../../features/spaces/invite/groups'
import ConfirmDeleteModal from './ConfirmDeleteModal.vue'
import type { SpaceEntry } from '../../features/home/home'

const props = defineProps<{ space: SpaceEntry }>()
const emit = defineEmits<{ close: []; deleted: [] }>()
const confirmDelete = ref(false)

async function doDeleteTenant() {
  if (!session.unlocked || !props.space.tenantId) return
  try {
    await deleteTenant(session.apiBase, session.unlocked, props.space.tenantId)
    emit('deleted')
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function doCancelDeletion() {
  if (!session.unlocked || !props.space.tenantId) return
  try {
    await cancelTenantDeletion(session.apiBase, session.unlocked, props.space.tenantId)
    emit('deleted')
  } catch (e) {
    error.value = (e as Error).message
  }
}

const role = ref<MemberRole>('Member')
const link = ref('')
const busy = ref(false)
const copied = ref(false)
const error = ref('')

// --- Members (owner-only: list, change role, remove, rotate keys) ---
const members = ref<TenantMember[]>([])
const membersOpen = ref(false)
const membersBusy = ref(false)
const rotated = ref(false)
const myId = () => session.unlocked?.user.id

async function openMembers() {
  membersOpen.value = true
  await reloadMembers()
}
async function reloadMembers() {
  if (!session.unlocked || !props.space.tenantId) return
  try {
    members.value = (await listTenantMembers(session.apiBase, session.unlocked.sessionToken, props.space.tenantId)).filter((m) => m.status === 'ACTIVE')
  } catch (e) {
    error.value = (e as Error).message
  }
}
async function setMemberRole(m: TenantMember, newRole: MemberRole) {
  if (!session.unlocked || !props.space.tenantId || m.role === newRole) return
  membersBusy.value = true
  try {
    await changeMemberRole(session.apiBase, session.unlocked.sessionToken, props.space.tenantId, m.userId, newRole)
    await reloadMembers()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    membersBusy.value = false
  }
}
async function remove(m: TenantMember) {
  if (!session.unlocked || !props.space.tenantId) return
  membersBusy.value = true
  try {
    await removeMember(session.apiBase, session.unlocked.sessionToken, props.space.tenantId, m.userId)
    await reloadMembers()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    membersBusy.value = false
  }
}
async function rotate() {
  if (!session.unlocked || !props.space.tenantId) return
  membersBusy.value = true
  error.value = ''
  try {
    await rotateScope(session.apiBase, session.unlocked, props.space.tenantId)
    rotated.value = true
    setTimeout(() => (rotated.value = false), 2000)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    membersBusy.value = false
  }
}

// --- Terms of Service (the group ToS guests must accept on any share) ---
const termsOpen = ref(false)
const terms = ref('')
const termsBusy = ref(false)
const termsSaved = ref(false)
const loc = () => (i18n.locale === 'en' ? 'en' : 'de')

async function openTerms() {
  termsOpen.value = true
  if (!session.unlocked || !props.space.tenantId) return
  try {
    terms.value = (await getTenantTerms(session.apiBase, session.unlocked, props.space.tenantId)).termsMarkdown ?? ''
  } catch { /* none yet */ }
}
function addClause(markdown: string) {
  terms.value = terms.value.trim() ? `${terms.value.trim()}\n\n${markdown}` : markdown
}
async function saveTerms() {
  if (!session.unlocked || !props.space.tenantId) return
  termsBusy.value = true
  try {
    await setTenantTerms(session.apiBase, session.unlocked, props.space.tenantId, terms.value)
    termsSaved.value = true
    setTimeout(() => (termsSaved.value = false), 1500)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    termsBusy.value = false
  }
}

async function generate() {
  if (!session.unlocked || !props.space.tenantId) return
  busy.value = true
  error.value = ''
  try {
    link.value = await inviteToGroup(session.apiBase, session.unlocked, props.space.tenantId, role.value, window.location.origin)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function copy() {
  await navigator.clipboard.writeText(link.value)
  copied.value = true
  setTimeout(() => (copied.value = false), 1500)
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel">
      <div class="settings-head">
        <h2 class="settings-title">{{ t('invite.title') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.close') }}</button>
      </div>
      <div class="settings-section">{{ space.displayName }}</div>

      <label class="invite-role">
        {{ t('invite.role') }}
        <div class="seg">
          <button type="button" :class="{ active: role === 'Member' }" @click="role = 'Member'">{{ t('invite.member') }}</button>
          <button type="button" :class="{ active: role === 'Owner' }" @click="role = 'Owner'">{{ t('invite.owner') }}</button>
        </div>
      </label>

      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>

      <template v-if="link">
        <p class="invite-hint">{{ t('invite.link') }}</p>
        <div class="invite-link-row">
          <input class="invite-link" :value="link" readonly @focus="($event.target as HTMLInputElement).select()" />
          <button class="btn btn-primary" type="button" @click="copy">{{ copied ? t('invite.copied') : t('invite.copy') }}</button>
        </div>
      </template>
      <button v-else class="btn btn-primary invite-gen" type="button" :disabled="busy" @click="generate">{{ t('invite.generate') }}</button>

      <div class="settings-section">{{ t('terms.title') }}</div>
      <template v-if="termsOpen">
        <p class="invite-hint">{{ t('terms.hint') }}</p>
        <div class="terms-clauses">
          <button v-for="c in TERMS_CLAUSES" :key="c.id" type="button" class="terms-clause" @click="addClause(c.markdown[loc()])">+ {{ c.label[loc()] }}</button>
        </div>
        <textarea class="share-field terms-editor" v-model="terms" :placeholder="t('terms.placeholder')"></textarea>
        <button class="btn btn-primary invite-gen" type="button" :disabled="termsBusy" @click="saveTerms">{{ termsSaved ? t('terms.saved') : t('terms.save') }}</button>
      </template>
      <button v-else class="btn btn-ghost invite-gen" type="button" @click="openTerms">{{ t('terms.edit') }}</button>

      <div class="settings-section">{{ t('members.title') }}</div>
      <template v-if="membersOpen">
        <ul class="member-list">
          <li v-for="m in members" :key="m.userId" class="member-row">
            <span class="member-name">{{ m.displayName }}<small v-if="m.userId === myId()"> · {{ t('members.you') }}</small></span>
            <div class="seg member-roles">
              <button type="button" :class="{ active: m.role === 'Member' }" :disabled="membersBusy || m.userId === myId()" @click="setMemberRole(m, 'Member')">{{ t('invite.member') }}</button>
              <button type="button" :class="{ active: m.role === 'Owner' }" :disabled="membersBusy || m.userId === myId()" @click="setMemberRole(m, 'Owner')">{{ t('invite.owner') }}</button>
            </div>
            <button class="btn btn-ghost track-del" type="button" :disabled="membersBusy || m.userId === myId()" @click="remove(m)">{{ t('members.remove') }}</button>
          </li>
        </ul>
        <p class="invite-hint">{{ t('members.rotateHint') }}</p>
        <button class="btn btn-ghost invite-gen" type="button" :disabled="membersBusy" @click="rotate">{{ rotated ? t('members.rotated') : t('members.rotate') }}</button>
      </template>
      <button v-else class="btn btn-ghost invite-gen" type="button" @click="openMembers">{{ t('members.manage') }}</button>

      <template v-if="space.scheduledDeletionAt">
        <p class="invite-hint">{{ t('delete.scheduledBanner', { date: new Date(space.scheduledDeletionAt).toLocaleDateString() }) }}</p>
        <button class="btn btn-ghost invite-gen" type="button" @click="doCancelDeletion">{{ t('delete.cancelDeletion') }}</button>
      </template>
      <template v-else>
        <p class="invite-hint">{{ t('delete.tenantWarning') }}</p>
        <button class="btn btn-ghost invite-gen" type="button" style="color: var(--app-danger)" @click="confirmDelete = true">{{ t('delete.tenant') }}</button>
      </template>
    </div>
    <ConfirmDeleteModal v-if="confirmDelete" :name="space.displayName" @close="confirmDelete = false" @confirm="doDeleteTenant" />
  </div>
</template>
