<script setup lang="ts">
// uc.spaces.add-member (web, cr-0077) — the "Members" rubric of the tenant settings. Lists the active tenant's
// members (name/email + role + granted/awaiting-access status) and, for the tenant OWNER, opens the invite-by-
// email dialog. Markup + classes mirror the create-tenant admin panel (settings-section-header + veyq-list-row +
// veyq-badge). Operator-blind: only operational metadata is shown — pubkeys/roles/grant-present, never a key.
// Admission (invite) is owner-only (the backend 403s otherwise); the directory is any key-holder's.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import InviteMemberDialog from './InviteMemberDialog.vue'
import {
  listMemberInvites,
  listScopeMembers,
  reissueMemberInvite,
  removeTenantMember,
  revokeMemberInvite,
  type PendingMemberInvite,
  type ScopeMember,
} from './members'
import { listMyTenants } from './scopes'
import { activeSpace, activeScopeKey } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'
import { buildInviteLink, copyText } from '../../shared/inviteLink'
import { unsealName, type MemberName } from '../account/profile/profile'
import { isUnauthorized } from '../../shared/httpError'
import { useMemberDirectory } from './useMemberDirectory'
import { useToast } from '../../shared/state/useToast'
import ChangedKeyReviewDialog from './ChangedKeyReviewDialog.vue'

const { t } = useI18n()
const toast = useToast()

// Locally-decrypted real names (first+last) keyed by userId — the sealed blob opened with K_tenant on-device.
const names = ref<Record<string, MemberName | null>>({})
const pendingInvites = ref<PendingMemberInvite[]>([])
const actionError = ref('')
const inviteOpen = ref(false)

// After a re-issue, show the fresh invite link to copy (cr-0080) — the owner hands it off again, no mailer.
const reissueUrl = ref('')
const reissueCopied = ref(false)

const inTenant = computed(() => activeSpace.kind === ScopeKind.Tenant && activeSpace.scopeId !== null)

// Shared directory core (members/trust/review/isOwner/load, cr-0156). The load body is Tenant-specific: fetch
// the roster, decrypt each real name with K_tenant (operator-blind, on-device), then read the owner-only invite
// list — and resolve ownership from the tenant role.
const { members, isOwner, trust, reviewTarget, reviewOpen, loading, loadError, refreshTrust, openReview, reload, on401 } =
  useMemberDirectory(async () => {
    if (!inTenant.value || !activeSpace.scopeId || !activeSpace.tenantId) throw new Error('no-active-tenant')
    const rows = await listScopeMembers(activeSpace.scopeId)
    // A grant-less "awaiting access" member has no sealed name yet → null (the row shows only the @username).
    const kTenant = activeScopeKey()
    names.value = kTenant ? Object.fromEntries(rows.map((m) => [m.userId, unsealName(kTenant, m.sealedName)])) : {}
    const mine = (await listMyTenants()).find((tn) => tn.tenantId === activeSpace.tenantId)
    const owner = mine?.role === 'OWNER'
    // Only the owner may read the pending-invite list (the backend 403s any non-owner).
    pendingInvites.value = owner ? await listMemberInvites(activeSpace.tenantId) : []
    return { rows, isOwner: owner }
  })

/** The member's decrypted "First Last", or '' when not yet sealed (awaiting access) / unreadable. */
function fullName(userId: string): string {
  const n = names.value[userId]
  return n ? `${n.firstName} ${n.lastName}`.trim() : ''
}

async function onInvited() {
  toast.success(t('members.inviteSent'))
  await reload()
}

async function reissue(invite: PendingMemberInvite) {
  actionError.value = ''
  reissueUrl.value = ''
  reissueCopied.value = false
  try {
    const state = await reissueMemberInvite(activeSpace.tenantId!, invite.inviteId)
    reissueUrl.value = buildInviteLink(state.bootstrapSecret) // same host — the owner invites into their tenant
    toast.success(t('members.inviteReissued'))
    await reload()
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('members.actionFailed')
  }
}

async function copyReissueLink() {
  reissueCopied.value = await copyText(reissueUrl.value)
}

// Remove a member from the tenant (uc.spaces.remove-tenant-member, cr-0091) — owner-only, inline confirm. The
// server runs the whole cascade; here we just fire it and refresh the directory (the ex-member drops out). Not
// offered on OWNER rows (owners are managed via co-ownership, not removed from the roster here).
async function remove(member: ScopeMember) {
  if (!window.confirm(t('members.removeConfirm'))) return
  actionError.value = ''
  reissueUrl.value = ''
  try {
    await removeTenantMember(activeSpace.tenantId!, member.userId)
    toast.success(t('members.removed'))
    await reload()
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('members.actionFailed')
  }
}

async function revoke(invite: PendingMemberInvite) {
  if (!window.confirm(t('members.revokeConfirm'))) return
  actionError.value = ''
  reissueUrl.value = ''
  try {
    await revokeMemberInvite(activeSpace.tenantId!, invite.inviteId)
    toast.success(t('members.inviteRevoked'))
    await reload()
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('members.actionFailed')
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
              <strong class="veyq-row-title">{{ fullName(member.userId) || '@' + member.username }}</strong>
              <span class="veyq-row-subtitle">
                <span v-if="fullName(member.userId)">@{{ member.username }} · </span>
                <span v-if="member.email">{{ member.email }} · </span>{{ member.role }}
              </span>
              <div class="veyq-row-meta">
                <span class="veyq-badge" :class="member.hasGrant ? 'success' : 'warning'">
                  {{ member.hasGrant ? t('members.statusActive') : t('members.statusAwaiting') }}
                </span>
                <!-- Changed-identity warning (cr-0099): the ONLY trust surface — the directory key differs from the
                     pin (a rotation or an operator substitution). Verified/recognized are intentionally not shown. -->
                <span v-if="trust[member.userId] === 'changed'" class="veyq-badge changed">
                  <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>{{ t('verify.badgeChanged') }}
                </span>
              </div>
            </div>
            <div class="management-actions">
              <!-- Review a changed key (cr-0099), available to ANY member: compare expected-vs-current out-of-band,
                   then accept (re-pin) or refuse (leave blocked). -->
              <Button
                v-if="trust[member.userId] === 'changed'"
                :label="t('verify.review')"
                icon="pi pi-exclamation-triangle"
                severity="warn"
                text
                size="small"
                @click="openReview(member)"
              />
              <Button
                v-if="isOwner && member.role !== 'OWNER'"
                icon="pi pi-user-minus"
                severity="danger"
                outlined
                size="small"
                :aria-label="t('members.remove')"
                :title="t('members.remove')"
                @click="remove(member)"
              />
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

    <!-- Review a changed identity key (cr-0099) — the only verify surface until scope-wide strict-mode (cr-0100). -->
    <ChangedKeyReviewDialog v-if="reviewTarget" v-model:visible="reviewOpen" :member="reviewTarget" @accepted="refreshTrust" />
  </section>
</template>

<style scoped>

.reissue-link {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

</style>
