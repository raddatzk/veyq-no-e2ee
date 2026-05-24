<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import Select from 'primevue/select'
import ManagementList from '../../../shared/components/ManagementList.vue'
import {
  createGroup,
  deleteGroup as deleteGroupApi,
  getGroup,
  groups,
  updateGroup,
  type GroupMemberSummary,
  type GroupRole,
  type GroupSummary,
  type TenantGroup
} from '../../../api/groupClient'
import { toPermalink } from '../../../composables/usePermalink'
import { useAuthState } from '../../../shared/state/useAuthState'
import { getErrorMessage, isApiStatus } from '../../../shared/utils/errors'
import { getTenantUsers, type TenantUserSummary } from '../api'

const props = defineProps<{
  tenantName: string
  groupName?: string
}>()

const router = useRouter()
const { authState } = useAuthState()
const groupList = ref<TenantGroup[]>([])
const tenantUsers = ref<TenantUserSummary[]>([])
const selectedGroup = ref<GroupSummary | null>(null)
const loading = ref(false)
const detailLoading = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')
const createDialogVisible = ref(false)
const deleteDialogVisible = ref(false)
const deleteConfirmation = ref('')
const createDisplayName = ref('')
const createName = ref('')
const createPermalinkTouched = ref(false)
const displayName = ref('')
const name = ref('')
const permalinkTouched = ref(false)
const selectedUserId = ref<number | null>(null)
const selectedRole = ref<GroupRole>('GroupMember')
const editingInitialGroupName = ref('')

const roleOptions = [
  { label: 'Owner', value: 'GroupOwner' },
  { label: 'Member', value: 'GroupMember' }
]

const selectedMembers = computed(() => selectedGroup.value?.members || [])
const availableTenantUsers = computed(() => {
  const memberIds = new Set(selectedMembers.value.map((member) => member.user.userId))
  return tenantUsers.value
    .filter((user) => !memberIds.has(user.user.userId) && user.status === 'Active')
    .map((user) => ({
      label: user.email ? `${user.user.displayName} (${user.email})` : user.user.displayName,
      value: user.user.userId
    }))
})
const ownerCount = computed(
  () => selectedMembers.value.filter((member) => member.role === 'GroupOwner').length
)
const canManageSelectedGroup = computed(() =>
  selectedGroup.value ? isCurrentUserGroupOwner(selectedGroup.value) : false
)
const showDetail = computed(() => Boolean(selectedGroup.value || props.groupName))
const canConfirmDeleteGroup = computed(
  () =>
    Boolean(selectedGroup.value) &&
    deleteConfirmation.value.trim() === selectedGroup.value!.displayName.trim() &&
    !saving.value
)

watch(displayName, (value) => {
  if (!permalinkTouched.value) name.value = toPermalink(value)
})

watch(createDisplayName, (value) => {
  if (!createPermalinkTouched.value) createName.value = toPermalink(value)
})

watch(() => props.tenantName, load, { immediate: false })
watch(() => props.groupName, load, { immediate: false })
onMounted(load)

async function load() {
  if (!props.tenantName) return
  loading.value = true
  error.value = ''
  message.value = ''
  try {
    groupList.value = await groups(props.tenantName)
    tenantUsers.value = []
    if (props.groupName) {
      await openGroup(props.groupName)
    } else {
      selectedGroup.value = null
      resetEditor()
    }
  } catch (caught) {
    error.value = getErrorMessage(caught, 'Gruppen konnten nicht geladen werden.')
  } finally {
    loading.value = false
  }
}

async function openGroup(groupName: string) {
  detailLoading.value = true
  error.value = ''
  message.value = ''
  try {
    const group = await getGroup(props.tenantName, groupName)
    selectedGroup.value = group
    editingInitialGroupName.value = group.name
    displayName.value = group.displayName
    name.value = group.name
    permalinkTouched.value = false
    selectedUserId.value = null
    selectedRole.value = 'GroupMember'
    if (isCurrentUserGroupOwner(group)) {
      await loadTenantUsers()
    } else {
      tenantUsers.value = []
    }
  } catch (caught) {
    selectedGroup.value = null
    error.value = getErrorMessage(caught, 'Gruppe konnte nicht geladen werden.')
  } finally {
    detailLoading.value = false
  }
}

function openCreateDialog() {
  createDisplayName.value = ''
  createName.value = ''
  createPermalinkTouched.value = false
  error.value = ''
  message.value = ''
  createDialogVisible.value = true
}

function openDeleteDialog() {
  deleteConfirmation.value = ''
  error.value = ''
  message.value = ''
  deleteDialogVisible.value = true
}

function updatePermalink(value: string | undefined) {
  permalinkTouched.value = true
  name.value = toPermalink(value || '')
}

function updateCreatePermalink(value: string | undefined) {
  createPermalinkTouched.value = true
  createName.value = toPermalink(value || '')
}

