<script setup lang="ts">
// uc.admin.create-tenant (web, cr-0087) — the per-tenant detail page of the Server settings: one tenant's status
// and, for a PENDING one, its owner-invite management (reassign email / resend / revoke). Addressed by tenantId
// (the Tenants collection in the settings rail routes here); mirrors the old TenantAdminPanel row + edit, scoped
// to a single tenant. Operator-blind: only operational metadata (name/domain/owner email/status). The backend
// enforces Server-Owner with a 403 regardless of what the client renders.
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { listTenants, reassignInvite, resendInvite, revokeTenant, tenantError, type TenantSummary } from './tenants'
import { buildInviteLink, copyText } from '../../../shared/inviteLink'

// Addressed by id (cr-0087) — the settings rail renders one panel per tenant; the tenantId watch re-loads on switch.
const props = defineProps<{ tenantId: string }>()

const { t } = useI18n()
const router = useRouter()

const tenant = ref<TenantSummary | null>(null)
const loading = ref(true)
const loadError = ref<'' | 'notAuthorized' | 'failed' | 'gone'>('')
const notice = ref('')
const actionError = ref('')

const email = ref('')
const busy = ref(false)
// After a resend/reassign, show the fresh invite link to copy (cr-0080 — the owner hands it off, no mailer).
const inviteUrl = ref('')
const copied = ref(false)

const isPending = computed(() => tenant.value?.status === 'PENDING')

onMounted(load)
watch(() => props.tenantId, load)

async function load() {
  loading.value = true
  loadError.value = ''
  notice.value = ''
  actionError.value = ''
  inviteUrl.value = ''
  try {
    const found = (await listTenants()).find((x) => x.tenantId === props.tenantId) ?? null
    tenant.value = found
    if (!found) return void (loadError.value = 'gone')
    email.value = found.ownerEmail
  } catch (e) {
    const { status } = await tenantError(e)
    if (status === 401) return void router.push({ name: 'login' })
    loadError.value = status === 403 ? 'notAuthorized' : 'failed'
  } finally {
    loading.value = false
  }
}

// Resend/reassign both re-mint the invite → capture the fresh secret + show its link; reloads the tenant behind it.
async function mint(action: () => Promise<{ bootstrapSecret: string }>, successKey: string) {
  if (!tenant.value) return
  busy.value = true
  actionError.value = ''
  try {
    const state = await action()
    notice.value = t(successKey)
    inviteUrl.value = buildInviteLink(state.bootstrapSecret, tenant.value.domain)
  } catch (e) {
    const { status } = await tenantError(e)
    if (status === 401) return void router.push({ name: 'login' })
    actionError.value = t('tenant.actionFailed')
  } finally {
    busy.value = false
  }
}

const resend = () => mint(() => resendInvite(props.tenantId), 'tenant.resent')
function reassign() {
  if (email.value.trim().length === 0) return
  return mint(() => reassignInvite(props.tenantId, email.value.trim()), 'tenant.reassigned')
}

async function revoke() {
  if (!window.confirm(t('tenant.revokeConfirm'))) return
  actionError.value = ''
  try {
    await revokeTenant(props.tenantId)
    await router.push('/settings') // the tenant is gone → drop back to the hub default
  } catch (e) {
    const { status } = await tenantError(e)
    if (status === 401) return void router.push({ name: 'login' })
    actionError.value = t('tenant.actionFailed')
  }
}

async function copyInviteLink() {
  copied.value = await copyText(inviteUrl.value)
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <p v-if="loading" class="settings-muted">{{ t('tenant.loading') }}</p>
    <Message v-else-if="loadError === 'notAuthorized'" severity="warn" :closable="false">{{ t('tenant.notAuthorized') }}</Message>
    <Message v-else-if="loadError === 'gone'" severity="warn" :closable="false">{{ t('tenant.empty') }}</Message>
    <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('tenant.loadFailed') }}</Message>

    <section v-else-if="tenant" class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ tenant.name }}</h2>
          <p>{{ tenant.domain }} · {{ tenant.ownerEmail }}</p>
        </div>
        <span class="veyq-badge" :class="isPending ? 'warning' : 'success'">
          {{ isPending ? t('tenant.statusPending') : t('tenant.statusActive') }}
        </span>
      </div>

      <Message v-if="notice" severity="success" :closable="false">{{ notice }}</Message>
      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

      <!-- A PENDING tenant's owner invite can be re-pointed (reassign), resent, or revoked; ACTIVE tenants are
           settled and carry no invite controls here. -->
      <template v-if="isPending">
        <form v-if="!inviteUrl" class="veyq-form" @submit.prevent="reassign">
          <label>
            {{ t('tenant.ownerEmail') }}
            <InputText v-model="email" type="email" autocomplete="email" required />
          </label>
          <div class="veyq-dialog-action-row">
            <Button type="button" :label="t('tenant.resend')" icon="pi pi-send" severity="secondary" outlined :loading="busy" @click="resend" />
            <Button type="submit" :label="t('tenant.reassignSubmit')" :loading="busy" :disabled="email.trim().length === 0" />
            <Button type="button" :label="t('tenant.revoke')" icon="pi pi-trash" severity="danger" outlined @click="revoke" />
          </div>
        </form>

        <div v-else class="veyq-form">
          <Message severity="success" :closable="false">{{ t('tenant.inviteReissued') }}</Message>
          <p class="settings-muted">{{ t('members.copyLinkIntro') }}</p>
          <div class="invite-link-row">
            <InputText :model-value="inviteUrl" readonly class="invite-link-input" @focus="($event.target as HTMLInputElement).select()" />
            <Button :icon="copied ? 'pi pi-check' : 'pi pi-copy'" :aria-label="t('members.copyLink')" :title="copied ? t('members.copied') : t('members.copyLink')" @click="copyInviteLink" />
            <Button icon="pi pi-times" severity="secondary" outlined :aria-label="t('common.done')" :title="t('common.done')" @click="inviteUrl = ''" />
          </div>
        </div>
      </template>
    </section>
  </section>
</template>

<style scoped>
</style>
