<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import Select from 'primevue/select'
import ManagementList from '../../../shared/components/ManagementList.vue'
import { getErrorMessage, isApiStatus } from '../../../shared/utils/errors'
import { deleteTenant as deleteTenantApi, tenants as loadTenantSummaries } from '../../../api/tenantClient'
import { useAuthState } from '../../../shared/state/useAuthState'
import {
  getTenantUsers,
  inviteTenantUser,
  reinviteTenantUser,
  updateTenantUserRole,
  type TenantRole,
  type TenantUserSummary
} from '../api'

const props = defineProps<{
  tenantName: string
}>()

const router = useRouter()
const { t } = useI18n()
const { authState, setTenants } = useAuthState()
const users = ref<TenantUserSummary[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')
const inviteDialogVisible = ref(false)
const deleteDialogVisible = ref(false)
const deleteConfirmation = ref('')
const inviteEmail = ref('')
const inviteTenantRole = ref<TenantRole>('TenantMember')

const tenantDisplayName = computed(
  () => authState.tenants.find((tenant) => tenant.name === props.tenantName)?.displayName || props.tenantName
)
const canDeleteTenant = computed(() =>
  users.value.some(
    (user) =>
      user.user.userId === authState.user?.userId &&
      user.status === 'Active' &&
      user.role === 'TenantOwner'
  )
)
const canConfirmDeleteTenant = computed(
  () => deleteConfirmation.value.trim() === tenantDisplayName.value.trim() && !saving.value
)

const roleOptions = computed(() => [
  { label: t('tenant.roles.TenantOwner'), value: 'TenantOwner' },
  { label: t('tenant.roles.TenantMember'), value: 'TenantMember' }
])

onMounted(load)
watch(() => props.tenantName, load)

async function load() {
  loading.value = true
  error.value = ''
  message.value = ''
  try {
    users.value = await getTenantUsers(props.tenantName)
  } catch (caught) {
    error.value = getErrorMessage(caught, t('tenant.membersLoadFailed'))
  } finally {
    loading.value = false
  }
}

function openInviteDialog() {
  inviteEmail.value = ''
  inviteTenantRole.value = 'TenantMember'
  error.value = ''
  message.value = ''
  inviteDialogVisible.value = true
}

function openDeleteDialog() {
  deleteConfirmation.value = ''
  error.value = ''
  message.value = ''
  deleteDialogVisible.value = true
}

async function inviteUserByEmail() {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    upsertUser(await inviteTenantUser(props.tenantName, inviteEmail.value, inviteTenantRole.value))
    inviteDialogVisible.value = false
    message.value = t('tenant.inviteSent')
  } catch (caught) {
    if (isApiStatus(caught, 409)) {
      error.value = t('tenant.alreadyMember')
    } else {
      error.value = getErrorMessage(caught, t('tenant.inviteFailed'))
    }
  } finally {
    saving.value = false
  }
}

async function reinviteUser(user: TenantUserSummary) {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    upsertUser(await reinviteTenantUser(props.tenantName, user.user.userId))
    message.value = t('tenant.inviteResent')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('tenant.inviteResendFailed'))
  } finally {
    saving.value = false
  }
}

async function changeRole(user: TenantUserSummary, role: TenantRole) {
  error.value = ''
  message.value = ''
  try {
    upsertUser(await updateTenantUserRole(props.tenantName, user.user.userId, role))
    message.value = t('tenant.roleSaved')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('tenant.roleSaveFailed'))
  }
}

async function submitDeleteTenant() {
  if (!canConfirmDeleteTenant.value) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    await deleteTenantApi(props.tenantName)
    setTenants(await loadTenantSummaries())
    deleteDialogVisible.value = false
    await router.push('/')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('tenant.deleteFailed'))
  } finally {
    saving.value = false
  }
}

