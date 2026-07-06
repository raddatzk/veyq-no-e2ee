<script setup lang="ts">
// uc.spaces.add-group-member (web, cr-0086) — the "Members" rubric of the GROUP settings (re-homed into the
// settings hub, ADR 0022). Lists the group's members (username + role + granted/awaiting-access) and, for the
// group OWNER, adds an existing tenant member by username (inline). Markup mirrors TenantMembersPanel. Operator-
// blind: only operational metadata (usernames/roles/grant-present) is shown; the add records a grant-less
// membership, the key is delivered later by grant-delivery (cr-0076), no crypto here. Real names stay `@username`
// — they're sealed under K_tenant, which a group owner needn't hold (the active key here is the group's K_SC).
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { listScopeMembers, addGroupMember, setMemberRole, removeGroupMember, memberError, SetMemberRoleRequestRoleEnum, type ScopeMember } from './members'
import { rotateGroupForRemoval, deleteGroup, restoreGroup, scopeLifecycle } from './scopes'
import { activeSpace, refreshActiveScope } from '../../app/activeSpace'
import { getMyProfile } from '../account/profile/profile'

// Addressed by scope id (cr-0087) — the settings hub renders one panel per group the caller owns, not the
// active scope; switching between groups in the rail re-loads via the `scopeId` watch.
const props = defineProps<{ scopeId: string }>()

const { t, locale } = useI18n()
const router = useRouter()
const emit = defineEmits<{ lifecycleChanged: [] }>()

const members = ref<ScopeMember[]>([])
const isOwner = ref(false)
const myUsername = ref('')
const loading = ref(true)
const loadError = ref<'' | 'notAuthorized' | 'failed'>('')
const username = ref('')
const addError = ref('')
const adding = ref(false)
const actionError = ref('')
const pendingRemove = ref<string | null>(null) // userId shown in inline "remove?" confirm (destructive → rotates)
const removing = ref(false)
// Group lifecycle (cr-0090): the group's own pending-deletion state + the danger-zone controls.
const purgeAfter = ref<Date | null>(null)
const isPending = computed(() => purgeAfter.value !== null)
const confirmingDelete = ref(false)
const lifecycleBusy = ref(false)
const lifecycleError = ref('')
const purgeDay = computed(() => (purgeAfter.value ? purgeAfter.value.toLocaleDateString(locale.value) : ''))

// Min length mirrors the contract (username minLength 2); the server does the real validation + resolve.
const canAdd = computed(() => username.value.trim().length >= 2 && !adding.value)

onMounted(load)
watch(() => props.scopeId, load)

async function load() {
  loading.value = true
  loadError.value = ''
  addError.value = ''
  if (!props.scopeId) {
    loading.value = false
    loadError.value = 'failed'
    return
  }
  try {
    // My group role isn't the tenant role — derive it from the directory by matching my per-tenant username.
    const [rows, me, lifecycle] = await Promise.all([
      listScopeMembers(props.scopeId), getMyProfile().catch(() => null), scopeLifecycle(props.scopeId).catch(() => null),
    ])
    members.value = rows
    myUsername.value = me?.username ?? ''
    isOwner.value = !!me && rows.some((m) => m.username === me.username && m.role === 'OWNER')
    purgeAfter.value = lifecycle?.purgeAfter ?? null
  } catch (e) {
    const { status } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    loadError.value = status === 403 ? 'notAuthorized' : 'failed'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!canAdd.value || !props.scopeId) return
  addError.value = ''
  adding.value = true
  try {
    await addGroupMember(props.scopeId, username.value.trim().toLowerCase())
    username.value = ''
    members.value = await listScopeMembers(props.scopeId) // refresh — the newcomer shows as awaiting access
  } catch (e) {
    const { status, code } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    addError.value =
      code === 'member_not_found'
        ? t('groups.memberNotFound')
        : code === 'already_member'
          ? t('members.alreadyMember')
          : code === 'not_scope_owner'
            ? t('members.notOwner')
            : t('groups.addMemberFailed')
  } finally {
    adding.value = false
  }
}

