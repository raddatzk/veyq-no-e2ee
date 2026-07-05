<script setup lang="ts">
// Group members (uc.spaces.add-group-member, cr-0086). Lists the group's members (username + role + awaiting-
// access status) and, for the group OWNER, adds an existing tenant member by username. Operator-blind: only
// operational metadata (usernames/roles/grant-present) is shown — never a key or a real name (which is sealed
// under K_tenant a group owner may not hold). The add records a grant-less membership; the key is delivered
// afterwards by grant-delivery (cr-0076), no crypto here.
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { listScopeMembers, addGroupMember, memberError, type ScopeMember } from './members'
import { getMyProfile } from '../account/profile/profile'

const props = defineProps<{ scopeId: string }>()
const visible = defineModel<boolean>('visible', { required: true })
const { t } = useI18n()
const router = useRouter()

const members = ref<ScopeMember[]>([])
const isOwner = ref(false)
const loading = ref(false)
const loadError = ref('')
const username = ref('')
const addError = ref('')
const adding = ref(false)
// Min length mirrors the contract (username minLength 2); the server does the real validation + resolve.
const canAdd = computed(() => username.value.trim().length >= 2 && !adding.value)

watch(visible, (isVisible) => {
  if (isVisible) void load()
})

async function load() {
  loading.value = true
  loadError.value = ''
  addError.value = ''
  username.value = ''
  try {
    // My group role isn't the tenant role — derive it from the directory by matching my per-tenant username.
    const [rows, me] = await Promise.all([listScopeMembers(props.scopeId), getMyProfile().catch(() => null)])
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
  if (!canAdd.value) return
  addError.value = ''
  adding.value = true
  try {
    await addGroupMember(props.scopeId, username.value.trim().toLowerCase())
    username.value = ''
    members.value = await listScopeMembers(props.scopeId) // refresh — the newcomer shows as awaiting access
  } catch (e) {
    const { code } = await memberError(e)
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
  <Dialog v-model:visible="visible" :header="t('groups.members')" modal class="tenant-dialog">
    <div class="veyq-form">
      <form v-if="isOwner" class="group-add-form" @submit.prevent="submit">
        <p class="dialog-intro">{{ t('groups.addMemberIntro') }}</p>
        <label>
          {{ t('groups.usernameLabel') }}
          <div class="group-add-row">
            <InputText v-model="username" autocomplete="off" :placeholder="t('groups.usernamePlaceholder')" />
            <Button type="submit" :label="t('groups.addMember')" icon="pi pi-user-plus" :loading="adding" :disabled="!canAdd" />
          </div>
        </label>
        <Message v-if="addError" severity="error" :closable="false">{{ addError }}</Message>
      </form>

      <p v-if="loading" class="dialog-intro">{{ t('members.loading') }}</p>
      <Message v-else-if="loadError" severity="error" :closable="false">
        {{ t(loadError === 'notAuthorized' ? 'members.notAuthorized' : 'members.loadFailed') }}
      </Message>
      <p v-else-if="!members.length" class="dialog-intro">{{ t('members.empty') }}</p>
      <div v-else class="veyq-list">
        <div class="veyq-list management-list-items">
          <div v-for="m in members" :key="m.userId" class="veyq-list-row">
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

      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.done')" icon="pi pi-check" @click="visible = false" />
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.dialog-intro {
  margin: 0 0 0.5rem;
  color: var(--app-muted);
  font-size: 0.9em;
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
