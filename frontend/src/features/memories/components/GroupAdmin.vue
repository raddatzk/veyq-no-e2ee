<script setup lang="ts">
// Group member administration (GroupOwner). Members are added by re-wrapping the group scope key to a tenant
// member's identity (operator-blind) — candidates come from the tenant member list (which carries their
// identity public keys). Roles, removal, and group deletion are server-enforced (e.g. can't drop the last owner).
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import { addGroupMember, deleteGroup, listGroupMembers, removeGroupMember, setGroupMemberRole, type GroupMember } from '../../../features/spaces/groups/groupSpaces'
import { listTenantMembers, type TenantMember } from '../../../features/admin/inviteMembersRoles/inviteMembers'
import { invalidateSpaces } from '../../../app/spaces'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'

const props = defineProps<{ tenantId: string; scopeId: string }>()
const emit = defineEmits<{ deleted: [] }>()

const members = ref<GroupMember[]>([])
const tenantMembers = ref<TenantMember[]>([])
const addPick = ref('')
const error = ref('')
const busy = ref(false)

const candidates = () => tenantMembers.value.filter((tm) => !members.value.some((m) => m.userId === tm.userId))

async function load() {
  error.value = ''
  try {
    members.value = await listGroupMembers(session.apiBase, session.unlocked!, props.tenantId, props.scopeId)
    tenantMembers.value = (await listTenantMembers(session.apiBase, session.unlocked!.sessionToken, props.tenantId)).filter((m) => m.status === 'ACTIVE')
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function add() {
  const tm = tenantMembers.value.find((m) => m.userId === addPick.value)
  if (!tm) return
  busy.value = true
  error.value = ''
  try {
    await addGroupMember(session.apiBase, session.unlocked!, props.tenantId, props.scopeId, { userId: tm.userId, kemPublicKeyB64: tm.identityPublicKey.kemPublicKey })
    addPick.value = ''
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function toggleRole(m: GroupMember) {
  busy.value = true
  try {
    await setGroupMemberRole(session.apiBase, session.unlocked!, props.tenantId, props.scopeId, m.userId, m.role === 'GroupOwner' ? 'GroupMember' : 'GroupOwner')
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function remove(m: GroupMember) {
  busy.value = true
  try {
    await removeGroupMember(session.apiBase, session.unlocked!, props.tenantId, props.scopeId, m.userId)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function dropGroup() {
  if (!window.confirm(t('delete.group') + '?')) return
  busy.value = true
  try {
    await deleteGroup(session.apiBase, session.unlocked!, props.tenantId, props.scopeId)
    invalidateSpaces()
    emit('deleted')
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

watch(() => [props.tenantId, props.scopeId], load, { immediate: true })
</script>

<template>
  <div class="group-admin">
    <p v-if="error" class="group-error">{{ error }}</p>

    <div class="add-row">
      <select v-model="addPick" class="member-select" :disabled="busy">
        <option value="">{{ t('groups.addMember') }}…</option>
        <option v-for="c in candidates()" :key="c.userId" :value="c.userId">{{ c.displayName }}</option>
      </select>
      <Button type="button" :label="t('groups.add')" icon="pi pi-user-plus" :disabled="busy || !addPick" @click="add" />
    </div>

    <ul class="member-list">
      <li v-for="m in members" :key="m.userId" class="member-row">
        <span class="member-meta">
          <strong>{{ m.displayName }}</strong>
          <small>{{ m.role === 'GroupOwner' ? t('groups.roleOwner') : t('groups.roleMember') }}</small>
        </span>
        <div class="member-actions">
          <Button type="button" :label="m.role === 'GroupOwner' ? t('groups.roleMember') : t('groups.roleOwner')" severity="secondary" text size="small" :disabled="busy" @click="toggleRole(m)" />
          <Button type="button" :label="t('members.remove')" severity="danger" text size="small" :disabled="busy" @click="remove(m)" />
        </div>
      </li>
    </ul>

    <Button type="button" :label="t('delete.group')" icon="pi pi-trash" severity="danger" outlined :disabled="busy" @click="dropGroup" />
  </div>
</template>

<style scoped>
.group-admin { margin: 0.5rem 0; }
.group-error { color: var(--p-red-400, #f87171); }
.add-row { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; }
.member-select { flex: 1; padding: 0.5rem; border-radius: 8px; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.3)); background: var(--p-content-background, transparent); color: inherit; }
.member-list { list-style: none; padding: 0; margin: 0 0 1rem; display: flex; flex-direction: column; gap: 0.4rem; }
.member-row { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
.member-meta { display: flex; flex-direction: column; }
.member-meta small { opacity: 0.6; }
</style>