async function submitCreate() {
  const groupName = createName.value.trim()
  const groupDisplayName = createDisplayName.value.trim()
  if (!groupName || !groupDisplayName) return

  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const created = await createGroup(props.tenantName, groupName, groupDisplayName)
    upsertGroup(created)
    createDialogVisible.value = false
    selectedGroup.value = created
    editingInitialGroupName.value = created.name
    displayName.value = created.displayName
    name.value = created.name
    permalinkTouched.value = false
    await loadTenantUsers()
    message.value = 'Gruppe wurde erstellt.'
  } catch (caught) {
    if (isApiStatus(caught, 409)) {
      error.value = 'Eine Gruppe mit diesem Permalink existiert bereits.'
    } else {
      error.value = getErrorMessage(caught, 'Gruppe konnte nicht erstellt werden.')
    }
  } finally {
    saving.value = false
  }
}

function addMember() {
  if (!canManageSelectedGroup.value || !selectedGroup.value || selectedUserId.value === null) return
  const userId = Number(selectedUserId.value)
  const user = tenantUsers.value.find((tenantUser) => tenantUser.user.userId === userId)
  if (!user) return

  selectedGroup.value = {
    ...selectedGroup.value,
    members: [
      ...selectedGroup.value.members,
      {
        user: {
          userId: user.user.userId,
          displayName: user.user.displayName,
          permalink: user.user.permalink
        },
        role: selectedRole.value
      }
    ].sort((left, right) => left.user.displayName.localeCompare(right.user.displayName))
  }
  selectedUserId.value = null
  selectedRole.value = 'GroupMember'
}

function changeRole(member: GroupMemberSummary, role: GroupRole) {
  if (!canManageSelectedGroup.value || !selectedGroup.value) return
  if (member.role === 'GroupOwner' && role !== 'GroupOwner' && ownerCount.value <= 1) {
    error.value = 'Mindestens ein Owner muss in der Gruppe bleiben.'
    return
  }
  selectedGroup.value = {
    ...selectedGroup.value,
    members: selectedGroup.value.members.map((item) =>
      item.user.userId === member.user.userId ? { ...item, role } : item
    )
  }
}

function removeMember(member: GroupMemberSummary) {
  if (!canManageSelectedGroup.value || !selectedGroup.value) return
  if (member.role === 'GroupOwner' && ownerCount.value <= 1) {
    error.value = 'Mindestens ein Owner muss in der Gruppe bleiben.'
    return
  }
  selectedGroup.value = {
    ...selectedGroup.value,
    members: selectedGroup.value.members.filter((item) => item.user.userId !== member.user.userId)
  }
}

async function saveGroup() {
  if (!canManageSelectedGroup.value || !selectedGroup.value) return
  const groupName = name.value.trim()
  const groupDisplayName = displayName.value.trim()
  if (!groupName || !groupDisplayName) return

  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const saved = await updateGroup(props.tenantName, editingInitialGroupName.value, {
      name: groupName,
      displayName: groupDisplayName,
      members: new Set(
        selectedGroup.value.members.map((member) => ({
          id: member.user.userId,
          role: member.role || 'GroupMember'
        }))
      )
    })
    selectedGroup.value = saved
    editingInitialGroupName.value = saved.name
    upsertGroup(saved)
    message.value = 'Gruppe wurde gespeichert.'
  } catch (caught) {
    error.value = getErrorMessage(caught, 'Gruppe konnte nicht gespeichert werden.')
  } finally {
    saving.value = false
  }
}

async function submitDeleteGroup() {
  if (!selectedGroup.value || !canConfirmDeleteGroup.value) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const deletedGroupName = selectedGroup.value.name
    await deleteGroupApi(props.tenantName, deletedGroupName)
    groupList.value = groupList.value.filter((group) => group.name !== deletedGroupName)
    selectedGroup.value = null
    resetEditor()
    deleteDialogVisible.value = false
    if (props.groupName) {
      await router.push(`/${props.tenantName}/settings`)
    } else {
      message.value = 'Gruppe wurde gelöscht.'
    }
  } catch (caught) {
    error.value = getErrorMessage(caught, 'Gruppe konnte nicht gelöscht werden.')
  } finally {
    saving.value = false
  }
}

function upsertGroup(group: GroupSummary) {
  const summary = {
    id: group.id,
    name: group.name,
    displayName: group.displayName
  }
  const index = groupList.value.findIndex((item) => item.id === group.id)
  if (index === -1) {
    groupList.value = [...groupList.value, summary].sort((left, right) =>
      left.name.localeCompare(right.name)
    )
  } else {
    groupList.value.splice(index, 1, summary)
  }
}

function resetEditor() {
  displayName.value = ''
  name.value = ''
  permalinkTouched.value = false
  editingInitialGroupName.value = ''
  selectedUserId.value = null
  selectedRole.value = 'GroupMember'
}

async function loadTenantUsers() {
  if (tenantUsers.value.length > 0) return
  tenantUsers.value = await getTenantUsers(props.tenantName)
}

function isCurrentUserGroupOwner(group: GroupSummary): boolean {
  const currentUserId = authState.user?.userId
  return group.members.some(
    (member) => member.user.userId === currentUserId && member.role === 'GroupOwner'
  )
}
</script>

