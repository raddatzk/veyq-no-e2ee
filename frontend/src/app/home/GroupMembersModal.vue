<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { session } from '../session'
import { t } from '../../i18n/i18n'
import { listTenantMembers, type TenantMember } from '../../features/admin/inviteMembersRoles/inviteMembers'
import { addGroupMember, deleteGroup, listGroupMembers, removeGroupMember, setGroupMemberRole, type GroupMember, type GroupRole } from '../../features/spaces/groups/groupSpaces'
import ConfirmDeleteModal from './ConfirmDeleteModal.vue'
import type { SpaceEntry } from '../../features/home/home'

const props = defineProps<{ space: SpaceEntry }>()
const emit = defineEmits<{ close: []; deleted: [] }>()
const confirmDelete = ref(false)

async function doDeleteGroup() {
  if (!session.unlocked || !props.space.tenantId) return
  try {
    await deleteGroup(session.apiBase, session.unlocked, props.space.tenantId, props.space.scopeId)
    emit('deleted')
  } catch (e) {
    error.value = (e as Error).message
  }
}

const members = ref<GroupMember[]>([])
const candidates = ref<TenantMember[]>([])
const toAdd = ref('')
const busy = ref(false)
const error = ref('')
const myId = () => session.unlocked?.user.id
const tenantId = () => props.space.tenantId ?? ''
const scopeId = () => props.space.scopeId

async function reload() {
  if (!session.unlocked || !props.space.tenantId) return
  error.value = ''
  try {
    members.value = await listGroupMembers(session.apiBase, session.unlocked, tenantId(), scopeId())
    const inGroup = new Set(members.value.map((m) => m.userId))
    const tenantMembers = await listTenantMembers(session.apiBase, session.unlocked.sessionToken, tenantId())
    candidates.value = tenantMembers.filter((m) => m.status === 'ACTIVE' && !inGroup.has(m.userId))
    toAdd.value = candidates.value[0]?.userId ?? ''
  } catch (e) {
    error.value = (e as Error).message
  }
}
onMounted(reload)

async function add() {
  const c = candidates.value.find((m) => m.userId === toAdd.value)
  if (!session.unlocked || !c) return
  busy.value = true
  error.value = ''
  try {
    await addGroupMember(session.apiBase, session.unlocked, tenantId(), scopeId(), { userId: c.userId, kemPublicKeyB64: c.identityPublicKey.kemPublicKey })
    await reload()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function setRole(m: GroupMember, role: GroupRole) {
  if (!session.unlocked || m.role === role) return
  busy.value = true
  error.value = ''
  try {
    await setGroupMemberRole(session.apiBase, session.unlocked, tenantId(), scopeId(), m.userId, role)
    await reload()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function remove(m: GroupMember) {
  if (!session.unlocked) return
  busy.value = true
  error.value = ''
  try {
    await removeGroupMember(session.apiBase, session.unlocked, tenantId(), scopeId(), m.userId)
    await reload()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="settings-backdrop" @click.self="emit('close')">
    <div class="settings-panel">
      <div class="settings-head">
        <h2 class="settings-title">{{ t('groups.members') }}</h2>
        <button class="btn btn-ghost" type="button" @click="emit('close')">{{ t('common.close') }}</button>
      </div>

      <div v-for="m in members" :key="m.userId" class="member-row">
        <span class="member-name">{{ m.displayName }}<span v-if="m.userId === myId()"> ({{ t('groups.you') }})</span></span>
        <select :value="m.role" :disabled="busy" @change="setRole(m, ($event.target as HTMLSelectElement).value as GroupRole)">
          <option value="GroupOwner">{{ t('groups.roleOwner') }}</option>
          <option value="GroupMember">{{ t('groups.roleMember') }}</option>
        </select>
        <button class="btn btn-ghost" type="button" :disabled="busy" @click="remove(m)">{{ t('common.remove') }}</button>
      </div>

      <form v-if="candidates.length" class="veyq-form member-add" @submit.prevent="add">
        <label>
          {{ t('groups.addMember') }}
          <select v-model="toAdd">
            <option v-for="c in candidates" :key="c.userId" :value="c.userId">{{ c.displayName }}</option>
          </select>
        </label>
        <button class="btn btn-primary" type="submit" :disabled="busy || !toAdd">{{ t('groups.add') }}</button>
      </form>

      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>

      <button class="btn btn-ghost" type="button" style="color: var(--app-danger); margin-top: 0.5rem" @click="confirmDelete = true">{{ t('delete.group') }}</button>
    </div>
    <ConfirmDeleteModal v-if="confirmDelete" :name="space.displayName" @close="confirmDelete = false" @confirm="doDeleteGroup" />
  </div>
</template>