function upsertUser(user: TenantUserSummary) {
  const index = users.value.findIndex((item) => item.user.userId === user.user.userId)
  if (index === -1) {
    users.value = [...users.value, user].sort((left, right) =>
      userSortLabel(left).localeCompare(userSortLabel(right))
    )
  } else {
    users.value.splice(index, 1, user)
  }
}

function userSortLabel(user: TenantUserSummary): string {
  return user.email || user.user.displayName
}
</script>

<template>
  <section class="settings-panel">
    <h1>{{ t('tenant.title') }}</h1>
    <p>{{ t('tenant.members') }}</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>

    <div v-if="loading" class="memory-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('tenant.membersLoading') }}</span>
    </div>

    <template v-else>
      <div class="settings-toolbar">
        <Button :label="t('tenant.invite')" icon="pi pi-user-plus" @click="openInviteDialog" />
      </div>

      <ManagementList>
        <div v-for="user in users" :key="user.user.userId" class="management-row">
          <div class="management-row-main">
            <strong class="management-row-title">{{ user.user.displayName }}</strong>
            <span v-if="user.email" class="management-row-subtitle">{{ user.email }}</span>
            <div class="management-row-meta">
              <span
                class="management-badge"
                :class="user.status === 'Active' ? 'success' : 'warning'"
              >
                {{ user.status === 'Active' ? t('common.active') : t('common.pending') }}
              </span>
            </div>
          </div>
          <Select
            class="management-control"
            :model-value="user.role"
            :options="roleOptions"
            option-label="label"
            option-value="value"
            :aria-label="t('tenant.role')"
            @update:model-value="changeRole(user, $event)"
          />
          <div class="management-actions">
            <Button
              v-if="user.status === 'Pending'"
              icon="pi pi-send"
              severity="secondary"
              outlined
              size="small"
              :aria-label="t('tenant.inviteResend')"
              :title="t('tenant.inviteResend')"
              :loading="saving"
              @click="reinviteUser(user)"
            />
          </div>
        </div>
      </ManagementList>

      <div v-if="canDeleteTenant" class="button-row">
        <Button
          :label="t('tenant.delete')"
          icon="pi pi-trash"
          severity="danger"
          outlined
          @click="openDeleteDialog"
        />
      </div>
    </template>

    <Dialog v-model:visible="inviteDialogVisible" :header="t('tenant.invite')" modal class="tenant-dialog">
      <form class="form" @submit.prevent="inviteUserByEmail">
        <label>
          {{ t('common.email') }}
          <InputText v-model="inviteEmail" type="email" autocomplete="email" required />
        </label>
        <label>
          {{ t('common.role') }}
          <Select
            v-model="inviteTenantRole"
            :options="roleOptions"
            option-label="label"
            option-value="value"
          />
        </label>
        <div class="button-row">
          <Button type="submit" :label="t('tenant.invite')" icon="pi pi-send" :loading="saving" />
          <Button
            type="button"
            :label="t('common.cancel')"
            severity="secondary"
            outlined
            @click="inviteDialogVisible = false"
          />
        </div>
      </form>
    </Dialog>

    <Dialog v-model:visible="deleteDialogVisible" :header="t('tenant.delete')" modal class="tenant-dialog">
      <div class="form">
        <p>
          {{ t('tenant.deleteConfirm', { name: tenantDisplayName }) }}
        </p>
        <label>
          {{ t('settings.confirmationDisplayName') }}
          <InputText v-model="deleteConfirmation" autocomplete="off" :placeholder="tenantDisplayName" />
        </label>
        <div class="button-row">
          <Button
            :label="t('common.delete')"
            icon="pi pi-trash"
            severity="danger"
            :disabled="!canConfirmDeleteTenant"
            :loading="saving"
            @click="submitDeleteTenant"
          />
          <Button
            type="button"
            :label="t('common.cancel')"
            severity="secondary"
            outlined
            @click="deleteDialogVisible = false"
          />
        </div>
      </div>
    </Dialog>
  </section>
</template>