<template>
  <section class="settings-panel">
    <h1>Gruppen</h1>
    <p>Gemeinsame Bereiche und Mitglieder</p>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>

    <div v-if="loading" class="memory-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Gruppen werden geladen...</span>
    </div>

    <template v-else>
      <div v-if="!props.groupName" class="settings-toolbar">
        <Button label="Gruppe erstellen" icon="pi pi-users" @click="openCreateDialog" />
      </div>

      <div v-if="!props.groupName" class="settings-table group-list-table">
        <div class="settings-table-row settings-table-head groups-row">
          <span>Gruppe</span>
          <span>Permalink</span>
          <span></span>
        </div>
        <div v-for="group in groupList" :key="group.id" class="settings-table-row groups-row">
          <div>
            <strong>{{ group.displayName }}</strong>
            <span>@{{ group.displayName }}</span>
          </div>
          <span>@{{ group.name }}</span>
          <Button
            label="Bearbeiten"
            icon="pi pi-pencil"
            severity="secondary"
            outlined
            @click="openGroup(group.name)"
          />
        </div>
      </div>

      <div v-if="detailLoading" class="memory-loading">
        <ProgressSpinner class="small-spinner" />
        <span>Gruppe wird geladen...</span>
      </div>

      <form
        v-else-if="showDetail && selectedGroup && canManageSelectedGroup"
        class="settings-form group-detail-form"
        @submit.prevent="saveGroup"
      >
        <label>
          <span>Name</span>
          <InputText v-model="displayName" autocomplete="off" required />
        </label>
        <label>
          <span>Permalink</span>
          <InputText
            :model-value="name"
            autocomplete="off"
            required
            @update:model-value="updatePermalink"
          />
        </label>

        <ManagementList>
          <div
            v-for="member in selectedGroup.members"
            :key="member.user.userId"
            class="management-row"
          >
            <div class="management-row-main">
              <strong class="management-row-title">{{ member.user.displayName }}</strong>
            </div>
            <Select
              class="management-control"
              :model-value="member.role"
              :options="roleOptions"
              option-label="label"
              option-value="value"
              aria-label="Gruppen-Rolle"
              @update:model-value="changeRole(member, $event)"
            />
            <div class="management-actions">
              <Button
                type="button"
                icon="pi pi-times"
                severity="secondary"
                outlined
                size="small"
                aria-label="Mitglied entfernen"
                title="Mitglied entfernen"
                @click="removeMember(member)"
              />
            </div>
          </div>
        </ManagementList>

        <div class="management-add-row">
            <Select
              v-model="selectedUserId"
              :options="availableTenantUsers"
              option-label="label"
              option-value="value"
              placeholder="Tenant-Mitglied auswählen"
            />
            <Select
              v-model="selectedRole"
              :options="roleOptions"
              option-label="label"
              option-value="value"
              aria-label="Neue Gruppen-Rolle"
            />
            <Button
              type="button"
              label="Hinzufügen"
              icon="pi pi-plus"
              severity="secondary"
              outlined
              :disabled="selectedUserId === null"
              @click="addMember"
            />
        </div>

        <div class="button-row">
          <Button type="submit" label="Speichern" icon="pi pi-save" :loading="saving" />
          <Button
            type="button"
            label="Gruppe löschen"
            icon="pi pi-trash"
            severity="danger"
            outlined
            @click="openDeleteDialog"
          />
        </div>
      </form>
      <div v-else-if="showDetail && selectedGroup" class="settings-entry">
        <div>
          <strong>{{ selectedGroup.displayName }}</strong>
          <span>@{{ selectedGroup.name }}</span>
        </div>
        <i class="pi pi-users"></i>
      </div>
    </template>

    <Dialog v-model:visible="createDialogVisible" header="Gruppe erstellen" modal class="tenant-dialog">
      <form class="form" @submit.prevent="submitCreate">
        <label>
          Name
          <InputText v-model="createDisplayName" autocomplete="off" required />
        </label>
        <label>
          Permalink
          <InputText
            :model-value="createName"
            autocomplete="off"
            required
            @update:model-value="updateCreatePermalink"
          />
        </label>
        <div class="button-row">
          <Button type="submit" label="Erstellen" icon="pi pi-plus" :loading="saving" />
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

    <Dialog v-model:visible="deleteDialogVisible" header="Gruppe löschen" modal class="tenant-dialog">
      <div v-if="selectedGroup" class="form">
        <p>
          <strong>{{ selectedGroup.displayName }}</strong> wirklich rekursiv löschen?
        </p>
        <label>
          Display-Name zur Bestätigung
          <InputText
            v-model="deleteConfirmation"
            autocomplete="off"
            :placeholder="selectedGroup.displayName"
          />
        </label>
        <div class="button-row">
          <Button
            label="Löschen"
            icon="pi pi-trash"
            severity="danger"
            :disabled="!canConfirmDeleteGroup"
            :loading="saving"
            @click="submitDeleteGroup"
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
