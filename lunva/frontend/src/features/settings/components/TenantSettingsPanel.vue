<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
  createManagedUser,
  getTenantUsers,
  inviteTenantUser,
  reinviteTenantUser,
  searchTenantUsers,
  updateTenantUserRole,
  type TenantRole,
  type TenantUserSearchSummary,
  type TenantUserSummary
} from '../api'

const props = defineProps<{
  tenantName: string
}>()

const router = useRouter()
const { authState, setTenants } = useAuthState()
const users = ref<TenantUserSummary[]>([])
const searchResults = ref<TenantUserSearchSummary[]>([])
const loading = ref(false)
const saving = ref(false)
const searching = ref(false)
const error = ref('')
const message = ref('')
const query = ref('')
const createDialogVisible = ref(false)
const inviteDialogVisible = ref(false)
const deleteDialogVisible = ref(false)
const deleteConfirmation = ref('')
const newEmail = ref('')
const newTenantRole = ref<TenantRole>('TenantMember')
const selectedUser = ref<TenantUserSearchSummary | null>(null)
const selectedTenantRole = ref<TenantRole>('TenantMember')
let searchTimeout: ReturnType<typeof setTimeout> | null = null
let searchRun = 0

const trimmedQuery = computed(() => query.value.trim())
const canSuggest = computed(() => trimmedQuery.value.length >= 3)
const isEmailQuery = computed(() => EMAIL_REGEX.test(trimmedQuery.value))
const showSuggestions = computed(
  () => canSuggest.value && (searching.value || searchResults.value.length > 0 || trimmedQuery.value.length >= 3)
)
const tenantDisplayName = computed(
  () => authState.tenants.find((tenant) => tenant.name === props.tenantName)?.displayName || props.tenantName
)
const canDeleteTenant = computed(() =>
  users.value.some(
    (user) =>
      user.userId === authState.user?.userId &&
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
onBeforeUnmount(clearSearchTimeout)
watch(() => props.tenantName, load)
watch(query, scheduleSearch)

async function load() {
  loading.value = true
  error.value = ''
  message.value = ''
  searchResults.value = []
  try {
    users.value = await getTenantUsers(props.tenantName)
  } catch (caught) {
    error.value = getErrorMessage(caught, 'Mitglieder konnten nicht geladen werden.')
  } finally {
    loading.value = false
  }
}

function scheduleSearch() {
  clearSearchTimeout()
  message.value = ''
  if (!canSuggest.value) {
    searchResults.value = []
    searching.value = false
    return
  }
  searchTimeout = setTimeout(searchUsers, 250)
}

async function searchUsers() {
  const currentRun = ++searchRun
  searching.value = true
  error.value = ''
  try {
    const results = await searchTenantUsers(props.tenantName, trimmedQuery.value)
    if (currentRun === searchRun) {
      searchResults.value = results
    }
  } catch (caught) {
    if (currentRun === searchRun) {
      error.value = getErrorMessage(caught, 'User-Suche ist fehlgeschlagen.')
      searchResults.value = []
    }
  } finally {
    if (currentRun === searchRun) {
      searching.value = false
    }
  }
}

function clearSearchTimeout() {
  if (searchTimeout) {
    clearTimeout(searchTimeout)
    searchTimeout = null
  }
}

function openCreateDialog() {
  newEmail.value = isEmailQuery.value ? trimmedQuery.value : ''
  newTenantRole.value = 'TenantMember'
  error.value = ''
  message.value = ''
  createDialogVisible.value = true
}

function openInviteDialog(user: TenantUserSearchSummary) {
  selectedUser.value = user
  selectedTenantRole.value = 'TenantMember'
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

async function createUser() {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const created = await createManagedUser({ email: newEmail.value })
    upsertUser(await inviteTenantUser(props.tenantName, created.userId, newTenantRole.value))
    closeCreateDialog()
    message.value = 'User wurde erstellt und eingeladen.'
  } catch (caught) {
    if (isApiStatus(caught, 409)) {
      await inviteDuplicateEmailUser()
    } else {
      error.value = getErrorMessage(caught, 'User konnte nicht erstellt werden.')
    }
  } finally {
    saving.value = false
  }
}

async function inviteDuplicateEmailUser() {
  const results = await searchTenantUsers(props.tenantName, newEmail.value.trim())
  if (results.length !== 1) {
    error.value = 'Der User existiert bereits oder ist schon Mitglied dieses Tenants.'
    return
  }

  try {
    upsertUser(await inviteTenantUser(props.tenantName, results[0].userId, newTenantRole.value))
    closeCreateDialog()
    message.value = 'Bestehender User wurde eingeladen.'
  } catch (caught) {
    if (isApiStatus(caught, 409)) {
      error.value = 'Der User ist bereits Mitglied oder hat eine offene Einladung in diesem Tenant.'
    } else {
      error.value = getErrorMessage(caught, 'Bestehender User konnte nicht eingeladen werden.')
    }
  }
}

async function inviteSelectedUser() {
  if (!selectedUser.value) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const invited = await inviteTenantUser(
      props.tenantName,
      selectedUser.value.userId,
      selectedTenantRole.value
    )
    upsertUser(invited)
    searchResults.value = searchResults.value.filter((user) => user.userId !== invited.userId)
    inviteDialogVisible.value = false
    selectedUser.value = null
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
    upsertUser(await reinviteTenantUser(props.tenantName, user.userId))
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
    upsertUser(await updateTenantUserRole(props.tenantName, user.userId, role))
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

function closeCreateDialog() {
  createDialogVisible.value = false
  searchResults.value = searchResults.value.filter(
    (user) => !users.value.some((tenantUser) => tenantUser.userId === user.userId)
  )
}

function upsertUser(user: TenantUserSummary) {
  const index = users.value.findIndex((item) => item.userId === user.userId)
  if (index === -1) {
    users.value = [...users.value, user].sort((left, right) => left.email.localeCompare(right.email))
  } else {
    users.value.splice(index, 1, user)
  }
}

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
</script>

<template>
  <section class="settings-panel">
    <h1>Tenant</h1>
    <p>Mitglieder</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>

    <div v-if="loading" class="explorer-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Mitglieder werden geladen...</span>
    </div>

    <template v-else>
      <div class="settings-toolbar">
        <div class="form-inline tenant-search">
          <InputText v-model="query" placeholder="Name oder E-Mail suchen" />
          <ProgressSpinner v-if="searching" class="small-spinner" />
          <div v-if="showSuggestions" class="suggest-panel">
            <button type="button" class="suggest-row create-row" @click="openCreateDialog">
              <i class="pi pi-user-plus" aria-hidden="true"></i>
              <span>Neuen User anlegen</span>
              <small v-if="isEmailQuery">{{ trimmedQuery }}</small>
            </button>
            <button
              v-for="user in searchResults"
              :key="user.userId"
              type="button"
              class="suggest-row"
              @click="openInviteDialog(user)"
            >
              <strong>{{ user.displayName }}</strong>
              <span>{{ user.maskedEmail }}</span>
            </button>
          </div>
        </div>
        <Button label="User erstellen" icon="pi pi-user-plus" @click="openCreateDialog" />
      </div>

      <div class="settings-table">
        <div class="settings-table-row settings-table-head tenant-users-row">
          <span>User</span>
          <span>Status</span>
          <span>Rolle</span>
          <span></span>
        </div>
        <div v-for="user in users" :key="user.userId" class="settings-table-row tenant-users-row">
          <div>
            <strong>{{ user.displayName }}</strong>
            <span>{{ user.email }}</span>
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

    <Dialog v-model:visible="createDialogVisible" header="User erstellen" modal class="tenant-dialog">
      <form class="form" @submit.prevent="createUser">
        <label>
          E-Mail
          <InputText v-model="newEmail" type="email" autocomplete="email" required />
        </label>
        <label>
          Rolle
          <Select
            v-model="newTenantRole"
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

    <Dialog v-model:visible="inviteDialogVisible" header="User einladen" modal class="tenant-dialog">
      <form v-if="selectedUser" class="form" @submit.prevent="inviteSelectedUser">
        <div>
          <strong>{{ selectedUser.displayName }}</strong>
          <span>{{ selectedUser.maskedEmail }}</span>
        </div>
        <label>
          Rolle
          <Select
            v-model="selectedTenantRole"
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
