<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import Select from 'primevue/select'
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

const roleOptions = [
  { label: 'Admin', value: 'TenantOwner' },
  { label: 'Member', value: 'TenantMember' }
]

onMounted(load)
watch(() => props.tenantName, load)

async function load() {
  loading.value = true
  error.value = ''
  message.value = ''
  try {
    users.value = await getTenantUsers(props.tenantName)
  } catch (caught) {
    error.value = getErrorMessage(caught, 'Mitglieder konnten nicht geladen werden.')
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
    message.value = 'Einladung wurde gesendet.'
  } catch (caught) {
    if (isApiStatus(caught, 409)) {
      error.value = 'Der User ist bereits Mitglied oder hat eine offene Einladung in diesem Tenant.'
    } else {
      error.value = getErrorMessage(caught, 'Einladung konnte nicht gesendet werden.')
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
    message.value = 'Einladung wurde erneut gesendet.'
  } catch (caught) {
    error.value = getErrorMessage(caught, 'Einladung konnte nicht erneut gesendet werden.')
  } finally {
    saving.value = false
  }
}

async function changeRole(user: TenantUserSummary, role: TenantRole) {
  error.value = ''
  message.value = ''
  try {
    upsertUser(await updateTenantUserRole(props.tenantName, user.user.userId, role))
    message.value = 'Rolle wurde gespeichert.'
  } catch (caught) {
    error.value = getErrorMessage(caught, 'Rolle konnte nicht gespeichert werden.')
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
    error.value = getErrorMessage(caught, 'Tenant konnte nicht gelöscht werden.')
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
    <h1>Tenant</h1>
    <p>Mitglieder</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>

    <div v-if="loading" class="memory-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Mitglieder werden geladen...</span>
    </div>

    <template v-else>
      <div class="settings-toolbar">
        <Button label="User einladen" icon="pi pi-user-plus" @click="openInviteDialog" />
      </div>

      <div class="settings-table">
        <div class="settings-table-row settings-table-head tenant-users-row">
          <span>User</span>
          <span>Status</span>
          <span>Rolle</span>
          <span></span>
        </div>
        <div v-for="user in users" :key="user.user.userId" class="settings-table-row tenant-users-row">
          <div>
            <strong>{{ user.user.displayName }}</strong>
            <span v-if="user.email">{{ user.email }}</span>
          </div>
          <span>{{ user.status === 'Active' ? 'Aktiv' : 'Ausstehend' }}</span>
          <Select
            :model-value="user.role"
            :options="roleOptions"
            option-label="label"
            option-value="value"
            aria-label="Tenant-Rolle"
            @update:model-value="changeRole(user, $event)"
          />
          <Button
            v-if="user.status === 'Pending'"
            label="Erneut einladen"
            icon="pi pi-send"
            severity="secondary"
            outlined
            :loading="saving"
            @click="reinviteUser(user)"
          />
          <span v-else></span>
        </div>
      </div>

      <div v-if="canDeleteTenant" class="button-row">
        <Button
          label="Tenant löschen"
          icon="pi pi-trash"
          severity="danger"
          outlined
          @click="openDeleteDialog"
        />
      </div>
    </template>

    <Dialog v-model:visible="inviteDialogVisible" header="User einladen" modal class="tenant-dialog">
      <form class="form" @submit.prevent="inviteUserByEmail">
        <label>
          E-Mail
          <InputText v-model="inviteEmail" type="email" autocomplete="email" required />
        </label>
        <label>
          Rolle
          <Select
            v-model="inviteTenantRole"
            :options="roleOptions"
            option-label="label"
            option-value="value"
          />
        </label>
        <div class="button-row">
          <Button type="submit" label="Einladen" icon="pi pi-send" :loading="saving" />
          <Button
            type="button"
            label="Abbrechen"
            severity="secondary"
            outlined
            @click="inviteDialogVisible = false"
          />
        </div>
      </form>
    </Dialog>

    <Dialog v-model:visible="deleteDialogVisible" header="Tenant löschen" modal class="tenant-dialog">
      <div class="form">
        <p>
          <strong>{{ tenantDisplayName }}</strong> wirklich rekursiv löschen?
        </p>
        <label>
          Display-Name zur Bestätigung
          <InputText v-model="deleteConfirmation" autocomplete="off" :placeholder="tenantDisplayName" />
        </label>
        <div class="button-row">
          <Button
            label="Löschen"
            icon="pi pi-trash"
            severity="danger"
            :disabled="!canConfirmDeleteTenant"
            :loading="saving"
            @click="submitDeleteTenant"
          />
          <Button
            type="button"
            label="Abbrechen"
            severity="secondary"
            outlined
            @click="deleteDialogVisible = false"
          />
        </div>
      </div>
    </Dialog>
  </section>
</template>
