<script setup lang="ts">
// uc.admin.create-tenant (web, Slice A) — the "Manage tenants" rubric of the Server Settings page. Lists every
// tenant and, for a PENDING one, **edit**s the owner invite (reassign email / resend) or **delete**s (revokes)
// it. Markup + classes are the design source's ServerSettingsPanel user-management section verbatim
// (settings-section-header + the ManagementList veyq-list-row rows + management-actions); only the data layer is
// ours. Operator-blind: only operational metadata is shown — no content keys, no member identities. The backend
// enforces Server-Owner with a 403 regardless of what the client renders.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import CreateTenantDialog from './CreateTenantDialog.vue'
import {
  listTenants,
  reassignInvite,
  resendInvite,
  revokeTenant,
  tenantError,
  type TenantSummary,
} from './tenants'
import { buildInviteLink, copyText } from '../../../shared/inviteLink'

const { t } = useI18n()
const router = useRouter()

const tenants = ref<TenantSummary[]>([])
const loading = ref(true)
const loadError = ref<'' | 'notAuthorized' | 'failed'>('')
const notice = ref('')
const actionError = ref('')
const createOpen = ref(false)

// The edit dialog folds the two PENDING-invite operations together: change the owner email (reassign) and
// resend the invite to the current email.
const editOpen = ref(false)
const editId = ref('')
const editEmail = ref('')
const editDomain = ref('')
const editBusy = ref(false)
// After a resend/reassign, show the fresh invite link to copy (cr-0080 — the owner hands it off, no mailer).
const editInviteUrl = ref('')
const editCopied = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    tenants.value = await listTenants()
  } catch (e) {
    const { status } = await tenantError(e)
    if (status === 401) return void router.push({ name: 'login' })
    loadError.value = status === 403 ? 'notAuthorized' : 'failed'
  } finally {
    loading.value = false
  }
}

async function onCreated() {
  notice.value = t('tenant.inviteSent')
  await load()
}

async function runAction(action: () => Promise<unknown>, successKey: string) {
  actionError.value = ''
  notice.value = ''
  try {
    await action()
    notice.value = t(successKey)
    await load()
  } catch (e) {
    const { status } = await tenantError(e)
    if (status === 401) return void router.push({ name: 'login' })
    actionError.value = t('tenant.actionFailed')
  }
}

function openEdit(tenant: TenantSummary) {
  editId.value = tenant.tenantId
  editEmail.value = tenant.ownerEmail
  editDomain.value = tenant.domain
  editInviteUrl.value = ''
  editCopied.value = false
  editOpen.value = true
}

async function copyEditLink() {
  editCopied.value = await copyText(editInviteUrl.value)
}

function revoke(id: string) {
  if (!window.confirm(t('tenant.revokeConfirm'))) return
  return runAction(() => revokeTenant(id), 'tenant.revoked')
}

async function resendFromEdit() {
  await mintFromEdit(() => resendInvite(editId.value), 'tenant.resent')
}

async function submitReassign() {
  if (editEmail.value.trim().length === 0) return
  await mintFromEdit(() => reassignInvite(editId.value, editEmail.value.trim()), 'tenant.reassigned')
}

// Resend/reassign both re-mint the invite → capture the fresh secret and show its link (the dialog stays open
// so the owner can copy it); reloads the list behind it. 401 → re-login, other failures → an inline error.
async function mintFromEdit(action: () => Promise<{ bootstrapSecret: string }>, successKey: string) {
  editBusy.value = true
  actionError.value = ''
  try {
    const state = await action()
    notice.value = t(successKey)
    editInviteUrl.value = buildInviteLink(state.bootstrapSecret, editDomain.value)
    await load()
  } catch (e) {
    const { status } = await tenantError(e)
    if (status === 401) return void router.push({ name: 'login' })
    actionError.value = t('tenant.actionFailed')
  } finally {
    editBusy.value = false
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('tenant.manageTitle') }}</h2>
          <p>{{ t('tenant.manageSub') }}</p>
        </div>
        <Button :label="t('tenant.create')" icon="pi pi-plus" size="small" @click="createOpen = true" />
      </div>

      <Message v-if="notice" severity="success" :closable="false">{{ notice }}</Message>
      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

      <p v-if="loading" class="settings-muted">{{ t('tenant.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="warn" :closable="false">
        {{ t('tenant.notAuthorized') }}
      </Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">
        {{ t('tenant.loadFailed') }}
      </Message>
      <p v-else-if="tenants.length === 0" class="settings-muted">{{ t('tenant.empty') }}</p>

      <div v-else class="veyq-list management-list">
        <div class="veyq-list management-list-items">
          <div v-for="tenant in tenants" :key="tenant.tenantId" class="veyq-list-row tenant-admin-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ tenant.name }}</strong>
              <span class="veyq-row-subtitle">{{ tenant.domain }} · {{ tenant.ownerEmail }}</span>
              <div class="veyq-row-meta">
                <span class="veyq-badge" :class="tenant.status === 'PENDING' ? 'warning' : 'success'">
                  {{ tenant.status === 'PENDING' ? t('tenant.statusPending') : t('tenant.statusActive') }}
                </span>
              </div>
            </div>
            <div v-if="tenant.status === 'PENDING'" class="management-actions">
              <Button
                icon="pi pi-pencil"
                severity="secondary"
                outlined
                size="small"
                :aria-label="t('tenant.edit')"
                :title="t('tenant.edit')"
                @click="openEdit(tenant)"
              />
              <Button
                icon="pi pi-trash"
                severity="danger"
                outlined
                size="small"
                :aria-label="t('tenant.revoke')"
                :title="t('tenant.revoke')"
                @click="revoke(tenant.tenantId)"
              />
            </div>
          </div>
        </div>
      </div>
    </section>

    <CreateTenantDialog v-model:visible="createOpen" @created="onCreated" />

    <Dialog v-model:visible="editOpen" :header="t('tenant.reassignTitle')" modal>
      <form v-if="!editInviteUrl" class="veyq-form" @submit.prevent="submitReassign">
        <label>
          {{ t('tenant.ownerEmail') }}
          <InputText v-model="editEmail" type="email" autocomplete="email" required />
        </label>
        <div class="veyq-dialog-action-row">
          <Button
            type="button"
            :label="t('tenant.resend')"
            icon="pi pi-send"
            severity="secondary"
            outlined
            :loading="editBusy"
            @click="resendFromEdit"
          />
          <Button
            type="submit"
            :label="t('tenant.reassignSubmit')"
            :loading="editBusy"
            :disabled="editEmail.trim().length === 0"
          />
        </div>
      </form>

      <div v-else class="veyq-form">
        <Message severity="success" :closable="false">{{ t('tenant.inviteReissued') }}</Message>
        <p class="settings-muted">{{ t('members.copyLinkIntro') }}</p>
        <div class="invite-link-row">
          <InputText :model-value="editInviteUrl" readonly class="invite-link-input" @focus="($event.target as HTMLInputElement).select()" />
          <Button :label="editCopied ? t('members.copied') : t('members.copyLink')" :icon="editCopied ? 'pi pi-check' : 'pi pi-copy'" @click="copyEditLink" />
        </div>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('common.done')" icon="pi pi-check" @click="editOpen = false" />
        </div>
      </div>
    </Dialog>
  </section>
</template>

<style scoped>
.settings-muted {
  margin: 0;
  color: var(--app-muted);
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
