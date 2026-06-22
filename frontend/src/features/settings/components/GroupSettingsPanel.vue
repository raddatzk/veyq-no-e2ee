<script setup lang="ts">
// Group (sub-space) admin: members (role / remove) and delete. Group membership crypto (wrapping the group
// scope to a member's identity) lives in the data layer; this panel drives list/role/remove/delete. Adding
// members (needs the tenant roster + identity keys) and renaming (sealedName is immutable) are follow-ups.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import {
  listGroupMembers, setGroupMemberRole, removeGroupMember, deleteGroup,
  type GroupMember, type GroupRole,
} from '../../spaces/groups/groupSpaces'
import { invalidateSpaces } from '../../../app/spaces'
import { requireSession, session } from '../../../app/session'

const props = defineProps<{ tenantId: string; scopeId: string }>()
const { t } = useI18n()

const members = ref<GroupMember[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
const deleteName = ref('')
const groupName = session.activeSpace?.entry.displayName ?? ''

const roleLabel = (role: string) => t(`tenantAdmin.roles.${role.replace('Group', '')}`)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    members.value = await listGroupMembers(session.apiBase, requireSession(), props.tenantId, props.scopeId)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('settings.loadFailed')
  } finally {
    loading.value = false
  }
}

async function setRole(member: GroupMember, role: GroupRole) {
  if (role === member.role) return
  error.value = ''
  try {
    await setGroupMemberRole(session.apiBase, requireSession(), props.tenantId, props.scopeId, member.userId, role)
    await load()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('tenantAdmin.roleFailed')
  }
}

async function remove(member: GroupMember) {
  error.value = ''
  message.value = ''
  try {
    await removeGroupMember(session.apiBase, requireSession(), props.tenantId, props.scopeId, member.userId)
    message.value = t('tenantAdmin.removed')
    await load()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('tenantAdmin.removeFailed')
  }
}

async function removeGroup() {
  if (deleteName.value.trim() !== groupName) return
  busy.value = true
  error.value = ''
  try {
    await deleteGroup(session.apiBase, requireSession(), props.tenantId, props.scopeId)
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
    <h1>{{ groupName }}</h1>

    <section class="settings-section">
      <div class="settings-section-header"><h2>{{ t('tenantAdmin.members') }}</h2></div>
      <div v-if="loading" class="veyq-loading"><ProgressSpinner class="small-spinner" /><span>{{ t('settings.loading') }}</span></div>
      <template v-else>
        <table class="members-table">
          <tbody>
            <tr v-for="m in members" :key="m.userId">
              <td><strong>{{ m.displayName }}</strong><small>{{ roleLabel(m.role) }}</small></td>
              <td class="members-actions">
                <select :value="m.role" @change="setRole(m, ($event.target as HTMLSelectElement).value as GroupRole)">
                  <option value="GroupOwner">{{ t('tenantAdmin.roles.Owner') }}</option>
                  <option value="GroupMember">{{ t('tenantAdmin.roles.Member') }}</option>
                </select>
                <Button type="button" icon="pi pi-trash" severity="danger" text :aria-label="t('tenantAdmin.remove')" @click="remove(m)" />
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="members.length === 0" class="settings-section-empty">{{ t('tenantAdmin.empty') }}</p>
      </template>
    </section>

    <section class="settings-section">
      <div class="settings-section-header"><h2>{{ t('tenantAdmin.danger') }}</h2></div>
      <label class="veyq-form">
        <span>{{ t('tenantAdmin.deleteConfirm', { name: groupName }) }}</span>
        <InputText v-model="deleteName" />
      </label>
      <Button type="button" :label="t('tenantAdmin.deleteSpace')" icon="pi pi-trash" severity="danger" :disabled="deleteName.trim() !== groupName" :loading="busy" @click="removeGroup" />
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
</style>
