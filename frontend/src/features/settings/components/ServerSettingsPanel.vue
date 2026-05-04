<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import Select from 'primevue/select'
import { tenants as loadTenants } from '../../../api/tenantClient'
import type { TenantSummary } from '../../../authState'
import { getErrorMessage } from '../../../shared/utils/errors'
import {
  createManagedUser,
  getManagedUsers,
  getServerSettings,
  resendManagedUserInvitation,
  updateManagedUserRole,
  type ManagedUserSummary,
  type ServerRole,
  type ServerSettingsResponse
} from '../api'

const settings = ref<ServerSettingsResponse | null>(null)
const users = ref<ManagedUserSummary[]>([])
const tenants = ref<TenantSummary[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')
const createDialogVisible = ref(false)
const newDisplayName = ref('')
const newEmail = ref('')
const newRole = ref<ServerRole>('ServerMember')
const newTenantId = ref<number | null>(null)

const roleOptions = [
  { label: 'Admin', value: 'ServerOwner' },
  { label: 'Member', value: 'ServerMember' }
]

const tenantOptions = computed(() =>
  tenants.value.map((tenant) => ({
    label: `${tenant.displayName} (${tenant.domain})`,
    value: tenant.id
  }))
)

onMounted(loadSettings)

async function loadSettings() {
  loading.value = true
  error.value = ''
  try {
    const [nextSettings, nextUsers, nextTenants] = await Promise.all([
      getServerSettings(),
      getManagedUsers(),
      loadTenants()
    ])
    settings.value = nextSettings
    users.value = nextUsers.users
    tenants.value = nextTenants
  } catch (caught) {
    settings.value = null
    error.value = getErrorMessage(caught, 'Server-Einstellungen konnten nicht geladen werden.')
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  newDisplayName.value = ''
  newEmail.value = ''
  newRole.value = 'ServerMember'
  newTenantId.value = tenants.value[0]?.id ?? null
  message.value = ''
  error.value = ''
  createDialogVisible.value = true
}

async function createUser() {
  if (newTenantId.value === null) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const user = await createManagedUser({
      displayName: newDisplayName.value,
      email: newEmail.value,
      serverRole: newRole.value,
      tenantId: newTenantId.value
    })
    upsertUser(user)
    createDialogVisible.value = false
    message.value = 'User wurde erstellt und eingeladen.'
  } catch (caught) {
    error.value = getErrorMessage(caught, 'User konnte nicht erstellt werden.')
  } finally {
    saving.value = false
  }
}

async function changeRole(user: ManagedUserSummary, role: ServerRole) {
  error.value = ''
  message.value = ''
  try {
    upsertUser(await updateManagedUserRole(user.userId, role))
    message.value = 'Rolle wurde gespeichert.'
  } catch (caught) {
    error.value = getErrorMessage(caught, 'Rolle konnte nicht gespeichert werden.')
  }
}

async function resendInvitation(user: ManagedUserSummary) {
  error.value = ''
  message.value = ''
  try {
    upsertUser(await resendManagedUserInvitation(user.userId))
    message.value = 'Einladung wurde erneut gesendet.'
  } catch (caught) {
    error.value = getErrorMessage(caught, 'Einladung konnte nicht gesendet werden.')
  }
}

function upsertUser(user: ManagedUserSummary) {
  const index = users.value.findIndex((item) => item.userId === user.userId)
  if (index === -1) {
    users.value = [...users.value, user].sort((left, right) => left.email.localeCompare(right.email))
  } else {
    users.value.splice(index, 1, user)
  }
}

function roleLabel(role: ServerRole) {
  return role === 'ServerOwner' ? 'Admin' : 'Member'
}
</script>

<template>
  <section class="settings-panel">
    <h1>Server</h1>
    <p>Server-Einstellungen</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div v-else-if="loading" class="explorer-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Server-Einstellungen werden geladen...</span>
    </div>
    <template v-else-if="settings?.userManagementAvailable">
      <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>
      <div class="settings-toolbar">
        <Button label="User erstellen" icon="pi pi-user-plus" @click="openCreateDialog" />
      </div>
      <div class="settings-table">
        <div class="settings-table-row settings-table-head">
          <span>User</span>
          <span>Status</span>
          <span>Tenant</span>
          <span>Rolle</span>
          <span></span>
        </div>
        <div v-for="user in users" :key="user.userId" class="settings-table-row">
          <div>
            <strong>{{ user.displayName }}</strong>
            <span>{{ user.email }}</span>
          </div>
          <span>{{ user.status === 'Active' ? 'Aktiv' : 'Ausstehend' }}</span>
          <span>{{ user.tenants.map((tenant) => tenant.displayName).join(', ') }}</span>
          <Select
            :model-value="user.serverRole"
            :options="roleOptions"
            option-label="label"
            option-value="value"
            aria-label="Rolle"
            @update:model-value="changeRole(user, $event)"
          />
          <Button
            v-if="user.status === 'Pending'"
            type="button"
            label="Erneut einladen"
            icon="pi pi-send"
            severity="secondary"
            outlined
            @click="resendInvitation(user)"
          />
          <span v-else>{{ roleLabel(user.serverRole) }}</span>
        </div>
      </div>
    </template>

    <Dialog v-model:visible="createDialogVisible" header="User erstellen" modal class="tenant-dialog">
      <form class="form" @submit.prevent="createUser">
        <label>
          Anzeigename
          <InputText v-model="newDisplayName" autocomplete="name" required />
        </label>
        <label>
          E-Mail
          <InputText v-model="newEmail" type="email" autocomplete="email" required />
        </label>
        <label>
          Tenant
          <Select
            v-model="newTenantId"
            :options="tenantOptions"
            option-label="label"
            option-value="value"
            required
          />
        </label>
        <label>
          Rolle
          <Select
            v-model="newRole"
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
            @click="createDialogVisible = false"
          />
        </div>
      </form>
    </Dialog>
  </section>
</template>
