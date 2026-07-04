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
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import InviteMemberDialog from './InviteMemberDialog.vue'
import {
  listMemberInvites,
  listScopeMembers,
  memberError,
  reissueMemberInvite,
  revokeMemberInvite,
  type PendingMemberInvite,
  type ScopeMember,
} from './members'
import { listMyTenants } from './scopes'
import { activeSpace } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'
import { buildInviteLink, copyText } from '../../shared/inviteLink'

const { t } = useI18n()
const router = useRouter()

const members = ref<ScopeMember[]>([])
const pendingInvites = ref<PendingMemberInvite[]>([])
const loading = ref(true)
const loadError = ref<'' | 'notAuthorized' | 'failed'>('')
const notice = ref('')
const actionError = ref('')
const isOwner = ref(false)
const inviteOpen = ref(false)

// After a re-issue, show the fresh invite link to copy (cr-0080) — the owner hands it off again, no mailer.
const reissueUrl = ref('')
const reissueCopied = ref(false)

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
    // Only the owner may read the pending-invite list (the backend 403s any non-owner).
    pendingInvites.value = isOwner.value ? await listMemberInvites(activeSpace.tenantId) : []
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

async function reissue(invite: PendingMemberInvite) {
  actionError.value = ''
  notice.value = ''
  reissueUrl.value = ''
  reissueCopied.value = false
  try {
    const state = await reissueMemberInvite(activeSpace.tenantId!, invite.inviteId)
    reissueUrl.value = buildInviteLink(state.bootstrapSecret) // same host — the owner invites into their tenant
    notice.value = t('members.inviteReissued')
    await load()
  } catch (e) {
    const { status } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    actionError.value = t('members.actionFailed')
  }
}

async function copyReissueLink() {
  reissueCopied.value = await copyText(reissueUrl.value)
}

async function revoke(invite: PendingMemberInvite) {
  if (!window.confirm(t('members.revokeConfirm'))) return
  actionError.value = ''
  notice.value = ''
  reissueUrl.value = ''
  try {
    await revokeMemberInvite(activeSpace.tenantId!, invite.inviteId)
    notice.value = t('members.inviteRevoked')
    await load()
  } catch (e) {
    const { status } = await memberError(e)
    if (status === 401) return void router.push({ name: 'login' })
    actionError.value = t('members.actionFailed')
  }
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
      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

      <!-- Fresh re-issued link to copy (cr-0080) — shown until the owner copies/dismisses it. -->
      <div v-if="reissueUrl" class="reissue-link">
        <p class="settings-muted">{{ t('members.copyLinkIntro') }}</p>
        <div class="invite-link-row">
          <InputText
            :model-value="reissueUrl"
            readonly
            class="invite-link-input"
            @focus="($event.target as HTMLInputElement).select()"
          />
          <Button
            :icon="reissueCopied ? 'pi pi-check' : 'pi pi-copy'"
            :aria-label="t('members.copyLink')"
            :title="reissueCopied ? t('members.copied') : t('members.copyLink')"
            @click="copyReissueLink"
          />
          <Button
            icon="pi pi-times"
            severity="secondary"
            outlined
            :aria-label="t('common.done')"
            :title="t('common.done')"
            @click="reissueUrl = ''"
          />
        </div>
      </div>

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

    <!-- Pending member invites (uc.spaces.manage-member-invites, cr-0082) — owner-only: invited-but-not-joined,
         each re-issuable (fresh copyable link) or revocable. Hidden entirely for a non-owner and when empty. -->
    <section v-if="isOwner && !loading && pendingInvites.length > 0" class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('members.pendingTitle') }}</h2>
          <p>{{ t('members.pendingSub') }}</p>
        </div>
      </div>

      <div class="veyq-list management-list">
        <div class="veyq-list management-list-items">
          <div v-for="invite in pendingInvites" :key="invite.inviteId" class="veyq-list-row invite-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ invite.email }}</strong>
              <div class="veyq-row-meta">
                <span class="veyq-badge warning">{{ t('members.statusInvited') }}</span>
              </div>
            </div>
            <div class="management-actions">
              <Button
                icon="pi pi-link"
                severity="secondary"
                outlined
                size="small"
                :aria-label="t('members.reissue')"
                :title="t('members.reissue')"
                @click="reissue(invite)"
              />
              <Button
                icon="pi pi-trash"
                severity="danger"
                outlined
                size="small"
                :aria-label="t('members.revoke')"
                :title="t('members.revoke')"
                @click="revoke(invite)"
              />
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
.reissue-link {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.invite-link-row {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.invite-link-input {
  flex: 1;
  min-width: 0;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.85em;
}
</style>
