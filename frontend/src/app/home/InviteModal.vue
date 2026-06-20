<script setup lang="ts">
import { ref } from 'vue'
import { session } from '../session'
import { i18n, t } from '../../i18n/i18n'
import { inviteToGroup, emailInviteToGroup } from '../../features/spaces/invite/groups'
import { getTenantTerms, setTenantTerms } from '../../features/memory/share/share'
import { TERMS_CLAUSES } from '../../features/memory/share/termsTemplate'
import { changeMemberRole, listTenantMembers, removeMember, rotateScope, type MemberRole, type TenantMember } from '../../features/admin/inviteMembersRoles/inviteMembers'
import { deleteTenant, cancelTenantDeletion } from '../../features/spaces/invite/groups'
import { clearTenantLogo, scaleTenantLogo, uploadTenantLogo, type ScaledTenantLogo } from '../../features/admin/createTenantInviteOwner/tenant'
import ConfirmDeleteModal from './ConfirmDeleteModal.vue'
import type { SpaceEntry } from '../../features/home/home'

const props = defineProps<{ space: SpaceEntry }>()
const emit = defineEmits<{ close: []; deleted: []; updated: [] }>()
const confirmDelete = ref(false)
const logo = ref<ScaledTenantLogo | null>(null)
const logoBusy = ref(false)
const logoSaved = ref(false)

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

async function chooseLogo(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  try {
    if (logo.value?.previewUrl) URL.revokeObjectURL(logo.value.previewUrl)
    logo.value = await scaleTenantLogo(file)
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function saveLogo() {
  if (!session.unlocked || !props.space.tenantId) return
  if (!logo.value) return
  logoBusy.value = true
  error.value = ''
  try {
    await uploadTenantLogo(session.apiBase, session.unlocked.sessionToken, props.space.tenantId, logo.value)
    logoSaved.value = true
    emit('updated')
    setTimeout(() => (logoSaved.value = false), 1500)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    logoBusy.value = false
  }
}

async function clearLogo() {
  if (!session.unlocked || !props.space.tenantId) return
  logoBusy.value = true
  error.value = ''
  try {
    await clearTenantLogo(session.apiBase, session.unlocked.sessionToken, props.space.tenantId)
    if (logo.value?.previewUrl) URL.revokeObjectURL(logo.value.previewUrl)
    logo.value = null
    logoSaved.value = true
    emit('updated')
    setTimeout(() => (logoSaved.value = false), 1500)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    logoBusy.value = false
  }
}

const role = ref<MemberRole>('Member')
const link = ref('')
const busy = ref(false)
const copied = ref(false)
const error = ref('')

// Protection mode: 'link' = the temp key rides the link in the clear (pragmatic); 'passphrase' = it's
// encrypted under a passphrase shared out of band (operator-blind, even against the email relay).
const protection = ref<'link' | 'passphrase'>('link')
const passphrase = ref('')
const inviteEmail = ref('')
const emailSent = ref(false)
const pass = () => (protection.value === 'passphrase' ? passphrase.value : undefined)

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
  if (protection.value === 'passphrase' && !passphrase.value.trim()) { error.value = t('invite.passphraseRequired'); return }
  busy.value = true
  error.value = ''
  try {
    link.value = await inviteToGroup(session.apiBase, session.unlocked, props.space.tenantId, role.value, window.location.origin, pass())
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function sendEmail() {
  if (!session.unlocked || !props.space.tenantId) return
  if (protection.value === 'passphrase' && !passphrase.value.trim()) { error.value = t('invite.passphraseRequired'); return }
  busy.value = true
  error.value = ''
  emailSent.value = false
  try {
    await emailInviteToGroup(session.apiBase, session.unlocked, props.space.tenantId, role.value, inviteEmail.value, window.location.origin, pass())
    emailSent.value = true
    inviteEmail.value = ''
    setTimeout(() => (emailSent.value = false), 2500)
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
        <h2 class="settings-title">{{ t('tenants.manageTitle') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.close') }}</button>
      </div>
      <div class="settings-section">{{ space.displayName }}</div>
      <div class="settings-field">
        {{ t('tenants.logo') }}
        <div class="tenant-logo-picker">
          <div class="tenant-logo-preview-shell">
            <img v-if="logo?.previewUrl || space.logoUrl" class="tenant-logo-preview" :src="logo?.previewUrl ?? space.logoUrl" alt="" />
            <span v-else class="tenant-logo-placeholder">+</span>
          </div>
          <div class="tenant-logo-actions">
            <label class="btn btn-ghost tenant-logo-trigger">
              {{ t('tenants.chooseLogo') }}
              <input class="tenant-logo-input" type="file" accept="image/png,image/jpeg,image/webp" @change="chooseLogo" />
            </label>
            <button class="btn btn-ghost" type="button" :disabled="logoBusy || !logo" @click="saveLogo">{{ logoSaved ? t('common.saved') : t('common.save') }}</button>
            <button v-if="space.logoUrl || logo" class="btn btn-ghost" type="button" :disabled="logoBusy" @click="clearLogo">{{ t('common.remove') }}</button>
          </div>
        </div>
      </div>

      <label class="invite-role">
        {{ t('invite.role') }}
        <div class="seg">
          <button type="button" :class="{ active: role === 'Member' }" @click="role = 'Member'">{{ t('invite.member') }}</button>
          <button type="button" :class="{ active: role === 'Owner' }" @click="role = 'Owner'">{{ t('invite.owner') }}</button>
        </div>
      </label>

      <label class="invite-role">
        {{ t('invite.protection') }}
        <div class="seg">
          <button type="button" :class="{ active: protection === 'link' }" @click="protection = 'link'">{{ t('invite.protLink') }}</button>
          <button type="button" :class="{ active: protection === 'passphrase' }" @click="protection = 'passphrase'">{{ t('invite.protPassphrase') }}</button>
        </div>
      </label>
      <template v-if="protection === 'passphrase'">
        <input class="invite-link invite-passphrase" v-model="passphrase" type="text" :placeholder="t('invite.passphrasePlaceholder')" />
        <p class="invite-hint">{{ t('invite.passphraseOob') }}</p>
      </template>

      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>

      <template v-if="link">
        <p class="invite-hint">{{ t('invite.link') }}</p>
        <div class="invite-link-row">
          <input class="invite-link" :value="link" readonly @focus="($event.target as HTMLInputElement).select()" />
          <button class="btn btn-primary" type="button" @click="copy">{{ copied ? t('invite.copied') : t('invite.copy') }}</button>
        </div>
      </template>
      <button v-else class="btn btn-primary invite-gen" type="button" :disabled="busy" @click="generate">{{ t('invite.generate') }}</button>

      <p class="invite-hint">{{ t('invite.emailHint') }}</p>
      <div class="invite-link-row">
        <input class="invite-link" v-model="inviteEmail" type="email" autocomplete="off" :placeholder="t('invite.emailPlaceholder')" />
        <button class="btn btn-primary" type="button" :disabled="busy || !inviteEmail.trim()" @click="sendEmail">{{ emailSent ? t('invite.emailSent') : t('invite.emailSend') }}</button>
      </div>

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
        <button class="btn btn-ghost invite-gen tenant-delete-button" type="button" @click="confirmDelete = true">{{ t('delete.tenant') }}</button>
      </template>
    </div>
    <ConfirmDeleteModal v-if="confirmDelete" :name="space.displayName" @close="confirmDelete = false" @confirm="doDeleteTenant" />
  </div>
</template>
