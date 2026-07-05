<script setup lang="ts">
// uc.spaces.add-group-member (web, cr-0086) — the "Members" rubric of the GROUP settings (re-homed into the
// settings hub, ADR 0022). Lists the group's members (username + role + granted/awaiting-access) and, for the
// group OWNER, adds an existing tenant member by username (inline). Markup mirrors TenantMembersPanel. Operator-
// blind: only operational metadata (usernames/roles/grant-present) is shown; the add records a grant-less
// membership, the key is delivered later by grant-delivery (cr-0076), no crypto here. Real names stay `@username`
// — they're sealed under K_tenant, which a group owner needn't hold (the active key here is the group's K_SC).
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { listScopeMembers, addGroupMember, memberError, type ScopeMember } from './members'
import { getMyProfile } from '../account/profile/profile'
import { activeSpace } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'

const { t } = useI18n()
const router = useRouter()

const members = ref<ScopeMember[]>([])
const isOwner = ref(false)
const loading = ref(true)
const loadError = ref<'' | 'notAuthorized' | 'failed'>('')
const username = ref('')
const addError = ref('')
const adding = ref(false)

const inGroup = computed(() => activeSpace.kind === ScopeKind.Group && activeSpace.scopeId !== null)
// Min length mirrors the contract (username minLength 2); the server does the real validation + resolve.
const canAdd = computed(() => username.value.trim().length >= 2 && !adding.value)

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  addError.value = ''
  if (!inGroup.value || !activeSpace.scopeId) {
    loading.value = false
    loadError.value = 'failed'
    return
  }
  try {
    // My group role isn't the tenant role — derive it from the directory by matching my per-tenant username.
    const [rows, me] = await Promise.all([listScopeMembers(activeSpace.scopeId), getMyProfile().catch(() => null)])
    members.value = rows
    isOwner.value = !!me && rows.some((m) => m.username === me.username && m.role === 'OWNER')
  } catch (e) {
    const { status } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    loadError.value = status === 403 ? 'notAuthorized' : 'failed'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!canAdd.value || !activeSpace.scopeId) return
  addError.value = ''
  adding.value = true
  try {
    await addGroupMember(activeSpace.scopeId, username.value.trim().toLowerCase())
    username.value = ''
    members.value = await listScopeMembers(activeSpace.scopeId) // refresh — the newcomer shows as awaiting access
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
          </div>
        </div>
      </div>
    </section>
  </section>
</template>

<style scoped>
.settings-muted {
  margin: 0;
  color: var(--app-muted);
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
