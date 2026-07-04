<script setup lang="ts">
// uc.spaces.add-member (web, cr-0077) — the "Members" rubric of the tenant settings. Lists the active tenant's
// members (name/email + role + granted/awaiting-access status) and, for the tenant OWNER, opens the invite-by-
// email dialog. Markup + classes mirror the create-tenant admin panel (settings-section-header + veyq-list-row +
// veyq-badge). Operator-blind: only operational metadata is shown — pubkeys/roles/grant-present, never a key.
// Admission (invite) is owner-only (the backend 403s otherwise); the directory is any key-holder's.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import InviteMemberDialog from './InviteMemberDialog.vue'
import { listScopeMembers, memberError, type ScopeMember } from './members'
import { listMyTenants } from './scopes'
import { activeSpace } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'

const { t } = useI18n()
const router = useRouter()

const members = ref<ScopeMember[]>([])
const loading = ref(true)
const loadError = ref<'' | 'notAuthorized' | 'failed'>('')
const notice = ref('')
const isOwner = ref(false)
const inviteOpen = ref(false)

const inTenant = computed(() => activeSpace.kind === ScopeKind.Tenant && activeSpace.scopeId !== null)

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  if (!inTenant.value || !activeSpace.scopeId || !activeSpace.tenantId) {
    loading.value = false
    loadError.value = 'failed'
    return
  }
  try {
    members.value = await listScopeMembers(activeSpace.scopeId)
    const mine = (await listMyTenants()).find((tn) => tn.tenantId === activeSpace.tenantId)
    isOwner.value = mine?.role === 'OWNER'
  } catch (e) {
    const { status } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    loadError.value = status === 403 ? 'notAuthorized' : 'failed'
  } finally {
    loading.value = false
  }
}

async function onInvited() {
  notice.value = t('members.inviteSent')
  await load()
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('members.title') }}</h2>
          <p>{{ t('members.sub') }}</p>
        </div>
        <Button
          v-if="isOwner"
          :label="t('members.invite')"
          icon="pi pi-user-plus"
          size="small"
          @click="inviteOpen = true"
        />
      </div>

      <Message v-if="notice" severity="success" :closable="false">{{ notice }}</Message>

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
          <div v-for="member in members" :key="member.userId" class="veyq-list-row member-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ member.displayName }}</strong>
              <span class="veyq-row-subtitle">{{ member.email }} · {{ member.role }}</span>
              <div class="veyq-row-meta">
                <span class="veyq-badge" :class="member.hasGrant ? 'success' : 'warning'">
                  {{ member.hasGrant ? t('members.statusActive') : t('members.statusAwaiting') }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <InviteMemberDialog
      v-if="activeSpace.tenantId"
      v-model:visible="inviteOpen"
      :tenant-id="activeSpace.tenantId"
      @invited="onInvited"
    />
  </section>
</template>

<style scoped>
.settings-muted {
  margin: 0;
  color: var(--app-muted);
}
</style>
