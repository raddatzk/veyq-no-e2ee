<script setup lang="ts">
// uc.admin.create-tenant (web, Slice A) — the Server Owner's tenant administration screen and the app's
// first authenticated content view. Lists tenants and, for PENDING ones, resends / reassigns / revokes the
// owner invite. Operator-blind: only operational metadata is shown — no content keys, no member identities.
// (Today the only account that can exist is the Server Owner, so every signed-in user reaches this; a
// role-aware gate arrives with member accounts. The backend enforces Server-Owner with a 403 regardless.)
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import CreateTenantDialog from '../features/admin/createTenant/CreateTenantDialog.vue'
import {
  listTenants,
  reassignInvite,
  resendInvite,
  revokeTenant,
  tenantError,
  type TenantSummary,
} from '../features/admin/createTenant/tenants'

const { t } = useI18n()
const router = useRouter()

const tenants = ref<TenantSummary[]>([])
const loading = ref(true)
const loadError = ref<'' | 'notAuthorized' | 'failed'>('')
const createOpen = ref(false)
const notice = ref('')
const actionError = ref('')

const reassignOpen = ref(false)
const reassignId = ref('')
const reassignEmail = ref('')
const reassignBusy = ref(false)

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

function resend(id: string) {
  return runAction(() => resendInvite(id), 'tenant.resent')
}

function revoke(id: string) {
  if (!window.confirm(t('tenant.revokeConfirm'))) return
  return runAction(() => revokeTenant(id), 'tenant.revoked')
}

function openReassign(id: string) {
  reassignId.value = id
  reassignEmail.value = ''
  reassignOpen.value = true
}

async function submitReassign() {
  if (reassignEmail.value.trim().length === 0) return
  reassignBusy.value = true
  await runAction(() => reassignInvite(reassignId.value, reassignEmail.value.trim()), 'tenant.reassigned')
  reassignBusy.value = false
  reassignOpen.value = false
}
</script>

<template>
  <Card class="tenants-card">
    <template #title>{{ t('tenant.manageTitle') }}</template>
    <template #subtitle>{{ t('tenant.manageSub') }}</template>
    <template #content>
      <div class="toolbar">
        <Button :label="t('tenant.create')" icon="pi pi-plus" @click="createOpen = true" />
        <Button
          :label="t('login.signOut')"
          icon="pi pi-sign-out"
          severity="secondary"
          text
          @click="router.push({ name: 'login' })"
        />
      </div>

      <Message v-if="notice" severity="success" :closable="false">{{ notice }}</Message>
      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

      <p v-if="loading" class="muted">{{ t('tenant.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="warn" :closable="false">
        {{ t('tenant.notAuthorized') }}
      </Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">
        {{ t('tenant.loadFailed') }}
      </Message>
      <p v-else-if="tenants.length === 0" class="muted">{{ t('tenant.empty') }}</p>

      <ul v-else class="tenant-list">
        <li v-for="tenant in tenants" :key="tenant.tenantId" class="tenant-row">
          <div class="tenant-main">
            <span class="tenant-name">{{ tenant.name }}</span>
            <span class="tenant-domain">{{ tenant.domain }}</span>
            <span class="tenant-owner">{{ tenant.ownerEmail }}</span>
          </div>
          <div class="tenant-meta">
            <span class="status" :class="`status--${tenant.status.toLowerCase()}`">
              {{ tenant.status === 'PENDING' ? t('tenant.statusPending') : t('tenant.statusActive') }}
            </span>
            <div v-if="tenant.status === 'PENDING'" class="row-actions">
              <Button :label="t('tenant.resend')" size="small" severity="secondary" text @click="resend(tenant.tenantId)" />
              <Button :label="t('tenant.reassign')" size="small" severity="secondary" text @click="openReassign(tenant.tenantId)" />
              <Button :label="t('tenant.revoke')" size="small" severity="danger" text @click="revoke(tenant.tenantId)" />
            </div>
          </div>
        </li>
      </ul>
    </template>
  </Card>

  <CreateTenantDialog v-model:visible="createOpen" @created="onCreated" />

  <Dialog v-model:visible="reassignOpen" :header="t('tenant.reassignTitle')" modal>
    <form class="veyq-form" @submit.prevent="submitReassign">
      <label>
        {{ t('tenant.ownerEmail') }}
        <InputText v-model="reassignEmail" type="email" autocomplete="email" required />
      </label>
      <div class="dialog-actions">
        <Button type="button" :label="t('common.cancel')" severity="secondary" outlined @click="reassignOpen = false" />
        <Button
          type="submit"
          :label="t('tenant.reassignSubmit')"
          :loading="reassignBusy"
          :disabled="reassignEmail.trim().length === 0"
        />
      </div>
    </form>
  </Dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
.muted {
  opacity: 0.7;
}
.tenant-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.tenant-row {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 0.6rem;
  padding: 0.75rem 1rem;
  border: 1px solid var(--p-content-border-color, rgba(255, 255, 255, 0.12));
  border-radius: 0.5rem;
}
.tenant-main {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  min-width: 0;
}
.tenant-name {
  font-weight: 600;
}
.tenant-domain,
.tenant-owner {
  font-size: 0.85em;
  opacity: 0.75;
  overflow-wrap: anywhere;
}
.tenant-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.status {
  font-size: 0.75em;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 0.15rem 0.5rem;
  border-radius: 1rem;
  border: 1px solid currentColor;
}
.status--pending {
  color: var(--p-amber-400, #fbbf24);
}
.status--active {
  color: var(--p-green-400, #4ade80);
}
.row-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 0.25rem;
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.5rem;
}
</style>