// Promote a member to co-owner or demote a co-owner (cr-0088) — owner-only; the last-owner guard is enforced
// server-side (409 last_owner). A pure role change; the directory reloads to reflect it.
async function changeRole(m: ScopeMember, role: SetMemberRoleRequestRoleEnum) {
  actionError.value = ''
  try {
    await setMemberRole(props.scopeId, m.userId, role)
    members.value = await listScopeMembers(props.scopeId)
  } catch (e) {
    const { status, code } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    actionError.value =
      code === 'last_owner'
        ? t('groups.lastOwner')
        : code === 'not_scope_owner'
          ? t('members.notOwner')
          : t('groups.roleChangeFailed')
  }
}

// Remove a member (cr-0089) — the cryptographic removal: delete the membership, then **rotate the scope key
// forward** and re-grant the remaining members (ADR 0024), so the ex-member reads pre-removal content but never
// post-removal content. Owner-only; last-owner guarded server-side. If the rotated group is the active browsing
// scope, refresh its epoch keys so the owner's next new content seals under the fresh epoch, not the stale one.
async function removeMember(m: ScopeMember) {
  actionError.value = ''
  removing.value = true
  try {
    await removeGroupMember(props.scopeId, m.userId)
    const remaining = await listScopeMembers(props.scopeId)
    await rotateGroupForRemoval(props.scopeId, remaining)
    members.value = remaining
    if (activeSpace.scopeId === props.scopeId) await refreshActiveScope()
  } catch (e) {
    const { status, code } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    actionError.value =
      code === 'last_owner'
        ? t('groups.lastOwner')
        : code === 'not_scope_owner'
          ? t('members.notOwner')
          : t('groups.removeMemberFailed')
  } finally {
    removing.value = false
    pendingRemove.value = null
  }
}

// Soft-delete the group (cr-0090) — enters the 30-day grace period (owner-only, inline confirm). The group stays
// readable but read-only + marked; any owner can restore it in-window. Refresh the active scope if it's this group
// (so its banner/read-only state shows) and let the settings hub re-badge its group list.
async function onDelete() {
  lifecycleError.value = ''
  lifecycleBusy.value = true
  try {
    await deleteGroup(props.scopeId)
    if (activeSpace.scopeId === props.scopeId) await refreshActiveScope()
    await load()
    emit('lifecycleChanged')
  } catch (e) {
    const { status, code } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    lifecycleError.value = code === 'not_scope_owner' ? t('members.notOwner') : t('groups.deleteFailed')
  } finally {
    lifecycleBusy.value = false
    confirmingDelete.value = false
  }
}

