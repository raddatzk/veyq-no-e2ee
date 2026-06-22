<script setup lang="ts">
// Tenant/space admin (Owner): members, invite link, logo, delete. Member removal is two-layer (AD-24): we
// revoke the grant immediately, then offer a scope rotation (new keys + re-sealed content) for forward
// secrecy. All scope crypto lives in the data layer (rotateScope/issueMemberInvite); this is just UI.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import {
  listTenantMembers, changeMemberRole, removeMember, rotateScope,
  type MemberRole, type TenantMember,
} from '../../admin/inviteMembersRoles/inviteMembers'
import { inviteToGroup, deleteTenant } from '../../spaces/invite/groups'
import { scaleTenantLogo, uploadTenantLogo, clearTenantLogo } from '../../admin/createTenantInviteOwner/tenant'
import { invalidateSpaces } from '../../../app/spaces'
import { requireSession, session } from '../../../app/session'

const props = defineProps<{ tenantId: string; scopeId: string }>()
const { t } = useI18n()

const members = ref<TenantMember[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
const inviteUrl = ref('')
const rotateSuggested = ref(false)
const deleteName = ref('')
const spaceName = session.activeSpace?.entry.displayName ?? ''

const token = () => requireSession().sessionToken

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    members.value = await listTenantMembers(session.apiBase, token(), props.tenantId)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('settings.loadFailed')
  } finally {
    loading.value = false
  }
}

async function createInvite() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    inviteUrl.value = await inviteToGroup(session.apiBase, requireSession(), props.tenantId, 'Member', window.location.origin)
    await navigator.clipboard?.writeText(inviteUrl.value).catch(() => {})
    message.value = t('tenantAdmin.inviteCopied')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('tenantAdmin.inviteFailed')
  } finally {
    busy.value = false
  }
}

async function setRole(member: TenantMember, role: MemberRole) {
  if (role === member.role) return
  error.value = ''
  try {
    await changeMemberRole(session.apiBase, token(), props.tenantId, member.userId, role)
    await load()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('tenantAdmin.roleFailed')
  }
}

async function remove(member: TenantMember) {
  error.value = ''
  message.value = ''
  try {
    await removeMember(session.apiBase, token(), props.tenantId, member.userId)
    rotateSuggested.value = true
    message.value = t('tenantAdmin.removed')
    await load()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('tenantAdmin.removeFailed')
  }
}

async function rotate() {
  busy.value = true
  error.value = ''
  try {
    await rotateScope(session.apiBase, requireSession(), props.tenantId)
    rotateSuggested.value = false
    message.value = t('tenantAdmin.rotated')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('tenantAdmin.rotateFailed')
  } finally {
    busy.value = false
  }
}

async function uploadLogo(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  busy.value = true
  error.value = ''
  try {
    await uploadTenantLogo(session.apiBase, token(), props.tenantId, await scaleTenantLogo(file))
    invalidateSpaces()
    message.value = t('common.saved')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('settings.loadFailed')
  } finally {
    busy.value = false
  }
}

async function removeLogo() {
  busy.value = true
  try {
    await clearTenantLogo(session.apiBase, token(), props.tenantId)
    invalidateSpaces()
    message.value = t('common.saved')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('settings.loadFailed')
  } finally {
    busy.value = false
  }
}

async function removeSpace() {
  if (deleteName.value.trim() !== spaceName) return
  busy.value = true
  error.value = ''
  try {
    await deleteTenant(session.apiBase, requireSession(), props.tenantId)
    invalidateSpaces()
    message.value = t('tenantAdmin.deleted')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('tenantAdmin.deleteFailed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <h1>{{ spaceName }}</h1>

    <!-- Members -->
    <section class="settings-section">
      <div class="settings-section-header">
        <h2>{{ t('tenantAdmin.members') }}</h2>
      </div>
      <div v-if="loading" class="veyq-loading"><ProgressSpinner class="small-spinner" /><span>{{ t('settings.loading') }}</span></div>
      <template v-else>
        <table class="members-table">
          <tbody>
            <tr v-for="m in members" :key="m.userId">
              <td><strong>{{ m.displayName }}</strong><small>{{ t(`tenantAdmin.roles.${m.role}`) }} · {{ m.status }}</small></td>
              <td class="members-actions">
                <select :value="m.role" @change="setRole(m, ($event.target as HTMLSelectElement).value as MemberRole)">
                  <option value="Owner">{{ t('tenantAdmin.roles.Owner') }}</option>
                  <option value="Member">{{ t('tenantAdmin.roles.Member') }}</option>
                </select>
                <Button type="button" icon="pi pi-trash" severity="danger" text :aria-label="t('tenantAdmin.remove')" @click="remove(m)" />
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="members.length === 0" class="settings-section-empty">{{ t('tenantAdmin.empty') }}</p>
        <div class="veyq-button-row">
          <Button type="button" :label="t('tenantAdmin.invite')" icon="pi pi-link" :loading="busy" @click="createInvite" />
        </div>
        <code v-if="inviteUrl" class="invite-url">{{ inviteUrl }}</code>
      </template>
    </section>

    <!-- Scope rotation (offered after a removal) -->
    <section v-if="rotateSuggested" class="settings-section">
      <Message severity="warn" :closable="false">{{ t('tenantAdmin.rotateCopy') }}</Message>
      <Button type="button" :label="t('tenantAdmin.rotate')" icon="pi pi-refresh" severity="warn" :loading="busy" @click="rotate" />
    </section>

    <!-- Logo -->
    <section class="settings-section">
      <div class="settings-section-header"><h2>{{ t('tenantAdmin.logo') }}</h2></div>
      <div class="veyq-button-row">
        <label class="kit-file-trigger">
          <span class="p-button p-component p-button-secondary">{{ t('tenantAdmin.logoChoose') }}</span>
          <input class="kit-file-input" type="file" accept="image/png,image/jpeg,image/webp" @change="uploadLogo" />
        </label>
        <Button type="button" :label="t('tenantAdmin.logoClear')" severity="secondary" text @click="removeLogo" />
      </div>
    </section>

    <!-- Danger zone -->
    <section class="settings-section">
      <div class="settings-section-header"><h2>{{ t('tenantAdmin.danger') }}</h2></div>
      <label class="veyq-form">
        <span>{{ t('tenantAdmin.deleteConfirm', { name: spaceName }) }}</span>
        <InputText v-model="deleteName" />
      </label>
      <Button type="button" :label="t('tenantAdmin.deleteSpace')" icon="pi pi-trash" severity="danger" :disabled="deleteName.trim() !== spaceName" :loading="busy" @click="removeSpace" />
    </section>

    <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
  </section>
</template>

<style scoped>
.settings-section { margin-top: 1.5rem; }
.settings-section-empty { opacity: 0.55; font-size: 0.9rem; }
.members-table { width: 100%; border-collapse: collapse; }
.members-table td { padding: 0.5rem 0; border-bottom: 1px solid var(--p-content-border-color, rgba(127,127,127,0.15)); vertical-align: middle; }
.members-table strong { display: block; }
.members-table small { opacity: 0.6; }
.members-actions { text-align: right; display: flex; gap: 0.5rem; justify-content: flex-end; align-items: center; }
.members-actions select { padding: 0.35rem 0.5rem; border-radius: 8px; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.25)); background: var(--p-content-background, transparent); color: inherit; }
.invite-url { display: block; margin-top: 0.5rem; padding: 0.5rem; border-radius: 8px; background: var(--p-content-border-color, rgba(127,127,127,0.12)); word-break: break-all; font-size: 0.8rem; }
</style>