/** Restore the group from pending-deletion (owner-only, in-window). */
async function onRestore() {
  lifecycleError.value = ''
  lifecycleBusy.value = true
  try {
    await restoreGroup(props.scopeId)
    if (activeSpace.scopeId === props.scopeId) await refreshActiveScope()
    await load()
    emit('lifecycleChanged')
  } catch (e) {
    const { status, code } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    lifecycleError.value = code === 'not_scope_owner' ? t('members.notOwner') : t('groups.restoreFailed')
  } finally {
    lifecycleBusy.value = false
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('groups.members') }}</h2>
          <p>{{ t('groups.membersSub') }}</p>
        </div>
      </div>

      <!-- Owner-only: add an existing tenant member by username (inline; the tenant owner has no say, ADR 0014). -->
      <form v-if="isOwner" class="group-add-form" @submit.prevent="submit">
        <label class="settings-muted" for="group-add-username">{{ t('groups.addMemberIntro') }}</label>
        <div class="group-add-row">
          <InputText id="group-add-username" v-model="username" autocomplete="off" :placeholder="t('groups.usernamePlaceholder')" />
          <Button type="submit" :label="t('groups.addMember')" icon="pi pi-user-plus" :loading="adding" :disabled="!canAdd" />
        </div>
        <Message v-if="addError" severity="error" :closable="false">{{ addError }}</Message>
      </form>

      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

      <p v-if="loading" class="settings-muted">{{ t('members.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="warn" :closable="false">
        {{ t('members.notAuthorized') }}
      </Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">
        {{ t('members.loadFailed') }}
      </Message>
      <p v-else-if="members.length === 0" class="settings-muted">{{ t('members.empty') }}</p>

      <div v-else class="veyq-list management-list">
        <div class="veyq-list management-list-items">
          <div v-for="m in members" :key="m.userId" class="veyq-list-row member-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">@{{ m.username }}</strong>
              <span class="veyq-row-subtitle">{{ m.role }}</span>
              <div class="veyq-row-meta">
                <span class="veyq-badge" :class="m.hasGrant ? 'success' : 'warning'">
                  {{ m.hasGrant ? t('members.statusActive') : t('members.statusAwaiting') }}
                </span>
              </div>
            </div>
            <!-- Owner-only controls (cr-0088/0089): promote a member to co-owner, demote a co-owner, or remove a
                 member. Remove is destructive (rotates the scope key forward) so it takes an inline confirm. The
                 last-owner guard is enforced server-side. You can't remove yourself here. -->
            <div v-if="isOwner && pendingRemove === m.userId" class="management-actions">
              <span class="settings-muted remove-confirm-label">{{ t('groups.removeConfirm') }}</span>
              <Button
                :label="t('groups.removeConfirmYes')"
                icon="pi pi-user-minus"
                severity="danger"
                size="small"
                :loading="removing"
                @click="removeMember(m)"
              />
              <Button
                :label="t('common.cancel')"
                severity="secondary"
                outlined
                size="small"
                :disabled="removing"
                @click="pendingRemove = null"
              />
            </div>
            <div v-else-if="isOwner" class="management-actions">
              <Button
                v-if="m.role === 'MEMBER'"
                :label="t('groups.promote')"
                icon="pi pi-arrow-up"
                severity="secondary"
                outlined
                size="small"
                @click="changeRole(m, SetMemberRoleRequestRoleEnum.Owner)"
              />
              <Button
                v-else
                :label="t('groups.demote')"
                icon="pi pi-arrow-down"
                severity="secondary"
                outlined
                size="small"
                @click="changeRole(m, SetMemberRoleRequestRoleEnum.Member)"
              />
              <Button
                v-if="m.username !== myUsername"
                :label="t('groups.remove')"
                icon="pi pi-user-minus"
                severity="danger"
                outlined
                size="small"
                @click="pendingRemove = m.userId"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- Danger zone (cr-0090): owner-only group lifecycle. Active → delete (30-day grace, inline confirm);
           pending-deletion → a countdown + restore. Deletion is soft + reversible in-window, so it's not a hard
           destructive action here. -->
      <section v-if="isOwner && !loading && loadError === ''" class="settings-form danger-zone">
        <div class="settings-section-header">
          <div>
            <h2>{{ t('groups.dangerZone') }}</h2>
            <p class="settings-muted">{{ isPending ? t('groups.pendingSub') : t('groups.deleteSub') }}</p>
          </div>
        </div>

        <Message v-if="lifecycleError" severity="error" :closable="false">{{ lifecycleError }}</Message>

        <div v-if="isPending" class="danger-row">
          <Message severity="warn" :closable="false">{{ t('groups.pendingDeletion', { day: purgeDay }) }}</Message>
          <Button :label="t('groups.restore')" icon="pi pi-undo" :loading="lifecycleBusy" @click="onRestore" />
        </div>
        <div v-else-if="confirmingDelete" class="danger-row">
          <span class="settings-muted">{{ t('groups.deleteConfirm') }}</span>
          <Button :label="t('groups.deleteConfirmYes')" icon="pi pi-trash" severity="danger" :loading="lifecycleBusy" @click="onDelete" />
          <Button :label="t('common.cancel')" severity="secondary" outlined :disabled="lifecycleBusy" @click="confirmingDelete = false" />
        </div>
        <div v-else class="danger-row">
          <Button :label="t('groups.deleteGroup')" icon="pi pi-trash" severity="danger" outlined @click="confirmingDelete = true" />
        </div>
      </section>
    </section>
  </section>
</template>

<style scoped>
.settings-muted {
  margin: 0;
  color: var(--app-muted);
}
.danger-zone {
  margin-top: 1.5rem;
  padding-top: 1.25rem;
  border-top: 1px solid var(--app-border, rgba(128, 128, 128, 0.25));
}
.danger-row {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
}
.danger-row :deep(.p-message) {
  margin: 0;
  flex: 1;
  min-width: 0;
}
.group-add-form {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.group-add-row {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.group-add-row :deep(.p-inputtext) {
  flex: 1;
  min-width: 0;
}
</style>
